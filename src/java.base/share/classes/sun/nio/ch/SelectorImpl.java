/*
 * Copyright (c) 2000, 2018, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;


/**
 * Base Selector implementation class.
 */

public abstract class SelectorImpl
    extends AbstractSelector
{
    // The set of keys registered with this Selector
    protected final Set<SelectionKey> keys;

    // The set of keys with data ready for an operation
    protected final Set<SelectionKey> selectedKeys;

    // Public views of the key sets
    private final Set<SelectionKey> publicKeys;             // Immutable
    private final Set<SelectionKey> publicSelectedKeys;     // Removal allowed, but not addition

    protected SelectorImpl(SelectorProvider sp) {
        super(sp);
        keys = new HashSet<>();
        selectedKeys = new HashSet<>();
        publicKeys = Collections.unmodifiableSet(keys);
        publicSelectedKeys = Util.ungrowableSet(selectedKeys);
    }

    @Override
    public final Set<SelectionKey> keys() {
        if (!isOpen())
            throw new ClosedSelectorException();
        return publicKeys;
    }

    @Override
    public final Set<SelectionKey> selectedKeys() {
        if (!isOpen())
            throw new ClosedSelectorException();
        return publicSelectedKeys;
    }

    /**
     * Returns the public view of the key sets
     */
    protected final Set<SelectionKey> nioKeys() {
        return publicKeys;
    }
    protected final Set<SelectionKey> nioSelectedKeys() {
        return publicSelectedKeys;
    }

    /**
     * Marks the beginning of a select operation that might block
     */
    protected final void begin(boolean blocking) {
        if (blocking) begin();
    }

    /**
     * Marks the end of a select operation that may have blocked
     */
    protected final void end(boolean blocking) {
        if (blocking) end();
    }

    /**
     * Selects the keys for channels that are ready for I/O operations.
     *
     * @param timeout timeout in milliseconds to wait, 0 to not wait, -1 to
     *                wait indefinitely
     */
    protected abstract int doSelect(long timeout) throws IOException;

    private int lockAndDoSelect(long timeout) throws IOException {
        synchronized (this) {
            if (!isOpen())
                throw new ClosedSelectorException();
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    return doSelect(timeout);
                }
            }
        }
    }

    @Override
    public final int select(long timeout)
        throws IOException
    {
        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");
        return lockAndDoSelect((timeout == 0) ? -1 : timeout);
    }

    @Override
    public final int select() throws IOException {
        return select(0);
    }

    @Override
    public final int selectNow() throws IOException {
        return lockAndDoSelect(0);
    }

    @Override
    public final void implCloseSelector() throws IOException {
        wakeup();
        synchronized (this) {
            implClose();
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    // Deregister channels
                    Iterator<SelectionKey> i = keys.iterator();
                    while (i.hasNext()) {
                        SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                        deregister(ski);
                        SelectableChannel selch = ski.channel();
                        if (!selch.isOpen() && !selch.isRegistered())
                            ((SelChImpl)selch).kill();
                        selectedKeys.remove(ski);
                        i.remove();
                    }
                    assert selectedKeys.isEmpty() && keys.isEmpty();
                }
            }
        }
    }

    protected abstract void implClose() throws IOException;

    @Override
    protected final SelectionKey register(AbstractSelectableChannel ch,
                                          int ops,
                                          Object attachment)
    {
        if (!(ch instanceof SelChImpl))
            throw new IllegalSelectorException();
        SelectionKeyImpl k = new SelectionKeyImpl((SelChImpl)ch, this);
        k.attach(attachment);
        // register before adding to key set
        implRegister(k);
        synchronized (publicKeys) {
            keys.add(k);
        }
        k.interestOps(ops);
        return k;
    }

    protected abstract void implRegister(SelectionKeyImpl ski);

    protected abstract void implDereg(SelectionKeyImpl ski) throws IOException;

    protected final void processDeregisterQueue() throws IOException {
        assert Thread.holdsLock(this);
        assert Thread.holdsLock(publicKeys);
        assert Thread.holdsLock(publicSelectedKeys);

        Set<SelectionKey> cks = cancelledKeys();
        synchronized (cks) {
            if (!cks.isEmpty()) {
                Iterator<SelectionKey> i = cks.iterator();
                while (i.hasNext()) {
                    SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                    i.remove();

                    // remove the key from the selector
                    implDereg(ski);

                    selectedKeys.remove(ski);
                    keys.remove(ski);

                    // remove from channel's key set
                    deregister(ski);

                    SelectableChannel ch = ski.channel();
                    if (!ch.isOpen() && !ch.isRegistered())
                        ((SelChImpl)ch).kill();
                }
            }
        }
    }

    /**
     * Change the event set in the selector
     */
    protected abstract void putEventOps(SelectionKeyImpl ski, int events);
}
