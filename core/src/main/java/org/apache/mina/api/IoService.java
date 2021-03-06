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
package org.apache.mina.api;

import java.util.Map;

/**
 * Base interface for all {@link IoServer}s and {@link IoClient}s that provide I/O service 
 * and manage {@link IoSession}s.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public interface IoService {
    /**
     * Returns the map of all sessions which are currently managed by this service. The key of map is the
     * {@link IoSession#getId() ID} of the session.
     * 
     * @return the sessions. An empty collection if there's no session.
     */
    Map<Long, IoSession> getManagedSessions();

    /**
     * Adds some {@link IoServiceListener} that listens any events related with this service.
     * 
     * @param listeners The {@link IoServiceListener} to add
     */
    void addListeners(IoServiceListener... listeners);

    /**
     * Removed some existing {@link IoServiceListener} that listens any events related with this service.
     * 
     * @param listeners The {@link IoServiceListener} to rmove
     */
    void removeListeners(IoServiceListener... listeners);

    /**
     * Get the list of filters installed on this service
     * 
     * @return The list of installed filters
     */
    IoFilter[] getFilters();

    /**
     * Set the list of filters for this service. Must be called before the service is bound/connected
     * 
     * @param The list of filters to inject in the filters chain
     */
    void setFilters(IoFilter... filters);

    /**
     * Returns the default configuration of the new {@link IoSession}s
     * created by this service.
     * 
     * @return The default configuration for this {@link IoService}
     */
    IoSessionConfig getSessionConfig();
}
