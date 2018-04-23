/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef SHARE_GC_SHARED_COMMANDLINEFLAGCONSTRAINTSGC_HPP
#define SHARE_GC_SHARED_COMMANDLINEFLAGCONSTRAINTSGC_HPP

#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"
#if INCLUDE_ALL_GCS
#include "gc/cms/commandLineFlagConstraintsCMS.hpp"
#include "gc/g1/commandLineFlagConstraintsG1.hpp"
#include "gc/parallel/commandLineFlagConstraintsParallel.hpp"
#endif

/*
 * Here we have GC arguments constraints functions, which are called automatically
 * whenever flag's value changes. If the constraint fails the function should return
 * an appropriate error value.
 */

Flag::Error ParallelGCThreadsConstraintFunc(uint value, bool verbose);
Flag::Error ConcGCThreadsConstraintFunc(uint value, bool verbose);
Flag::Error YoungPLABSizeConstraintFunc(size_t value, bool verbose);
Flag::Error OldPLABSizeConstraintFunc(size_t value, bool verbose);
Flag::Error MinHeapFreeRatioConstraintFunc(uintx value, bool verbose);
Flag::Error MaxHeapFreeRatioConstraintFunc(uintx value, bool verbose);
Flag::Error SoftRefLRUPolicyMSPerMBConstraintFunc(intx value, bool verbose);
Flag::Error MarkStackSizeConstraintFunc(size_t value, bool verbose);
Flag::Error MinMetaspaceFreeRatioConstraintFunc(uintx value, bool verbose);
Flag::Error MaxMetaspaceFreeRatioConstraintFunc(uintx value, bool verbose);
Flag::Error InitialTenuringThresholdConstraintFunc(uintx value, bool verbose);
Flag::Error MaxTenuringThresholdConstraintFunc(uintx value, bool verbose);

Flag::Error MaxGCPauseMillisConstraintFunc(uintx value, bool verbose);
Flag::Error GCPauseIntervalMillisConstraintFunc(uintx value, bool verbose);
Flag::Error InitialBootClassLoaderMetaspaceSizeConstraintFunc(size_t value, bool verbose);
Flag::Error InitialHeapSizeConstraintFunc(size_t value, bool verbose);
Flag::Error MaxHeapSizeConstraintFunc(size_t value, bool verbose);
Flag::Error HeapBaseMinAddressConstraintFunc(size_t value, bool verbose);
Flag::Error NewSizeConstraintFunc(size_t value, bool verbose);
Flag::Error MinTLABSizeConstraintFunc(size_t value, bool verbose);
Flag::Error TLABSizeConstraintFunc(size_t value, bool verbose);
Flag::Error TLABWasteIncrementConstraintFunc(uintx value, bool verbose);
Flag::Error SurvivorRatioConstraintFunc(uintx value, bool verbose);
Flag::Error MetaspaceSizeConstraintFunc(size_t value, bool verbose);
Flag::Error MaxMetaspaceSizeConstraintFunc(size_t value, bool verbose);
Flag::Error SurvivorAlignmentInBytesConstraintFunc(intx value, bool verbose);

// Internal
Flag::Error MaxPLABSizeBounds(const char* name, size_t value, bool verbose);

#endif // SHARE_GC_SHARED_COMMANDLINEFLAGCONSTRAINTSGC_HPP
