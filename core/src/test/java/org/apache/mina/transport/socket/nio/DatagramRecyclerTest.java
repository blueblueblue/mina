/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.transport.socket.nio;

import java.net.InetSocketAddress;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.ExpiringSessionRecycler;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.util.AvailablePortFinder;

/**
 * Tests if datagram sessions are recycled properly.
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */
public class DatagramRecyclerTest extends TestCase {
    private NioDatagramAcceptor acceptor;
    private NioDatagramConnector connector;

    public DatagramRecyclerTest() {
        // Do nothing
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        acceptor = new NioDatagramAcceptor();
        connector = new NioDatagramConnector();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        acceptor.dispose();
        connector.dispose();
    }

    public void testDatagramRecycler() throws Exception {
        int port = AvailablePortFinder.getNextAvailable(1024);
        ExpiringSessionRecycler recycler = new ExpiringSessionRecycler(1, 1);

        MockHandler acceptorHandler = new MockHandler();
        MockHandler connectorHandler = new MockHandler();

        acceptor.setHandler(acceptorHandler);
        acceptor.setSessionRecycler(recycler);
        acceptor.bind(new InetSocketAddress(port));

        try {
            connector.setHandler(connectorHandler);
            ConnectFuture future = connector.connect(new InetSocketAddress(
                    "localhost", port));
            future.awaitUninterruptibly();

            // Write whatever to trigger the acceptor.
            future.getSession().write(IoBuffer.allocate(1))
                    .awaitUninterruptibly();

            // Close the client-side connection.
            // This doesn't mean that the acceptor-side connection is also closed.
            // The life cycle of the acceptor-side connection is managed by the recycler.
            future.getSession().close(true);
	    future.getSession().getCloseFuture().awaitUninterruptibly();
            Assert.assertTrue(future.getSession().getCloseFuture().isClosed());

            // Wait until the acceptor-side connection is closed.
            while (acceptorHandler.session == null) {
                Thread.yield();
            }
            acceptorHandler.session.getCloseFuture().awaitUninterruptibly(3000);

            // Is it closed?
            Assert.assertTrue(acceptorHandler.session.getCloseFuture()
                    .isClosed());

            Thread.sleep(1000);

            Assert.assertEquals("CROPSECL", connectorHandler.result.toString());
            Assert.assertEquals("CROPRECL", acceptorHandler.result.toString());
        } finally {
            acceptor.unbind();
        }
    }
    
    public void testCloseRequest() throws Exception {
        int port = AvailablePortFinder.getNextAvailable(1024);
        ExpiringSessionRecycler recycler = new ExpiringSessionRecycler(10, 1);

        MockHandler acceptorHandler = new MockHandler();
        MockHandler connectorHandler = new MockHandler();

        acceptor.getSessionConfig().setIdleTime(IdleStatus.READER_IDLE, 1);
        acceptor.setHandler(acceptorHandler);
        acceptor.setSessionRecycler(recycler);
        acceptor.bind(new InetSocketAddress(port));

        try {
            connector.setHandler(connectorHandler);
            ConnectFuture future = connector.connect(new InetSocketAddress(
                    "localhost", port));
            future.awaitUninterruptibly();
            
            // Write whatever to trigger the acceptor.
            future.getSession().write(IoBuffer.allocate(1)).awaitUninterruptibly();

            // Make sure the connection is closed before recycler closes it.
            while (acceptorHandler.session == null) {
                Thread.yield();
            }
            acceptorHandler.session.close(true);
            Assert.assertTrue(
                    acceptorHandler.session.getCloseFuture().awaitUninterruptibly(3000));
            
            IoSession oldSession = acceptorHandler.session;

            // Wait until all events are processed and clear the state.
            long startTime = System.currentTimeMillis();
            while (acceptorHandler.result.length() < 8) {
                Thread.yield();
                if (System.currentTimeMillis() - startTime > 5000) {
                    throw new Exception();
                }
            }
            acceptorHandler.result.setLength(0);
            acceptorHandler.session = null;
            
            // Write whatever to trigger the acceptor again.
            WriteFuture wf = future.getSession().write(
                    IoBuffer.allocate(1)).awaitUninterruptibly();
            Assert.assertTrue(wf.isWritten());
            
            // Make sure the connection is closed before recycler closes it.
            while (acceptorHandler.session == null) {
                Thread.yield();
            }
            acceptorHandler.session.close(true);
            Assert.assertTrue(
                    acceptorHandler.session.getCloseFuture().awaitUninterruptibly(3000));

            future.getSession().close(true).awaitUninterruptibly();
            
            Assert.assertNotSame(oldSession, acceptorHandler.session);
        } finally {
            acceptor.unbind();
        }
    }

    private class MockHandler extends IoHandlerAdapter {
        public volatile IoSession session;
        public final StringBuffer result = new StringBuffer();

        /**
         * Default constructor
         */
        public MockHandler() {
            super();
        }
        
        @Override
        public void exceptionCaught(IoSession session, Throwable cause)
                throws Exception {
            this.session = session;
            result.append("CA");
        }

        @Override
        public void messageReceived(IoSession session, Object message)
                throws Exception {
            this.session = session;
            result.append("RE");
        }

        @Override
        public void messageSent(IoSession session, Object message)
                throws Exception {
            this.session = session;
            result.append("SE");
        }

        @Override
        public void sessionClosed(IoSession session) throws Exception {
            this.session = session;
            result.append("CL");
        }

        @Override
        public void sessionCreated(IoSession session) throws Exception {
            this.session = session;
            result.append("CR");
        }

        @Override
        public void sessionIdle(IoSession session, IdleStatus status)
                throws Exception {
            this.session = session;
            result.append("ID");
        }

        @Override
        public void sessionOpened(IoSession session) throws Exception {
            this.session = session;
            result.append("OP");
        }
    }
}
