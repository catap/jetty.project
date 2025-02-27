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

package org.eclipse.jetty.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslHandshakeListener;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTLSTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(SslContextFactory sslContextFactory, Handler handler) throws Exception
    {
        ExecutorThreadPool serverThreads = new ExecutorThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        connector = new ServerConnector(server, sslContextFactory);
        server.addConnector(connector);

        server.setHandler(handler);
        server.start();
    }

    private void startClient(SslContextFactory sslContextFactory) throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(sslContextFactory);
        client.setExecutor(clientThreads);
        client.start();
    }

    private SslContextFactory.Server createServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    private SslContextFactory.Client createClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testNoCommonTLSProtocol() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.setIncludeProtocols("TLSv1.3");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.setIncludeProtocols("TLSv1.2");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testNoCommonTLSCiphers() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.setIncludeCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.setExcludeCipherSuites(".*_SHA$");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnServer() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        serverTLSFactory.setIncludeProtocols("TLSv1.1");
        serverTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // In JDK 11+, a mismatch on the client does not generate any bytes towards
    // the server, while in previous JDKs the client sends to the server the close_notify.
    @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Test
    public void testMismatchBetweenTLSProtocolAndTLSCiphersOnClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        // TLS 1.1 protocol, but only TLS 1.2 ciphers.
        clientTLSFactory.setIncludeProtocols("TLSv1.1");
        clientTLSFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeFailed(Event event, Throwable failure)
            {
                clientLatch.countDown();
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .timeout(5, TimeUnit.SECONDS)
                .send());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandshakeSucceeded() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                serverLatch.countDown();
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                clientLatch.countDown();
            }
        });

        ContentResponse response = client.GET("https://localhost:" + connector.getLocalPort());
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // Excluded in JDK 11+ because resumed sessions cannot be compared
    // using their session IDs even though they are resumed correctly.
    @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Test
    public void testHandshakeSucceededWithSessionResumption() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        AtomicReference<byte[]> serverSession = new AtomicReference<>();
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                serverSession.set(event.getSSLEngine().getSession().getId());
            }
        });

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        startClient(clientTLSFactory);

        AtomicReference<byte[]> clientSession = new AtomicReference<>();
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                clientSession.set(event.getSSLEngine().getSession().getId());
            }
        });

        // First request primes the TLS session.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .header(HttpHeader.CONNECTION, "close")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertNotNull(serverSession.get());
        assertNotNull(clientSession.get());

        connector.removeBean(connector.getBean(SslHandshakeListener.class));
        client.removeBean(client.getBean(SslHandshakeListener.class));

        CountDownLatch serverLatch = new CountDownLatch(1);
        connector.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                if (Arrays.equals(serverSession.get(), event.getSSLEngine().getSession().getId()))
                    serverLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        client.addBean(new SslHandshakeListener()
        {
            @Override
            public void handshakeSucceeded(Event event)
            {
                if (Arrays.equals(clientSession.get(), event.getSSLEngine().getSession().getId()))
                    clientLatch.countDown();
            }
        });

        // Second request should have the same session ID.
        response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .header(HttpHeader.CONNECTION, "close")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(serverLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(1, TimeUnit.SECONDS));
    }

    // Excluded in JDK 11+ because resumed sessions cannot be compared
    // using their session IDs even though they are resumed correctly.
    @EnabledOnJre({JRE.JAVA_8, JRE.JAVA_9, JRE.JAVA_10})
    @Test
    public void testClientRawCloseDoesNotInvalidateSession() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        clientTLSFactory.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        Socket socket = new Socket(host, port);
        SSLSocket sslSocket = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket, host, port, true);
        CountDownLatch handshakeLatch1 = new CountDownLatch(1);
        AtomicReference<byte[]> session1 = new AtomicReference<>();
        sslSocket.addHandshakeCompletedListener(event ->
        {
            session1.set(event.getSession().getId());
            handshakeLatch1.countDown();
        });
        sslSocket.startHandshake();
        assertTrue(handshakeLatch1.await(5, TimeUnit.SECONDS));

        // In TLS 1.3 the server sends a NewSessionTicket post-handshake message
        // to enable session resumption and without a read, the message is not processed.
        try
        {
            sslSocket.setSoTimeout(1000);
            sslSocket.getInputStream().read();
        }
        catch (SocketTimeoutException expected)
        {
        }

        // The client closes abruptly.
        socket.close();

        // Try again and compare the session ids.
        socket = new Socket(host, port);
        sslSocket = (SSLSocket)clientTLSFactory.getSslContext().getSocketFactory().createSocket(socket, host, port, true);
        CountDownLatch handshakeLatch2 = new CountDownLatch(1);
        AtomicReference<byte[]> session2 = new AtomicReference<>();
        sslSocket.addHandshakeCompletedListener(event ->
        {
            session2.set(event.getSession().getId());
            handshakeLatch2.countDown();
        });
        sslSocket.startHandshake();
        assertTrue(handshakeLatch2.await(5, TimeUnit.SECONDS));

        assertArrayEquals(session1.get(), session2.get());

        sslSocket.close();
    }

    @Test
    public void testServerRawCloseDetectedByClient() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        serverTLSFactory.start();
        try (ServerSocket server = new ServerSocket(0))
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName("client");
            client = new HttpClient(createClientSslContextFactory())
            {
                @Override
                protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
                {
                    SslClientConnectionFactory ssl = (SslClientConnectionFactory)super.newSslClientConnectionFactory(connectionFactory);
                    ssl.setAllowMissingCloseMessage(false);
                    return ssl;
                }
            };
            client.setExecutor(clientThreads);
            client.start();

            CountDownLatch latch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .scheme(HttpScheme.HTTPS.asString())
                .send(result ->
                {
                    assertThat(result.getResponseFailure(), instanceOf(SSLException.class));
                    latch.countDown();
                });

            try (Socket socket = server.accept())
            {
                SSLSocket sslSocket = (SSLSocket)serverTLSFactory.getSslContext().getSocketFactory().createSocket(socket, null, socket.getPort(), true);
                sslSocket.setUseClientMode(false);
                BufferedReader reader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
                while (true)
                {
                    String line = reader.readLine();
                    if (StringUtil.isEmpty(line))
                        break;
                }

                // If the response is Content-Length delimited, allowing the
                // missing TLS Close Message is fine because the application
                // will see a EOFException anyway.
                // If the response is connection delimited, allowing the
                // missing TLS Close Message is bad because the application
                // will see a successful response with truncated content.

                // Verify that by not allowing the missing
                // TLS Close Message we get a response failure.

                byte[] half = new byte[8];
                String response = "HTTP/1.1 200 OK\r\n" +
//                        "Content-Length: " + (half.length * 2) + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
                OutputStream output = sslSocket.getOutputStream();
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.write(half);
                output.flush();
                // Simulate a truncation attack by raw closing
                // the socket in the try-with-resources block end.
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testHostNameVerificationFailure() throws Exception
    {
        SslContextFactory serverTLSFactory = createServerSslContextFactory();
        startServer(serverTLSFactory, new EmptyServerHandler());

        SslContextFactory clientTLSFactory = createClientSslContextFactory();
        // Make sure the host name is not verified at the TLS level.
        clientTLSFactory.setEndpointIdentificationAlgorithm(null);
        // Add host name verification after the TLS handshake.
        clientTLSFactory.setHostnameVerifier((host, session) -> false);
        startClient(clientTLSFactory);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .send(result ->
            {
                Throwable failure = result.getFailure();
                if (failure instanceof SSLPeerUnverifiedException)
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
