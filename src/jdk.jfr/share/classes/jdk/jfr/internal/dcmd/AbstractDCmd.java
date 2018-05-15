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
package jdk.jfr.internal.dcmd;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.internal.SecuritySupport;
import jdk.jfr.internal.SecuritySupport.SafePath;
import jdk.jfr.internal.Utils;

/**
 * Base class for JFR diagnostic commands
 *
 */
abstract class AbstractDCmd {

    private final StringWriter result;
    private final PrintWriter log;

    protected AbstractDCmd() {
        result = new StringWriter();
        log = new PrintWriter(result);
    }

    protected final FlightRecorder getFlightRecorder() {
        return FlightRecorder.getFlightRecorder();
    }

    public final String getResult() {
        return result.toString();
    }

    protected final SafePath resolvePath(String path, String errorMsg) throws DCmdException {
        if (path == null) {
            return null;
        }
        try {
            return new SafePath(path);
        } catch (InvalidPathException e) {
            throw new DCmdException(e, errorMsg, ", invalid path \"" + path + "\".");
        }
    }

    protected final Recording findRecording(String name) throws DCmdException {
        try {
            return findRecordingById(Integer.parseInt(name));
        } catch (NumberFormatException nfe) {
            // User specified a name, not an id.
            return findRecordingByName(name);
        }
    }

    protected final void reportOperationComplete(String actionPrefix, Recording r, SafePath file) {
        print(actionPrefix);
        print(" recording ");
        print("\"" + r.getName() + "\"");
        if (file != null) {
            print(",");
            try {
                print(" ");
                long bytes = SecuritySupport.getFileSize(file);
                printBytes(bytes, " ");
            } catch (IOException e) {
                // Ignore, not essential
            }
            println(" written to:");
            println();
            printPath(file);
        } else {
            println(".");
        }
    }

    protected final List<Recording> getRecordings() {
        List<Recording> list = new ArrayList<>(getFlightRecorder().getRecordings());
        Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    static String quoteIfNeeded(String text) {
        if (text.contains(" ")) {
            return "\\\"" + text + "\\\"";
        } else {
            return text;
        }
    }

    protected final void println() {
        log.println();
    }

    protected final void print(String s) {
        log.print(s);
    }

    protected final void print(String s, Object... args) {
        log.printf(s, args);
    }

    protected final void println(String s, Object... args) {
        print(s, args);
        println();
    }

    protected final void printBytes(long bytes, String separation) {
       print(Utils.formatBytes(bytes, separation));
    }

    protected final void printTimespan(Duration timespan, String separator) {
        print(Utils.formatTimespan(timespan, separator));
    }

    protected final void printPath(SafePath path) {
        if (path == null) {
            print("N/A");
            return;
        }
        try {
            printPath(SecuritySupport.getAbsolutePath(path).toPath());
        } catch (IOException ioe) {
            printPath(path.toPath());
        }
    }

    protected final void printPath(Path path) {
        try {
            println(path.toAbsolutePath().toString());
        } catch (SecurityException e) {
            // fall back on filename
            println(path.toString());
        }
    }

    private Recording findRecordingById(int id) throws DCmdException {
        for (Recording r : getFlightRecorder().getRecordings()) {
            if (r.getId() == id) {
                return r;
            }
        }
        throw new DCmdException("Could not find %d.\n\nUse JFR.check without options to see list of all available recordings.", id);
    }

    private Recording findRecordingByName(String name) throws DCmdException {
        for (Recording recording : getFlightRecorder().getRecordings()) {
            if (name.equals(recording.getName())) {
                return recording;
            }
        }
        throw new DCmdException("Could not find %s.\n\nUse JFR.check without options to see list of all available recordings.", name);
    }
}
