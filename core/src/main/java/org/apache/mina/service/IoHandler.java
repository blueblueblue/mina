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
package org.apache.mina.service;

import java.io.IOException;

import org.apache.mina.IdleStatus;
import org.apache.mina.IoSession;

/**
 * Handles all I/O events fired by MINA. This is the Interface an application will implement
 * to manage the incoming messages and the outgoing responses.<br/>
 * An IoHandler handles a set of events :
 * <ul>
 * <li>SessionCreated : fired when a new session has been created. It's not opened yet</li>
 * <li>SessionOpened : fired when a new session is opened (ie a client has just bound)</li>
 * <li>SessionClosed : fired when the session has been closed. No more message can be received or sent</li>
 * <li>SessionIdle : fired when a session has been inactive for a certain amount of time (configurable)</li>
 * <li>MessageReceived : fired when a message has just been received</li>
 * <li>MessageSent : fired when the message has been fully sent to the client. Note that we can't know 
 * if the client has received it, we can just tell that the message has been delivered</li>
 * <li>ExceptionCaught : fired when some exception has been generated during the processing of an 
 * event for the session </li>
 * </ul>
 *
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public interface IoHandler {
    /**
     * Invoked from an I/O processor thread when a new connection has been created.
     * Because this method is supposed to be called from the same thread that
     * handles I/O of multiple sessions, please implement this method to perform
     * tasks that consumes minimal amount of time such as socket parameter
     * and user-defined session attribute initialization.
     */
    void sessionCreated(IoSession session) throws Exception;

    /**
     * Invoked when a connection has been opened.  This method is invoked after
     * {@link #sessionCreated(IoSession)}.  The biggest difference from
     * {@link #sessionCreated(IoSession)} is that it's invoked from other thread
     * than an I/O processor thread once thread model is configured properly.
     */
    void sessionOpened(IoSession session) throws Exception;

    /**
     * Invoked when a connection is closed.
     */
    void sessionClosed(IoSession session) throws Exception;

    /**
     * Invoked with the related {@link IdleStatus} when a connection becomes idle.
     * This method is not invoked if the transport type is UDP; it's a known bug,
     * and will be fixed in 2.0.
     */
    void sessionIdle(IoSession session, IdleStatus status) throws Exception;

    /**
     * Invoked when any exception is thrown by user {@link IoHandler}
     * implementation or by MINA.  If <code>cause</code> is an instance of
     * {@link IOException}, MINA will close the connection automatically.
     */
    void exceptionCaught(IoSession session, Throwable cause) throws Exception;

    /**
     * Invoked when a message is received.
     */
    void messageReceived(IoSession session, Object message) throws Exception;

    /**
     * Invoked when a message written by {@link IoSession#write(Object)} is
     * sent out.
     */
    void messageSent(IoSession session, Object message) throws Exception;
}