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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CustomRequestLogTest
{
    RequestLog _log;
    Server _server;
    LocalConnector _connector;
    BlockingQueue<String> _entries = new BlockingArrayQueue<>();
    String _tmpDir;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _tmpDir = new File(System.getProperty("java.io.tmpdir")).getCanonicalPath();
    }

    void testHandlerServerStart(String formatString) throws Exception
    {
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/context");
        context.setResourceBase(_tmpDir);
        context.addServlet(TestServlet.class, "/servlet/*");

        TestRequestLogWriter writer = new TestRequestLogWriter();
        _log = new CustomRequestLog(writer, formatString);
        _server.setRequestLog(_log);
        _server.setHandler(context);
        _server.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testLogFilename() throws Exception
    {
        testHandlerServerStart("Filename: %f");

        _connector.getResponse("GET /context/servlet/info HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("Filename: " + _tmpDir + File.separator + "servlet" + File.separator + "info"));
    }

    @Test
    public void testLogRequestHandler() throws Exception
    {
        testHandlerServerStart("RequestHandler: %R");

        _connector.getResponse("GET /context/servlet/ HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, Matchers.containsString("TestServlet"));
    }

    class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            try
            {
                _entries.add(requestEntry);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getRequestURI().contains("error404"))
            {
                response.setStatus(404);
            }
            else if (request.getRequestURI().contains("error301"))
            {
                response.setStatus(301);
            }
            else if (request.getHeader("echo") != null)
            {
                ServletOutputStream outputStream = response.getOutputStream();
                outputStream.print(request.getHeader("echo"));
            }
            else if (request.getRequestURI().contains("responseHeaders"))
            {
                response.addHeader("Header1", "value1");
                response.addHeader("Header2", "value2");
            }
        }
    }
}
