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
 *
 */

#include "precompiled.hpp"
#include "gc/shared/barrierSetAssembler.hpp"
#include "interpreter/interp_masm.hpp"
#include "runtime/jniHandles.hpp"

#define __ masm->

void BarrierSetAssembler::load_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                  Register dst, Address src, Register tmp1, Register tmp_thread) {
  bool on_heap = (decorators & IN_HEAP) != 0;
  bool on_root = (decorators & IN_ROOT) != 0;
  bool oop_not_null = (decorators & OOP_NOT_NULL) != 0;

  switch (type) {
  case T_OBJECT:
  case T_ARRAY: {
    if (on_heap) {
#ifdef _LP64
      if (UseCompressedOops) {
        __ movl(dst, src);
        if (oop_not_null) {
          __ decode_heap_oop_not_null(dst);
        } else {
          __ decode_heap_oop(dst);
        }
      } else
#endif
      {
        __ movptr(dst, src);
      }
    } else {
      assert(on_root, "why else?");
      __ movptr(dst, src);
    }
    break;
  }
  default: Unimplemented();
  }
}

void BarrierSetAssembler::store_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                   Address dst, Register val, Register tmp1, Register tmp2) {
  bool on_heap = (decorators & IN_HEAP) != 0;
  bool on_root = (decorators & IN_ROOT) != 0;
  bool oop_not_null = (decorators & OOP_NOT_NULL) != 0;

  switch (type) {
  case T_OBJECT:
  case T_ARRAY: {
    if (on_heap) {
      if (val == noreg) {
        assert(!oop_not_null, "inconsistent access");
#ifdef _LP64
        if (UseCompressedOops) {
          __ movl(dst, (int32_t)NULL_WORD);
        } else {
          __ movslq(dst, (int32_t)NULL_WORD);
        }
#else
        __ movl(dst, (int32_t)NULL_WORD);
#endif
      } else {
#ifdef _LP64
        if (UseCompressedOops) {
          assert(!dst.uses(val), "not enough registers");
          if (oop_not_null) {
            __ encode_heap_oop_not_null(val);
          } else {
            __ encode_heap_oop(val);
          }
          __ movl(dst, val);
        } else
#endif
        {
          __ movptr(dst, val);
        }
      }
    } else {
      assert(on_root, "why else?");
      assert(val != noreg, "not supported");
      __ movptr(dst, val);
    }
    break;
  }
  default: Unimplemented();
  }
}

void BarrierSetAssembler::try_resolve_jobject_in_native(MacroAssembler* masm, Register robj, Register tmp, Label& slowpath) {
  __ clear_jweak_tag(robj);
  __ movptr(robj, Address(robj, 0));
}
