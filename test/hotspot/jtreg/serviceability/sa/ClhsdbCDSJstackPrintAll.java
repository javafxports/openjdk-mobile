/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 8174994
 * @summary Test the clhsdb commands 'jstack', 'printall' with CDS enabled
 * @requires vm.cds
 * @library /test/lib
 * @run main/othervm/timeout=2400 -Xmx1g ClhsdbCDSJstackPrintAll
 */

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.cds.CDSOptions;
import jdk.test.lib.apps.LingeredApp;

public class ClhsdbCDSJstackPrintAll {

    public static void main(String[] args) throws Exception {
        System.out.println("Starting ClhsdbCDSJstackPrintAll test");
        String sharedArchiveName = "ArchiveForClhsdbJstackPrintAll.jsa";
        LingeredApp theApp = null;

        try {
            CDSOptions opts = (new CDSOptions()).setArchiveName(sharedArchiveName);
            CDSTestUtils.createArchiveAndCheck(opts);

            ClhsdbLauncher test = new ClhsdbLauncher();
            List<String> vmArgs = Arrays.asList(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:SharedArchiveFile=" + sharedArchiveName,
                "-Xshare:auto");
            theApp = LingeredApp.startApp(vmArgs);
            System.out.println("Started LingeredApp with pid " + theApp.getPid());

            // Ensure that UseSharedSpaces is turned on.
            List<String> cmds = List.of("flags UseSharedSpaces");

            String useSharedSpacesOutput = test.run(theApp.getPid(), cmds,
                                                    null, null);

            if (useSharedSpacesOutput == null) {
                // Attach permission issues.
                System.out.println("Could not determine the UseSharedSpaces value - test skipped.");
                LingeredApp.stopApp(theApp);
                return;
            }

            if (!useSharedSpacesOutput.contains("true")) {
                // CDS archive is not mapped. Skip the rest of the test.
                System.out.println("The CDS archive is not mapped - test skipped.");
                LingeredApp.stopApp(theApp);
                return;
            }

            cmds = List.of("jstack -v", "printall");

            Map<String, List<String>> expStrMap = new HashMap<>();
            Map<String, List<String>> unExpStrMap = new HashMap<>();
            expStrMap.put("jstack -v", List.of(
                "No deadlocks found",
                "Common-Cleaner",
                "Signal Dispatcher",
                "Method*",
                "LingeredApp.main"));
            unExpStrMap.put("jstack -v", List.of(
                "sun.jvm.hotspot.types.WrongTypeException",
                "No suitable match for type of address"));
            expStrMap.put("printall", List.of(
                "aload_0",
                "Constant Pool of",
                "public static void main(java.lang.String[])",
                "Bytecode",
                "invokevirtual",
                "checkcast",
                "Exception Table",
                "invokedynamic"));
            unExpStrMap.put("printall", List.of(
                "No suitable match for type of address"));
            test.run(theApp.getPid(), cmds, expStrMap, unExpStrMap);
        } catch (Exception ex) {
            throw new RuntimeException("Test ERROR " + ex, ex);
        } finally {
            LingeredApp.stopApp(theApp);
        }
        System.out.println("Test PASSED");
    }
}
