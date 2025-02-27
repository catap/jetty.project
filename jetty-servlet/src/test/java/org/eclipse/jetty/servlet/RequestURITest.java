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

package org.eclipse.jetty.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;

public class RequestURITest
{
    public static Stream<Arguments> data()
    {
        List<Arguments> ret = new ArrayList<>();
        ret.add(Arguments.of("/hello", "/hello", null));
        ret.add(Arguments.of("/hello%20world", "/hello%20world", null));
        ret.add(Arguments.of("/hello;world", "/hello;world", null));
        ret.add(Arguments.of("/hello:world", "/hello:world", null));
        ret.add(Arguments.of("/hello!world", "/hello!world", null));
        ret.add(Arguments.of("/hello?world", "/hello", "world"));
        ret.add(Arguments.of("/hello?type=world", "/hello", "type=world"));
        ret.add(Arguments.of("/hello?type=wo&rld", "/hello", "type=wo&rld"));
        ret.add(Arguments.of("/hello?type=wo%20rld", "/hello", "type=wo%20rld"));
        ret.add(Arguments.of("/hello?type=wo+rld", "/hello", "type=wo+rld"));
        ret.add(Arguments.of("/It%27s%20me%21", "/It%27s%20me%21", null));
        // try some slash encoding (with case preservation tests)
        ret.add(Arguments.of("/hello%2fworld", "/hello%2fworld", null));
        ret.add(Arguments.of("/hello%2Fworld", "/hello%2Fworld", null));
        ret.add(Arguments.of("/%2f%2Fhello%2Fworld", "/%2f%2Fhello%2Fworld", null));
        // try some "?" encoding (should not see as query string)
        ret.add(Arguments.of("/hello%3Fworld", "/hello%3Fworld", null));
        // try some strange encodings (should preserve them)
        ret.add(Arguments.of("/hello%252Fworld", "/hello%252Fworld", null));
        ret.add(Arguments.of("/hello%u0025world", "/hello%u0025world", null));
        ret.add(Arguments.of("/hello-euro-%E2%82%AC", "/hello-euro-%E2%82%AC", null));
        ret.add(Arguments.of("/hello-euro?%E2%82%AC", "/hello-euro", "%E2%82%AC"));
        // test the ascii control characters (just for completeness)
        for (int i = 0x0; i < 0x1f; i++)
        {
            String raw = String.format("/hello%%%02Xworld", i);
            ret.add(Arguments.of(raw, raw, null));
        }

        return ret.stream();
    }

    @SuppressWarnings("serial")
    public static class RequestUriServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            PrintWriter out = resp.getWriter();
            out.println("RequestURI: " + req.getRequestURI());
            out.println("QueryString: " + req.getQueryString());
            out.print("FullURI: " + req.getRequestURI());
            if (req.getQueryString() != null)
            {
                out.print('?');
                out.print(req.getQueryString());
            }
            out.println();
        }
    }

    private static Server server;
    private static URI serverURI;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(RequestUriServlet.class, "/*");

        server.start();

        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverURI = new URI(String.format("http://%s:%d/", host, port));
    }

    @AfterAll
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    protected Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    /**
     * Read entire response from the client. Close the output.
     *
     * @param client Open client socket.
     * @return The response string.
     * @throws IOException in case of I/O problems
     */
    protected static String readResponse(Socket client) throws IOException
    {
        // TODO: use HttpTester.Response.parse instead
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream())))
        {
            String line;

            while ((line = br.readLine()) != null)
            {
                sb.append(line);
                sb.append('\n');
            }

            return sb.toString();
        }
        catch (IOException e)
        {
            System.err.println(e + " while reading '" + sb + "'");
            throw e;
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetRequestURI_HTTP10(String rawpath, String expectedReqUri, String expectedQuery) throws Exception
    {
        try (Socket client = newSocket(serverURI.getHost(), serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = String.format("GET %s HTTP/1.0\r\n\r\n", rawpath);
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            // TODO: is HTTP/1.1 response appropriate for a HTTP/1.0 request?
            assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("RequestURI: " + expectedReqUri));
            assertThat(response, Matchers.containsString("QueryString: " + expectedQuery));
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testGetRequestURI_HTTP11(String rawpath, String expectedReqUri, String expectedQuery) throws Exception
    {
        try (Socket client = newSocket(serverURI.getHost(), serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            String request = String.format("GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", rawpath, serverURI.getHost());
            os.write(request.getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            // Read the response.
            String response = readResponse(client);

            assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            assertThat(response, Matchers.containsString("RequestURI: " + expectedReqUri));
            assertThat(response, Matchers.containsString("QueryString: " + expectedQuery));
        }
    }
}
