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

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AbstractClusteredSessionScavengingTest
 *
 * Test that a session that was live on node1, but then more
 * recently used on node2 does not expire over on node1.
 */
public abstract class AbstractClusteredSessionScavengingTest extends AbstractTestBase
{

    public void pause(int secs)
        throws InterruptedException
    {
        Thread.sleep(TimeUnit.SECONDS.toMillis(secs));
    }

    @Test
    public void testClusteredScavenge() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int maxInactivePeriod = 5; //session will timeout after 5 seconds
        int scavengePeriod = 1; //scavenging occurs every 1 seconds

        DefaultSessionCacheFactory cacheFactory1 = new DefaultSessionCacheFactory();
        cacheFactory1.setEvictionPolicy(SessionCache.NEVER_EVICT); //don't evict sessions
        SessionDataStoreFactory storeFactory1 = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory1).setGracePeriodSec(scavengePeriod);
        ((AbstractSessionDataStoreFactory)storeFactory1).setSavePeriodSec(0); //always save when the session exits

        TestServer server1 = new TestServer(0, maxInactivePeriod, scavengePeriod, cacheFactory1, storeFactory1);
        TestServlet servlet1 = new TestServlet();
        ServletHolder holder1 = new ServletHolder(servlet1);
        ServletContextHandler context = server1.addContext(contextPath);
        TestContextScopeListener scopeListener = new TestContextScopeListener();
        context.addEventListener(scopeListener);
        TestSessionListener listener1 = new TestSessionListener();
        context.getSessionHandler().addEventListener(listener1);
        context.addServlet(holder1, servletMapping);
        SessionHandler m1 = context.getSessionHandler();

        try
        {
            server1.start();
            int port1 = server1.getPort();

            DefaultSessionCacheFactory cacheFactory2 = new DefaultSessionCacheFactory();
            cacheFactory2.setEvictionPolicy(SessionCache.NEVER_EVICT); //don't evict sessions
            SessionDataStoreFactory storeFactory2 = createSessionDataStoreFactory();
            ((AbstractSessionDataStoreFactory)storeFactory2).setGracePeriodSec(scavengePeriod);
            ((AbstractSessionDataStoreFactory)storeFactory2).setSavePeriodSec(0); //always save when the session exits
            TestServer server2 = new TestServer(0, maxInactivePeriod, scavengePeriod, cacheFactory2, storeFactory2);
            ServletContextHandler context2 = server2.addContext(contextPath);
            TestContextScopeListener scopeListener2 = new TestContextScopeListener();
            context2.addEventListener(scopeListener2);
            context2.addServlet(TestServlet.class, servletMapping);
            SessionHandler m2 = context2.getSessionHandler();

            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                client.start();
                try
                {
                    // Perform one request to server1 to create a session
                    CountDownLatch synchronizer = new CountDownLatch(1);
                    scopeListener.setExitSynchronizer(synchronizer);
                    ContentResponse response1 = client.GET("http://localhost:" + port1 + contextPath + servletMapping.substring(1) + "?action=init");
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    assertTrue(response1.getContentAsString().startsWith("init"));
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);

                    //ensure request has finished being handled
                    synchronizer.await(5, TimeUnit.SECONDS);
                    
                    assertEquals(1, ((DefaultSessionCache)m1.getSessionCache()).getSessionsCurrent());
                    assertEquals(1, ((DefaultSessionCache)m1.getSessionCache()).getSessionsMax());
                    assertEquals(1, ((DefaultSessionCache)m1.getSessionCache()).getSessionsTotal());
                    // Mangle the cookie, replacing Path with $Path, etc.
                    sessionCookie = sessionCookie.replaceFirst("(\\W)(P|p)ath=", "$1\\$Path=");
                    String id = TestServer.extractSessionId(sessionCookie);
                    
                    //Peek at the contents of the cache without doing all the reference counting etc
                    Session s1 = ((AbstractSessionCache)m1.getSessionCache()).doGet(id);
                    assertNotNull(s1);
                    long expiry = s1.getSessionData().getExpiry();

                    //Now do requests for the session to node2. This will update the expiry time on the session.
                    //Send requests for the next maxInactiveInterval, pausing a little between each request. 
                    int requestInterval = 500; //ms pause between requests
                    long start = System.currentTimeMillis();
                    long end = expiry;
                    long time = start;
                    while (time < end)
                    {
                        synchronizer = new CountDownLatch(1);
                        scopeListener2.setExitSynchronizer(synchronizer);
                        Request request = client.newRequest("http://localhost:" + port2 + contextPath + servletMapping.substring(1));
                        request.header("Cookie", sessionCookie); //use existing session
                        ContentResponse response2 = request.send();
                        assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
                        assertTrue(response2.getContentAsString().startsWith("test"));
                        //ensure request has finished being handled
                        synchronizer.await(5, TimeUnit.SECONDS);
                        Thread.sleep(requestInterval);
                        assertSessionCounts(1, 1, 1, m2);
                        time = System.currentTimeMillis();
                    }

                    //session on node1 should be eligible for scavenge
                    //ensure scavenger has run on node1
                    Thread.sleep(TimeUnit.SECONDS.toMillis(scavengePeriod)); // wait until just after the original expiry time has passed

                    //check that the session wasn't in fact scavenged because it was in use on node1
                    assertFalse(listener1._destroys.contains(TestServer.extractSessionId(sessionCookie)));
                    assertAfterScavenge(m1);
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public void assertAfterSessionCreated(SessionHandler m)
    {
        assertSessionCounts(1, 1, 1, m);
    }

    public void assertAfterScavenge(SessionHandler manager)
    {
        assertSessionCounts(1, 1, 1, manager);
    }

    public void assertSessionCounts(int current, int max, int total, SessionHandler manager)
    {
        assertEquals(current, ((DefaultSessionCache)manager.getSessionCache()).getSessionsCurrent());
        assertEquals(max, ((DefaultSessionCache)manager.getSessionCache()).getSessionsMax());
        assertEquals(total, ((DefaultSessionCache)manager.getSessionCache()).getSessionsTotal());
    }

    public static class TestSessionListener implements HttpSessionListener
    {
        public Set<String> _creates = new HashSet<>();
        public Set<String> _destroys = new HashSet<>();

        @Override
        public void sessionDestroyed(HttpSessionEvent se)
        {
            _destroys.add(se.getSession().getId());
        }

        @Override
        public void sessionCreated(HttpSessionEvent se)
        {
            _creates.add(se.getSession().getId());
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("test", "init");
                sendResult(session, httpServletResponse.getWriter());
            }
            else
            {
                HttpSession session = request.getSession(false);


                if (session != null)
                {
                    session.setAttribute("test", "test");
                }
                
                // if we node hopped we should get the session and test should already be present
                sendResult(session, httpServletResponse.getWriter());

            }
        }

        private void sendResult(HttpSession session, PrintWriter writer)
        {
            if (session != null)
            {
                writer.println(session.getAttribute("test"));
            }
            else
            {
                writer.println("null");
            }
        }
    }
}
