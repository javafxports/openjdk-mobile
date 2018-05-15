#!/bin/sh
#
# Copyright (c) 2007, 2018, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

. $TESTSRC/../share/common.sh

JAVA_OPTS="${JAVA_OPTS} -XX:-UseGCOverheadLimit"

DUMPPATH=${DUMPBASE}/dumps

rm -rf ${DUMPPATH}

mkdir -p ${DUMPPATH}

JAVA_OPTS="${JAVA_OPTS} -Xmx`get_max_heap_size $JAVA_OPTS`"

${JAVA} ${JAVA_OPTS} -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${DUMPPATH} heapdump.share.EatMemory

status=$?

if [ $status -ne 0 ]; then
        fail "Java command exited with exit status $status"
fi

DUMPFILE=`ls ${DUMPPATH}/*`

if [ ! -f "${DUMPFILE}" ]; then
        fail "Dump file was not created: $DUMPPATH/\*"
fi

verify_heapdump ${DUMPFILE}

if [ $? -ne 0 ]; then
        fail "Verification of heap dump failed"
fi

pass
