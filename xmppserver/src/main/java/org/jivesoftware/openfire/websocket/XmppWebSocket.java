/*
 * Copyright (C) 2015 Tom Evans, 2022 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.websocket;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.QName;
import org.dom4j.io.XMPPPacketReader;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.jivesoftware.openfire.*;
import org.jivesoftware.openfire.entitycaps.EntityCapabilitiesManager;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.multiplex.UnknownStanzaException;
import org.jivesoftware.openfire.net.SASLAuthentication;
import org.jivesoftware.openfire.net.SASLAuthentication.Status;
import org.jivesoftware.openfire.nio.ClientConnectionHandler;
import org.jivesoftware.openfire.nio.OfflinePacketDeliverer;
import org.jivesoftware.openfire.session.ConnectionSettings;
import org.jivesoftware.openfire.session.LocalClientSession;
import org.jivesoftware.openfire.streammanagement.StreamManager;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.SystemProperty;
import org.jivesoftware.util.TaskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.StreamError;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.TimerTask;

/**
 * This class handles all WebSocket events for the corresponding connection with a remote peer.
 * Specifically the XMPP session is managed concurrently with the WebSocket session, including all
 * framing and authentication requirements. Packets received from the remote peer are forwarded as
 * needed via a {@link SessionPacketRouter}, and packets destined for the remote peer are forwarded
 * via the corresponding {@link RemoteEndpoint}.
 */

@WebSocket
public class XmppWebSocket {

    /**
     * Controls if clients that do websockets without the required XMPP framing will get their 'stream' element names
     * replaced, so that they are able to connect.
     *
     * @see <a href="https://igniterealtime.atlassian.net/browse/OF-2479">OF-2479: Allow Tsung to test with websockets</a>
     */
    private static final SystemProperty<Boolean> STREAM_SUBSTITUTION_ENABLED = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("xmpp.websocket.stream-substitution-enabled")
        .setDefaultValue(false)
        .setDynamic(true)
        .build();

    private static final String STREAM_HEADER = "open";
    private static final String STREAM_FOOTER = "close";
    private static final String FRAMING_NAMESPACE = "urn:ietf:params:xml:ns:xmpp-framing";

    private static Logger Log = LoggerFactory.getLogger( XmppWebSocket.class );
    private static GenericObjectPool<XMPPPacketReader> readerPool;

    private SessionPacketRouter router;
    private Session wsSession;
    private WebSocketConnection wsConnection;
    private LocalClientSession xmppSession;
    private boolean startedSASL = false;
    private Status saslStatus;
    private TimerTask pingTask;

    public XmppWebSocket() {
        if (readerPool == null) {
            initializePool();
        }
    }

    // WebSocket event handlers

    @OnWebSocketConnect
    public void onConnect(Session session)
    {
        wsSession = session;
        final PacketDeliverer backupDeliverer = ClientConnectionHandler.BACKUP_PACKET_DELIVERY_ENABLED.getValue() ? new OfflinePacketDeliverer() : null;
        wsConnection = new WebSocketConnection(this, backupDeliverer, session.getRemoteAddress());
        pingTask = new PingTask();
        TaskEngine.getInstance().schedule(pingTask, Duration.ofMinutes(1), Duration.ofMinutes(1));
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        // Handle asynchronously, to prevent deadlocks. See OF-2473.
        HttpBindManager.getInstance().getSessionManager().execute(() -> {
            try {
                closeSession();
            } catch (Throwable t) {
                Log.warn("An exception occurred while trying to process @OnWebSocketClose for session {}.", wsSession, t);
            }
        });
    }

    @OnWebSocketMessage
    public void onTextMethod(String stanza)
    {
        XMPPPacketReader reader = null;
        try {
            reader = readerPool.borrowObject();

            if (STREAM_SUBSTITUTION_ENABLED.getValue()) {
                // Allow clients that do websockets without the required XMPP framing to connect. See https://igniterealtime.atlassian.net/browse/OF-2479
                if (stanza.startsWith("<?xml version='1.0'?><stream:stream ")) {
                    stanza = stanza.replace("<?xml version='1.0'?><stream:stream ", "<open ");
                    stanza = stanza.replace("jabber:client", "urn:ietf:params:xml:ns:xmpp-framing");
                    stanza += "</open>";
                }
                if (stanza.startsWith("</stream:stream>")) {
                    stanza = stanza.replace("</stream:stream>", "<close xmlns='urn:ietf:params:xml:ns:xmpp-framing' />");
                }
            }

            Document doc = reader.read(new StringReader(stanza));

            if (xmppSession == null) {
                initiateSession(doc.getRootElement());
            } else {
                processStanza(doc.getRootElement());
            }
        } catch (Exception ex) {
            Log.error("Failed to process XMPP stanza", ex);
        } finally {
            if (reader != null) {
                readerPool.returnObject(reader);
            }
        }
    }

    @OnWebSocketError
    public void onError(Throwable error)
    {
        Log.error("Error detected; session: {}", wsSession, error);

        // Handle asynchronously, to prevent deadlocks. See OF-2473.
        HttpBindManager.getInstance().getSessionManager().execute(() -> {
            synchronized (this) {
                try {
                    Log.debug("Attempting to close session on which an error occurred.");
                    if (isWebSocketOpen()) {
                        closeStream(new StreamError(StreamError.Condition.internal_server_error));
                    }
                } catch (Exception e) {
                    Log.error("Error disconnecting websocket", e);
                } finally {
                    wsSession = null;
                }
            }
        });
    }

    // local (package) visibility

    synchronized boolean isWebSocketOpen() {
        return wsSession != null && wsSession.isOpen();
    }

    synchronized boolean isWebSocketSecure() {
        return wsSession != null && wsSession.isSecure();
    }

    synchronized void closeWebSocket()
    {
        if (isWebSocketOpen())
        {
            wsSession.close();
        }
        wsSession = null;
    }

    void closeSession() {
        closeSession(null);
    }

    void closeSession(@Nullable final StreamError error) {
        synchronized (this) {
            if (isWebSocketOpen()) {
                closeStream(error);
            }
        }
        if (xmppSession != null) {
            if (!xmppSession.getStreamManager().getResume()) {
                Log.debug( "Closing session {}", xmppSession );
                xmppSession.close();
                SessionManager.getInstance().removeSession(xmppSession);
            }
            xmppSession = null;
        }
    }

    public void setXmppSession(LocalClientSession session) {
        this.xmppSession = session;
    }

    /**
     * Send an XML packet to the remote peer
     *
     * @param packet XML to be sent to client
     */
    synchronized void deliver(String packet)
    {
        if (isWebSocketOpen())
        {
            try {
                if (xmppSession != null) { // OF-2265 In certain circumstances, the xmppSession can be absent (eg: when sending an error).
                    xmppSession.incrementServerPacketCount();
                } else {
                    Log.debug("Packet delivery when no xmppSession is present. Should only occur exceptionally. Session: {}, Packet: {}", wsSession, packet);
                }
                wsSession.getRemote().sendStringByFuture(packet);
            } catch (Exception e) {
                Log.error("Packet delivery failed; session: " + wsSession, e);
                Log.warn("Failed to deliver packet:\n" + packet );
            }
        } else {
            Log.warn("Failed to deliver packet; socket is closed:\n" + packet);
        }
    }


    static boolean isCompressionEnabled() {
        return JiveGlobals.getProperty(
                ConnectionSettings.Client.COMPRESSION_SETTINGS, Connection.CompressionPolicy.optional.toString())
                .equalsIgnoreCase(Connection.CompressionPolicy.optional.toString());
    }

    // helper/utility methods

    /*
     * Process stream headers/footers and authentication stanzas locally;
     * otherwise delegate stanza handling to the session packet router.
     */
    private void processStanza(Element stanza) {

        try {
            String tag = stanza.getName();
            if (STREAM_FOOTER.equals(tag)) {
                xmppSession.getStreamManager().formalClose();
                closeStream(null);
            } else if ("auth".equals(tag)) {
                // User is trying to authenticate using SASL
                startedSASL = true;
                // Process authentication stanza
                xmppSession.incrementClientPacketCount();
                saslStatus = SASLAuthentication.handle(xmppSession, stanza);
            } else if (startedSASL && "response".equals(tag) || "abort".equals(tag)) {
                // User is responding to SASL challenge. Process response
                xmppSession.incrementClientPacketCount();
                saslStatus = SASLAuthentication.handle(xmppSession, stanza);
            } else if (STREAM_HEADER.equals(tag)) {
                // restart the stream
                openStream(stanza.attributeValue(QName.get("lang", XMLConstants.XML_NS_URI), "en"), stanza.attributeValue("from"));
                configureStream();
            } else if (Status.authenticated.equals(saslStatus)) {
                if (router == null) {
                    if (StreamManager.isStreamManagementActive()) {
                        router = new StreamManagementPacketRouter(xmppSession);
                    } else {
                        // fall back for older Openfire installations
                        router = new SessionPacketRouter(xmppSession);
                    }
                }
                router.route(stanza);
            } else {
                // require authentication
                Log.warn("Not authorized: " + stanza.asXML());
                sendPacketError(stanza, PacketError.Condition.not_authorized);
            }
        } catch (UnknownStanzaException use) {
            Log.warn("Received invalid stanza: " + stanza.asXML());
            sendPacketError(stanza, PacketError.Condition.bad_request);
        } catch (Exception ex) {
            Log.error("Failed to process incoming stanza: " + stanza.asXML(), ex);
            closeStream(new StreamError(StreamError.Condition.internal_server_error));
        }
    }

    /*
     * Initiate the stream and corresponding XMPP session.
     */
    private void initiateSession(Element stanza) {

        String host = stanza.attributeValue("to");
        StreamError streamError = null;
        Locale language = Locale.forLanguageTag(stanza.attributeValue(QName.get("lang", XMLConstants.XML_NS_URI), "en"));
        if (STREAM_FOOTER.equals(stanza.getName())) {
            // an error occurred while setting up the session
            Log.warn("Client closed stream before session was established");
        } else if (!STREAM_HEADER.equals(stanza.getName())) {
            streamError = new StreamError(StreamError.Condition.unsupported_stanza_type);
            Log.warn("Closing session due to incorrect stream header. Tag: " + stanza.getName());
        } else if (!FRAMING_NAMESPACE.equals(stanza.getNamespace().getURI())) {
            // Validate the stream namespace (https://tools.ietf.org/html/rfc7395#section-3.3.2)
            streamError = new StreamError(StreamError.Condition.invalid_namespace);
            Log.warn("Closing session due to invalid namespace in stream header. Namespace: " + stanza.getNamespace().getURI());
        } else if (!validateHost(host)) {
            streamError = new StreamError(StreamError.Condition.host_unknown);
            Log.warn("Closing session due to incorrect hostname in stream header. Host: " + host);
        } else {
            // valid stream; initiate session
            xmppSession = SessionManager.getInstance().createClientSession(wsConnection, language);
            xmppSession.setSessionData("ws", Boolean.TRUE);
        }

        if (xmppSession == null) {
            closeStream(streamError);
        } else {
            openStream(language.toLanguageTag(), stanza.attributeValue("from"));
            configureStream();
        }
    }

    private boolean validateHost(String host) {
        boolean result = true;
        if (JiveGlobals.getBooleanProperty("xmpp.client.validate.host", false)) {
            result = XMPPServer.getInstance().getServerInfo().getXMPPDomain().equals(host);
        }
        return result;
    }

    /*
     * Prepare response for stream initiation (sasl) or stream restart (features).
     */
    private void configureStream() {

        StringBuilder sb = new StringBuilder(250);
        sb.append("<stream:features xmlns:stream='http://etherx.jabber.org/streams'>");
        if (saslStatus == null) {
            // Include available SASL Mechanisms
            sb.append(SASLAuthentication.getSASLMechanisms(xmppSession));
            if (XMPPServer.getInstance().getIQRouter().supports("jabber:iq:auth")) {
                sb.append("<auth xmlns='http://jabber.org/features/iq-auth'/>");
            }
        } else if (saslStatus.equals(Status.authenticated)) {
            // Include Stream features
            sb.append(String.format("<bind xmlns='%s'/>", "urn:ietf:params:xml:ns:xmpp-bind"));
            sb.append(String.format("<session xmlns='%s'><optional/></session>", "urn:ietf:params:xml:ns:xmpp-session"));

            if (StreamManager.isStreamManagementActive()) {
                sb.append(String.format("<sm xmlns='%s'/>", StreamManager.NAMESPACE_V3));
            }
        }

        // Add XEP-0115 entity capabilities for the server, so that peer can skip service discovery.
        final String ver = EntityCapabilitiesManager.getLocalDomainVerHash();
        if ( ver != null ) {
            sb.append( String.format( "<c xmlns=\"http://jabber.org/protocol/caps\" hash=\"sha-1\" node=\"%s\" ver=\"%s\"/>", EntityCapabilitiesManager.OPENFIRE_IDENTIFIER_NODE, ver ) );
        }

        sb.append("</stream:features>");
        deliver(sb.toString());
    }

    private void openStream(String lang, String jid) {

        xmppSession.incrementClientPacketCount();
        StringBuilder sb = new StringBuilder(250);
        sb.append("<open ");
        if (jid != null) {
            sb.append("to='").append(jid).append("' ");
        }
        sb.append("from='").append(XMPPServer.getInstance().getServerInfo().getXMPPDomain()).append("' ");
        sb.append("id='").append(xmppSession.getStreamID().toString()).append("' ");
        sb.append("xmlns='").append(FRAMING_NAMESPACE).append("' ");
        sb.append("xml:lang='").append(lang).append("' ");
        sb.append("version='1.0'/>");
        deliver(sb.toString());
    }

    private synchronized void closeStream(StreamError streamError)
    {
        if (isWebSocketOpen()) {

            if (streamError != null) {
                deliver(streamError.toXML());
            }

            StringBuilder sb = new StringBuilder(250);
            sb.append("<close ");
            sb.append("xmlns='").append(FRAMING_NAMESPACE).append("'");
            sb.append("/>");
            deliver(sb.toString());
            closeWebSocket();
        }
    }

    private void sendPacketError(Element stanza, PacketError.Condition condition) {
        Element reply = stanza.createCopy();
        reply.addAttribute("type", "error");
        reply.addAttribute("to", stanza.attributeValue("from"));
        reply.addAttribute("from", stanza.attributeValue("to"));
        reply.add(new PacketError(condition).getElement());
        deliver(reply.asXML());
    }

    private synchronized void initializePool() {
        if (readerPool == null) {
            readerPool = new GenericObjectPool<XMPPPacketReader>(new XMPPPPacketReaderFactory());
            readerPool.setMaxTotal(-1);
            readerPool.setBlockWhenExhausted(false);
            readerPool.setTestOnReturn(true);
            readerPool.setTimeBetweenEvictionRunsMillis(Duration.ofMinutes(1).toMillis());
        }
    }

    //-- Keep-alive ping for idle peers

    private final class PingTask extends TimerTask {
        private boolean lastPingFailed = false;

        @Override
        public void run() {
            if (!isWebSocketOpen()) {
                TaskEngine.getInstance().cancelScheduledTask(pingTask);
            } else {
                Instant idleTime = Instant.now().minus(Duration.ofMinutes(1));
                if (xmppSession.getLastActiveDate().toInstant().isAfter(idleTime)) {
                    return;
                }
                try {
                    // see https://tools.ietf.org/html/rfc6455#section-5.5.2
                    wsSession.getRemote().sendPing(null);
                    lastPingFailed = false;
                } catch (IOException ioe) {
                    Log.error("Failed to ping remote peer: " + wsSession, ioe);
                    if (lastPingFailed) {
                        closeSession();
                        TaskEngine.getInstance().cancelScheduledTask(pingTask);
                    } else {
                        lastPingFailed = true;
                    }
                }
            }
        }
    }
}
