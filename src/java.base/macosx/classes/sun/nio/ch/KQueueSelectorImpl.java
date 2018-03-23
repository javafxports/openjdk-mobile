/*
 * Copyright (c) 2011, 2018, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static sun.nio.ch.KQueue.EVFILT_READ;
import static sun.nio.ch.KQueue.EVFILT_WRITE;
import static sun.nio.ch.KQueue.EV_ADD;
import static sun.nio.ch.KQueue.EV_DELETE;

/**
 * KQueue based Selector implementation for macOS
 */

class KQueueSelectorImpl extends SelectorImpl {

    // maximum number of events to poll in one call to kqueue
    private static final int MAX_KEVENTS = 256;

    // kqueue file descriptor
    private final int kqfd;

    // address of poll array (event list) when polling for pending events
    private final long pollArrayAddress;

    // file descriptors used for interrupt
    private final int fd0;
    private final int fd1;

    // maps file descriptor to selection key, synchronize on selector
    private final Map<Integer, SelectionKeyImpl> fdToKey = new HashMap<>();

    // file descriptors registered with kqueue, synchronize on selector
    private final BitSet registeredReadFilter = new BitSet();
    private final BitSet registeredWriteFilter = new BitSet();

    // pending new registrations/updates, queued by implRegister and putEventOps
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> newKeys = new ArrayDeque<>();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();
    private final Deque<Integer> updateOps = new ArrayDeque<>();

    // interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered;

    // used by updateSelectedKeys to handle cases where the same file
    // descriptor is polled by more than one filter
    private int pollCount;

    KQueueSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);

        this.kqfd = KQueue.create();
        this.pollArrayAddress = KQueue.allocatePollArray(MAX_KEVENTS);

        try {
            long fds = IOUtil.makePipe(false);
            this.fd0 = (int) (fds >>> 32);
            this.fd1 = (int) fds;
        } catch (IOException ioe) {
            KQueue.freePollArray(pollArrayAddress);
            FileDispatcherImpl.closeIntFD(kqfd);
            throw ioe;
        }

        // register one end of the socket pair for wakeups
        KQueue.register(kqfd, fd0, EVFILT_READ, EV_ADD);
    }

    private void ensureOpen() {
        if (!isOpen())
            throw new ClosedSelectorException();
    }

    @Override
    protected int doSelect(long timeout) throws IOException {
        assert Thread.holdsLock(this);

        int numEntries;
        processUpdateQueue();
        processDeregisterQueue();
        try {
            begin();

            long to = Math.min(timeout, Integer.MAX_VALUE);  // max kqueue timeout
            boolean timedPoll = (to > 0);
            do {
                long startTime = timedPoll ? System.nanoTime() : 0;
                numEntries = KQueue.poll(kqfd, pollArrayAddress, MAX_KEVENTS, to);
                if (numEntries == IOStatus.INTERRUPTED && timedPoll) {
                    // timed poll interrupted so need to adjust timeout
                    long adjust = System.nanoTime() - startTime;
                    to -= TimeUnit.MILLISECONDS.convert(adjust, TimeUnit.NANOSECONDS);
                    if (to <= 0) {
                        // timeout expired so no retry
                        numEntries = 0;
                    }
                }
            } while (numEntries == IOStatus.INTERRUPTED);
            assert IOStatus.check(numEntries);

        } finally {
            end();
        }
        processDeregisterQueue();
        return updateSelectedKeys(numEntries);
    }

    /**
     * Process new registrations and changes to the interest ops.
     */
    private void processUpdateQueue() {
        assert Thread.holdsLock(this);

        synchronized (updateLock) {
            SelectionKeyImpl ski;

            // new registrations
            while ((ski = newKeys.pollFirst()) != null) {
                if (ski.isValid()) {
                    SelChImpl ch = ski.channel;
                    int fd = ch.getFDVal();
                    SelectionKeyImpl previous = fdToKey.put(fd, ski);
                    assert previous == null;
                    assert registeredReadFilter.get(fd) == false;
                    assert registeredWriteFilter.get(fd) == false;
                }
            }

            // changes to interest ops
            assert updateKeys.size() == updateOps.size();
            while ((ski = updateKeys.pollFirst()) != null) {
                int ops = updateOps.pollFirst();
                int fd = ski.channel.getFDVal();
                if (ski.isValid() && fdToKey.containsKey(fd)) {
                    // add or delete interest in read events
                    if (registeredReadFilter.get(fd)) {
                        if ((ops & Net.POLLIN) == 0) {
                            KQueue.register(kqfd, fd, EVFILT_READ, EV_DELETE);
                            registeredReadFilter.clear(fd);
                        }
                    } else if ((ops & Net.POLLIN) != 0) {
                        KQueue.register(kqfd, fd, EVFILT_READ, EV_ADD);
                        registeredReadFilter.set(fd);
                    }

                    // add or delete interest in write events
                    if (registeredWriteFilter.get(fd)) {
                        if ((ops & Net.POLLOUT) == 0) {
                            KQueue.register(kqfd, fd, EVFILT_WRITE, EV_DELETE);
                            registeredWriteFilter.clear(fd);
                        }
                    } else if ((ops & Net.POLLOUT) != 0) {
                        KQueue.register(kqfd, fd, EVFILT_WRITE, EV_ADD);
                        registeredWriteFilter.set(fd);
                    }
                }
            }
        }
    }

    /**
     * Update the keys whose fd's have been selected by kqueue.
     * Add the ready keys to the selected key set.
     * If the interrupt fd has been selected, drain it and clear the interrupt.
     */
    private int updateSelectedKeys(int numEntries) throws IOException {
        assert Thread.holdsLock(this);
        assert Thread.holdsLock(nioSelectedKeys());

        int numKeysUpdated = 0;
        boolean interrupted = false;

        // A file descriptor may be registered with kqueue with more than one
        // filter and so there may be more than one event for a fd. The poll
        // count is incremented here and compared against the SelectionKey's
        // "lastPolled" field. This ensures that the ready ops is updated rather
        // than replaced when a file descriptor is polled by both the read and
        // write filter.
        pollCount++;

        for (int i = 0; i < numEntries; i++) {
            long kevent = KQueue.getEvent(pollArrayAddress, i);
            int fd = KQueue.getDescriptor(kevent);
            if (fd == fd0) {
                interrupted = true;
            } else {
                SelectionKeyImpl ski = fdToKey.get(fd);
                if (ski != null) {
                    int rOps = 0;
                    short filter = KQueue.getFilter(kevent);
                    if (filter == EVFILT_READ) {
                        rOps |= Net.POLLIN;
                    } else if (filter == EVFILT_WRITE) {
                        rOps |= Net.POLLOUT;
                    }

                    if (selectedKeys.contains(ski)) {
                        // file descriptor may be polled more than once per poll
                        if (ski.lastPolled != pollCount) {
                            if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                                numKeysUpdated++;
                                ski.lastPolled = pollCount;
                            }
                        } else {
                            // ready ops have already been set on this update
                            ski.channel.translateAndUpdateReadyOps(rOps, ski);
                        }
                    } else {
                        ski.channel.translateAndSetReadyOps(rOps, ski);
                        if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                            selectedKeys.add(ski);
                            numKeysUpdated++;
                            ski.lastPolled = pollCount;
                        }
                    }
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
        assert Thread.holdsLock(nioKeys());

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        FileDispatcherImpl.closeIntFD(kqfd);
        KQueue.freePollArray(pollArrayAddress);

        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);

        // Deregister channels
        Iterator<SelectionKey> i = keys.iterator();
        while (i.hasNext()) {
            SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
            deregister(ski);
            SelectableChannel selch = ski.channel();
            if (!selch.isOpen() && !selch.isRegistered())
                ((SelChImpl)selch).kill();
            i.remove();
        }
    }

    @Override
    protected void implRegister(SelectionKeyImpl ski) {
        assert Thread.holdsLock(nioKeys());
        ensureOpen();
        synchronized (updateLock) {
            newKeys.addLast(ski);
        }
        keys.add(ski);
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid();
        assert Thread.holdsLock(this);
        assert Thread.holdsLock(nioKeys());
        assert Thread.holdsLock(nioSelectedKeys());

        int fd = ski.channel.getFDVal();
        fdToKey.remove(fd);
        if (registeredReadFilter.get(fd)) {
            KQueue.register(kqfd, fd, EVFILT_READ, EV_DELETE);
            registeredReadFilter.clear(fd);
        }
        if (registeredWriteFilter.get(fd)) {
            KQueue.register(kqfd, fd, EVFILT_WRITE, EV_DELETE);
            registeredWriteFilter.clear(fd);
        }

        selectedKeys.remove(ski);
        keys.remove(ski);

        // remove from channel's key set
        deregister(ski);

        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl) selch).kill();
    }

    @Override
    public void putEventOps(SelectionKeyImpl ski, int ops) {
        ensureOpen();
        synchronized (updateLock) {
            updateOps.addLast(ops);   // ops first in case adding the key fails
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    IOUtil.write1(fd1, (byte)0);
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
            IOUtil.drain(fd0);
            interruptTriggered = false;
        }
    }
}
