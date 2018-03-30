/*
 * Copyright (c) 2012, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static sun.nio.ch.SolarisEventPort.PORT_SOURCE_FD;
import static sun.nio.ch.SolarisEventPort.PORT_SOURCE_USER;
import static sun.nio.ch.SolarisEventPort.SIZEOF_PORT_EVENT;
import static sun.nio.ch.SolarisEventPort.OFFSETOF_EVENTS;
import static sun.nio.ch.SolarisEventPort.OFFSETOF_SOURCE;
import static sun.nio.ch.SolarisEventPort.OFFSETOF_OBJECT;
import static sun.nio.ch.SolarisEventPort.port_create;
import static sun.nio.ch.SolarisEventPort.port_close;
import static sun.nio.ch.SolarisEventPort.port_associate;
import static sun.nio.ch.SolarisEventPort.port_dissociate;
import static sun.nio.ch.SolarisEventPort.port_getn;
import static sun.nio.ch.SolarisEventPort.port_send;

/**
 * Selector implementation based on the Solaris event port mechanism.
 */

class EventPortSelectorImpl
    extends SelectorImpl
{
    // maximum number of events to retrive in one call to port_getn
    static final int MAX_EVENTS = Math.min(IOUtil.fdLimit()-1, 1024);

    // port file descriptor
    private final int pfd;

    // the poll array (populated by port_getn)
    private final long pollArrayAddress;
    private final AllocatedNativeObject pollArray;

    // maps file descriptor to selection key, synchronize on selector
    private final Map<Integer, SelectionKeyImpl> fdToKey = new HashMap<>();

    // the last update operation, incremented by processUpdateQueue
    private int lastUpdate;

    // pending new registrations/updates, queued by implRegister, putEventOps,
    // and updateSelectedKeys
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> newKeys = new ArrayDeque<>();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();
    private final Deque<Integer> updateEvents = new ArrayDeque<>();

    // interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered;

    EventPortSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);

        this.pfd = port_create();

        int allocationSize = MAX_EVENTS * SIZEOF_PORT_EVENT;
        this.pollArray = new AllocatedNativeObject(allocationSize, false);
        this.pollArrayAddress = pollArray.address();
    }

    private void ensureOpen() {
        if (!isOpen())
            throw new ClosedSelectorException();
    }

    @Override
    protected int doSelect(long timeout) throws IOException {
        assert Thread.holdsLock(this);

        long to = timeout;
        boolean blocking = (to != 0);
        boolean timedPoll = (to > 0);

        int numEvents;
        processUpdateQueue();
        processDeregisterQueue();
        try {
            begin(blocking);

            do {
                long startTime = timedPoll ? System.nanoTime() : 0;
                numEvents = port_getn(pfd, pollArrayAddress, MAX_EVENTS, to);
                if (numEvents == IOStatus.INTERRUPTED && timedPoll) {
                    // timed poll interrupted so need to adjust timeout
                    long adjust = System.nanoTime() - startTime;
                    to -= TimeUnit.MILLISECONDS.convert(adjust, TimeUnit.NANOSECONDS);
                    if (to <= 0) {
                        // timeout also expired so no retry
                        numEvents = 0;
                    }
                }
            } while (numEvents == IOStatus.INTERRUPTED);
            assert IOStatus.check(numEvents);

        } finally {
            end(blocking);
        }
        processDeregisterQueue();
        return processPortEvents(numEvents);
    }

    /**
     * Process new registrations and changes to the interest ops.
     */
    private void processUpdateQueue() throws IOException {
        assert Thread.holdsLock(this);

        // bump lastUpdate to ensure that the interest ops are changed at most
        // once per bulk update
        lastUpdate++;

        synchronized (updateLock) {
            SelectionKeyImpl ski;

            // new registrations
            while ((ski = newKeys.pollFirst()) != null) {
                if (ski.isValid()) {
                    int fd = ski.channel.getFDVal();
                    SelectionKeyImpl previous = fdToKey.put(fd, ski);
                    assert previous == null;
                    assert ski.registeredEvents() == 0;
                }
            }

            // changes to interest ops
            assert updateKeys.size() == updateEvents.size();
            while ((ski = updateKeys.pollFirst()) != null) {
                int newEvents = updateEvents.pollFirst();
                int fd = ski.channel.getFDVal();
                if (ski.isValid() && fdToKey.containsKey(fd)) {
                    if (newEvents != ski.registeredEvents()) {
                        if (newEvents == 0) {
                            port_dissociate(pfd, PORT_SOURCE_FD, fd);
                        } else {
                            port_associate(pfd, PORT_SOURCE_FD, fd, newEvents);
                        }
                        ski.registeredEvents(newEvents);
                    }
                }
            }
        }
    }

    /**
     * Process the port events. This method updates the keys of file descriptors
     * that were polled. It also re-queues the key so that the file descriptor
     * is re-associated at the next select operation.
     *
     * @return the number of selection keys updated.
     */
    private int processPortEvents(int numEvents) throws IOException {
        assert Thread.holdsLock(this);
        assert Thread.holdsLock(nioSelectedKeys());

        int numKeysUpdated = 0;
        boolean interrupted = false;

        synchronized (updateLock) {
            for (int i = 0; i < numEvents; i++) {
                short source = getSource(i);
                if (source == PORT_SOURCE_FD) {
                    int fd = getDescriptor(i);
                    SelectionKeyImpl ski = fdToKey.get(fd);
                    if (ski != null) {
                        int rOps = getEventOps(i);
                        if (selectedKeys.contains(ski)) {
                            if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                                numKeysUpdated++;
                            }
                        } else {
                            ski.channel.translateAndSetReadyOps(rOps, ski);
                            if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                                selectedKeys.add(ski);
                                numKeysUpdated++;
                            }
                        }

                        // re-queue key to head so that it is re-associated at
                        // next select (and before other changes)
                        updateEvents.addFirst(ski.registeredEvents());
                        updateKeys.addFirst(ski);
                        ski.registeredEvents(0);
                    }
                } else if (source == PORT_SOURCE_USER) {
                    interrupted = true;
                } else {
                    assert false;
                }
            }
        }

        if (interrupted) {
            clearInterrupt();
        }
        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        assert !isOpen();
        assert Thread.holdsLock(this);

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        port_close(pfd);
        pollArray.free();
    }

    @Override
    protected void implRegister(SelectionKeyImpl ski) {
        ensureOpen();
        synchronized (updateLock) {
            newKeys.addLast(ski);
        }
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid();
        assert Thread.holdsLock(this);

        int fd = ski.channel.getFDVal();
        if (fdToKey.remove(fd) != null) {
            if (ski.registeredEvents() != 0) {
                port_dissociate(pfd, PORT_SOURCE_FD, fd);
                ski.registeredEvents(0);
            }
        } else {
            assert ski.registeredEvents() == 0;
        }
    }

    @Override
    public void putEventOps(SelectionKeyImpl ski, int events) {
        ensureOpen();
        synchronized (updateLock) {
            updateEvents.addLast(events);  // events first in case adding key fails
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    port_send(pfd, 0);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                interruptTriggered = true;
            }
        }
        return this;
    }

    private void clearInterrupt() throws IOException {
        synchronized (interruptLock) {
            interruptTriggered = false;
        }
    }

    private short getSource(int i) {
        int offset = SIZEOF_PORT_EVENT * i + OFFSETOF_SOURCE;
        return pollArray.getShort(offset);
    }

    private int getEventOps(int i) {
        int offset = SIZEOF_PORT_EVENT * i + OFFSETOF_EVENTS;
        return pollArray.getInt(offset);
    }

    private int getDescriptor(int i) {
        //assert Unsafe.getUnsafe().addressSize() == 8;
        int offset = SIZEOF_PORT_EVENT * i + OFFSETOF_OBJECT;
        return (int) pollArray.getLong(offset);
    }
}
