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

package org.apache.mina.transport.tcp;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.mina.IoServer;
import org.apache.mina.IoService;
import org.apache.mina.IoSession;
import org.apache.mina.service.SelectorProcessor;
import org.apache.mina.service.SelectorStrategy;
import org.apache.mina.session.WriteQueue;
import org.apache.mina.session.WriteRequest;
import org.apache.mina.transport.tcp.nio.NioTcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * A {@link SelectorProcessor} for processing NIO based {@link IoSession}.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 * 
 */
public class NioSelectorProcessor implements SelectorProcessor {
    /**
     * A timeout used for the select, as we need to get out to deal with idle
     * sessions
     */
    private static final long SELECT_TIMEOUT = 1000L;

    private SelectorStrategy strategy;

    private Logger log;

    private Map<SocketAddress, ServerSocketChannel> serverSocketChannels = new ConcurrentHashMap<SocketAddress, ServerSocketChannel>();

    private ByteBuffer readBuffer;

    /**
     * new binded server to add to the selector {ServerSocketChannel, IoServer}
     */
    private final Queue<Object[]> serversToAdd = new ConcurrentLinkedQueue<Object[]>();

    /** server to remove of the selector */
    private final Queue<ServerSocketChannel> serversToRemove = new ConcurrentLinkedQueue<ServerSocketChannel>();

    /**
     * new session freshly accepted, placed here for being added to the selector
     */
    private final Queue<NioTcpSession> sessionsToConnect = new ConcurrentLinkedQueue<NioTcpSession>();

    /** session to be removed of the selector */
    private final Queue<NioTcpSession> sessionsToClose = new ConcurrentLinkedQueue<NioTcpSession>();

    /** A queue used to store the sessions to be flushed */
    private final Queue<NioTcpSession> flushingSessions = new ConcurrentLinkedQueue<NioTcpSession>();

    private Selector selector;

    public NioSelectorProcessor(String name, SelectorStrategy strategy) {
        this.strategy = strategy;
        this.log = LoggerFactory.getLogger("SelectorProcessor[" + name + "]");

        // TODO : configurable parameter
        readBuffer = ByteBuffer.allocate(1024);
    }

    /**
     * Add a bound server channel for starting accepting new client connections.
     * 
     * @param serverChannel
     */
    private void add(ServerSocketChannel serverChannel, IoServer server) {
        log.debug("adding a server channel {} for server {}", serverChannel, server);
        serversToAdd.add(new Object[] { serverChannel, server });
        wakeupWorker();
    }

    private Object workerLock = new Object();

    private SelectorWorker worker = null;

    private void wakeupWorker() {
        synchronized (workerLock) {
            if (worker == null) {
                worker = new SelectorWorker();
                worker.start();
            }
        }
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public void bindAndAcceptAddress(IoServer server, SocketAddress address) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();

        serverSocketChannel.socket().bind(address);
        serverSocketChannel.configureBlocking(false);
        serverSocketChannels.put(address, serverSocketChannel);
        add(serverSocketChannel, server);
    }

    @Override
    public void unbind(SocketAddress address) throws IOException {
        ServerSocketChannel channel = serverSocketChannels.get(address);
        channel.socket().close();
        channel.close();
        serverSocketChannels.remove(channel);
        log.debug("removing a server channel " + channel);
        serversToRemove.add(channel);
        wakeupWorker();
    }

    @Override
    public void createSession(IoService service, Object clientSocket) {
        log.debug("create session");
        SocketChannel socketChannel = (SocketChannel) clientSocket;
        NioTcpSession session = new NioTcpSession((NioTcpServer) service, socketChannel,
                strategy.getSelectorForNewSession(this));

        // TODO : configure
        try {
            socketChannel.configureBlocking(false);
        } catch (IOException e) {
            log.error("Unexpected exception, while configuring socket as non blocking", e);
        }

        // TODO : event session created

        // add the session to the queue for being added to the selector
        sessionsToConnect.add(session);
        wakeupWorker();
    }

    /**
     * The worker processing incoming session creation and destruction requests.
     * It will also bind new servers.
     */
    private class SelectorWorker extends Thread {
        // map for finding the keys associated with a given server
        private Map<ServerSocketChannel, SelectionKey> serverKey = new HashMap<ServerSocketChannel, SelectionKey>();

        // map for finding read keys associated with a given session
        private Map<NioTcpSession, SelectionKey> sessionReadKey = new HashMap<NioTcpSession, SelectionKey>();

        @Override
        public void run() {
            if (selector == null) {
                log.debug("opening a new selector");

                try {
                    selector = Selector.open();
                } catch (IOException e) {
                    log.error("IOException while opening a new Selector", e);
                }
            }

            for (;;) {
                try {
                    // pop server sockets for removing
                    if (serversToRemove.size() > 0) {
                        while (!serversToRemove.isEmpty()) {
                            ServerSocketChannel channel = serversToRemove.poll();
                            SelectionKey key = serverKey.remove(channel);

                            if (key == null) {
                                log.error("The server socket was already removed of the selector");
                            } else {
                                key.cancel();
                            }
                        }
                    }

                    // pop new server sockets for accepting
                    if (serversToAdd.size() > 0) {
                        while (!serversToAdd.isEmpty()) {
                            Object[] tmp = serversToAdd.poll();
                            ServerSocketChannel channel = (ServerSocketChannel) tmp[0];
                            SelectionKey key = channel.register(selector, SelectionKey.OP_ACCEPT);
                            key.attach(tmp);
                        }
                    }

                    // pop new session for starting read/write
                    if (sessionsToConnect.size() > 0) {
                        while (!sessionsToConnect.isEmpty()) {
                            NioTcpSession session = sessionsToConnect.poll();
                            SelectionKey key = session.getSocketChannel().register(selector, SelectionKey.OP_READ);
                            key.attach(session);
                            sessionReadKey.put(session, key);
                            session.setConnected();
                        }
                    }

                    // pop session for close
                    if (sessionsToClose.size() > 0) {
                        while (!sessionsToClose.isEmpty()) {
                            NioTcpSession session = sessionsToClose.poll();

                            SelectionKey key = sessionReadKey.remove(session);
                            key.cancel();

                            // needed ?
                            session.getSocketChannel().close();
                        }
                    }

                    log.debug("selecting...");
                    int readyCount = selector.select(SELECT_TIMEOUT);
                    log.debug("... done selecting : {}", readyCount);

                    if (readyCount > 0) {

                        // process selected keys
                        Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();

                        while (selectedKeys.hasNext()) {
                            SelectionKey key = selectedKeys.next();
                            selectedKeys.remove();

                            if (!key.isValid()) {
                                continue;
                            }
                            selector.selectedKeys().remove(key);

                            if (key.isReadable()) {
                                log.debug("readable client {}", key);
                                NioTcpSession session = (NioTcpSession) key.attachment();
                                SocketChannel channel = session.getSocketChannel();
                                int readCount = channel.read(readBuffer);
                                log.debug("read {} bytes", readCount);
                                if (readCount < 0) {
                                    // session closed by the remote peer
                                    log.debug("session closed by the remote peer");
                                    sessionsToClose.add(session);
                                } else {
                                    // we have read some data
                                    // TODO : push to the chain

                                    readBuffer.rewind();
                                }

                            }
                            if (key.isWritable()) {
                                log.debug("writable client {}", key);
                                NioTcpSession session = (NioTcpSession) key.attachment();
                                // write from the session write queue
                                WriteQueue queue = session.getWriteQueue();
                                do {
                                    // get a write request from the queue
                                    WriteRequest wreq = queue.peek();
                                    if (wreq == null) {
                                        break;
                                    }
                                    ByteBuffer buf = (ByteBuffer) wreq.getMessage();
                                    int wrote = session.getSocketChannel().write(buf);
                                    if (buf.remaining() == 0) {
                                        // completed write request, let's remove
                                        // it
                                        queue.remove();
                                    } else {
                                        // output socket buffer is full, we need
                                        // to give up until next selection for
                                        // writing
                                        break;
                                    }

                                } while (!queue.isEmpty());

                            }

                            if (key.isAcceptable()) {
                                log.debug("acceptable new client {}", key);
                                ServerSocketChannel serverSocket = (ServerSocketChannel) ((Object[]) key.attachment())[0];
                                IoServer server = (IoServer) (((Object[]) key.attachment())[1]);
                                // accepted connection
                                SocketChannel newClientChannel = serverSocket.accept();
                                log.debug("client accepted");
                                // and give it's to the strategy
                                strategy.getSelectorForNewSession(NioSelectorProcessor.this).createSession(server,
                                        newClientChannel);
                            }

                        }
                    }

                    // registering session with data in the write queue for
                    // writing
                    while (!flushingSessions.isEmpty()) {
                        NioTcpSession session = flushingSessions.poll();
                        // a key registered for read ? (because we can have a
                        // Selector for reads and another for the writes
                        SelectionKey readKey = sessionReadKey.get(session);
                        if (readKey != null) {
                            readKey.cancel();
                            // register for read/write
                            SelectionKey key = session.getSocketChannel().register(selector,
                                    SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                            key.attach(session);
                            sessionReadKey.put(session, key);

                        } else {
                            SelectionKey key = session.getSocketChannel().register(selector, SelectionKey.OP_WRITE);
                            key.attach(session);
                        }
                    }
                } catch (IOException e) {
                    log.error("IOException while selecting selector", e);
                }

                // stop the worker if needed
                synchronized (workerLock) {
                    if (selector.keys().isEmpty()) {
                        worker = null;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void flush(IoSession session) {
        log.debug("scheduling session {} for writing", session.toString());
        // add the session to the list of session to be registered for writing
        // wake the selector
        flushingSessions.add((NioTcpSession) session);
        wakeupWorker();
    }
}
