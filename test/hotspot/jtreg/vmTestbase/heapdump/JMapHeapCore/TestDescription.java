/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
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
 *
 * @summary converted from VM testbase heapdump/JMapHeapCore.
 * VM testbase keywords: [heapdump, feature_heapdump, nonconcurrent.jdk, quick, quarantine]
 * VM testbase comments: JDK-8023376 JDK-8001227 JDK-8051445
 * VM testbase readme:
 * DESCRIPTION
 *     This test verifies that heap dump created by jhsdb is able to be
 *     parsed by HprofParser. It fills the heap with objects of different types
 *     till OutOfMemoryError, forces core dump, then uses jhsdb on core file
 *     to create heap dump and then verifies created heap dump with HprofParser.
 *
 * @library /vmTestbase
 *          /test/lib
 * @run driver jdk.test.lib.FileInstaller . .
 * @build jdk.test.lib.hprof.HprofParser
 *        heapdump.share.EatMemory
 * @run shell/timeout=300 run.sh
 */

