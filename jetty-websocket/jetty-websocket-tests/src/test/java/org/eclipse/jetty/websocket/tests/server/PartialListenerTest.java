//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPartialListener;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.util.TextUtil;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PartialListenerTest
{
    private Server server;
    private PartialCreator partialCreator;
    private WebSocketClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder closeEndpoint = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(SECONDS.toMillis(2));
                partialCreator = new PartialCreator();
                factory.setCreator(partialCreator);
            }
        });
        context.addServlet(closeEndpoint, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    private void close(Session session)
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    public void testPartialText() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            RemoteEndpoint clientRemote = session.getRemote();
            clientRemote.sendPartialString("hello", false);
            clientRemote.sendPartialString(" ", false);
            clientRemote.sendPartialString("world", true);

            PartialEndpoint serverEndpoint = partialCreator.partialEndpoint;

            String event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=hello, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=world, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }

    @Test
    public void testPartialBinary() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            RemoteEndpoint clientRemote = session.getRemote();
            clientRemote.sendPartialBytes(BufferUtil.toBuffer("hello"), false);
            clientRemote.sendPartialBytes(BufferUtil.toBuffer(" "), false);
            clientRemote.sendPartialBytes(BufferUtil.toBuffer("world"), true);

            PartialEndpoint serverEndpoint = partialCreator.partialEndpoint;

            String event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<hello>>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<< >>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<world>>>, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }

    /**
     * Test to ensure that the internal state tracking the partial messages is reset after each complete message.
     */
    @Test
    public void testPartial_TextBinaryText() throws Exception
    {
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        CloseTrackingEndpoint clientEndpoint = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        Future<Session> futSession = client.connect(clientEndpoint, wsUri, request);

        Session session = null;
        try (StacklessLogging ignore = new StacklessLogging(WebSocketSession.class))
        {
            session = futSession.get(5, SECONDS);

            RemoteEndpoint clientRemote = session.getRemote();
            clientRemote.sendPartialString("hello", false);
            clientRemote.sendPartialString(" ", false);
            clientRemote.sendPartialString("world", true);

            clientRemote.sendPartialBytes(BufferUtil.toBuffer("greetings"), false);
            clientRemote.sendPartialBytes(BufferUtil.toBuffer(" "), false);
            clientRemote.sendPartialBytes(BufferUtil.toBuffer("mars"), true);

            clientRemote.sendPartialString("salutations", false);
            clientRemote.sendPartialString(" ", false);
            clientRemote.sendPartialString("phobos", true);

            PartialEndpoint serverEndpoint = partialCreator.partialEndpoint;

            String event;

            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=hello, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=world, fin=true]"));

            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<greetings>>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<< >>>, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("BINARY[payload=<<<mars>>>, fin=true]"));

            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=salutations, fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload= , fin=false]"));
            event = serverEndpoint.partialEvents.poll(5, SECONDS);
            assertThat("Event", event, is("TEXT[payload=phobos, fin=true]"));
        }
        finally
        {
            close(session);
        }
    }


    public static class PartialCreator implements WebSocketCreator
    {
        public PartialEndpoint partialEndpoint;

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            partialEndpoint = new PartialEndpoint();
            return partialEndpoint;
        }
    }

    public static class PartialEndpoint implements WebSocketPartialListener
    {
        public Session session;
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public LinkedBlockingQueue<String> partialEvents = new LinkedBlockingQueue<>();

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public void onWebSocketPartialBinary(ByteBuffer payload, boolean fin)
        {
            // our testcases always send bytes limited in the US-ASCII range.
            partialEvents.offer(String.format("BINARY[payload=<<<%s>>>, fin=%b]", BufferUtil.toUTF8String(payload), fin));
        }

        @Override
        public void onWebSocketPartialText(String payload, boolean fin)
        {
            partialEvents.offer(String.format("TEXT[payload=%s, fin=%b]", TextUtil.maxStringLength(30, payload), fin));
        }
    }
}
