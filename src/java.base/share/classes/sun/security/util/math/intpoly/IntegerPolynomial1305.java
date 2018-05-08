/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package sun.security.util.math.intpoly;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.*;

/**
 * An IntegerFieldModuloP designed for use with the Poly1305 authenticator.
 * The representation uses 5 signed long values.
 *
 * In addition to the branch-free operations specified in the parent class,
 * the following operations are branch-free:
 *
 * addModPowerTwo
 * asByteArray
 *
 */

public class IntegerPolynomial1305 extends IntegerPolynomial {

    protected static final int SUBTRAHEND = 5;
    protected static final int NUM_LIMBS = 5;
    private static final int POWER = 130;
    private static final int BITS_PER_LIMB = 26;
    private static final BigInteger MODULUS
        = TWO.pow(POWER).subtract(BigInteger.valueOf(SUBTRAHEND));

    private final long[] posModLimbs;

    private long[] setPosModLimbs() {
        long[] result = new long[NUM_LIMBS];
        setLimbsValuePositive(MODULUS, result);
        return result;
    }

    public IntegerPolynomial1305() {
        super(BITS_PER_LIMB, NUM_LIMBS, MODULUS);
        posModLimbs = setPosModLimbs();
    }

    protected void mult(long[] a, long[] b, long[] r) {

        // Use grade-school multiplication into primitives to avoid the
        // temporary array allocation. This is equivalent to the following
        // code:
        //  long[] c = new long[2 * NUM_LIMBS - 1];
        //  for(int i = 0; i < NUM_LIMBS; i++) {
        //      for(int j - 0; j < NUM_LIMBS; j++) {
        //          c[i + j] += a[i] * b[j]
        //      }
        //  }

        long c0 = (a[0] * b[0]);
        long c1 = (a[0] * b[1]) + (a[1] * b[0]);
        long c2 = (a[0] * b[2]) + (a[1] * b[1]) + (a[2] * b[0]);
        long c3 = (a[0] * b[3]) + (a[1] * b[2]) + (a[2] * b[1]) + (a[3] * b[0]);
        long c4 = (a[0] * b[4]) + (a[1] * b[3]) + (a[2] * b[2]) + (a[3] * b[1]) + (a[4] * b[0]);
        long c5 = (a[1] * b[4]) + (a[2] * b[3]) + (a[3] * b[2]) + (a[4] * b[1]);
        long c6 = (a[2] * b[4]) + (a[3] * b[3]) + (a[4] * b[2]);
        long c7 = (a[3] * b[4]) + (a[4] * b[3]);
        long c8 = (a[4] * b[4]);

        carryReduce(r, c0, c1, c2, c3, c4, c5, c6, c7, c8);
    }

    private void carryReduce(long[] r, long c0, long c1, long c2, long c3,
                             long c4, long c5, long c6, long c7, long c8) {
        //reduce(2, 2)
        r[2] = c2 + (c7 * SUBTRAHEND);
        c3 += (c8 * SUBTRAHEND);

        // carry(3, 2)
        long carry3 = carryValue(c3);
        r[3] = c3 - (carry3 << BITS_PER_LIMB);
        c4 += carry3;

        long carry4 = carryValue(c4);
        r[4] = c4 - (carry4 << BITS_PER_LIMB);
        c5 += carry4;

        // reduce(0, 2)
        r[0] = c0 + (c5 * SUBTRAHEND);
        r[1] = c1 + (c6 * SUBTRAHEND);

        // carry(0, 4)
        carry(r);
    }

    protected void multByInt(long[] a, long b, long[] r) {

        for (int i = 0; i < a.length; i++) {
            r[i] = a[i] * b;
        }

        reduce(r);
    }

    @Override
    protected void square(long[] a, long[] r) {
        // Use grade-school multiplication with a simple squaring optimization.
        // Multiply into primitives to avoid the temporary array allocation.
        // This is equivalent to the following code:
        //  long[] c = new long[2 * NUM_LIMBS - 1];
        //  for(int i = 0; i < NUM_LIMBS; i++) {
        //      c[2 * i] = a[i] * a[i];
        //      for(int j = i + 1; j < NUM_LIMBS; j++) {
        //          c[i + j] += 2 * a[i] * a[j]
        //      }
        //  }

        long c0 = (a[0] * a[0]);
        long c1 = 2 * (a[0] * a[1]);
        long c2 = 2 * (a[0] * a[2]) + (a[1] * a[1]);
        long c3 = 2 * (a[0] * a[3] + a[1] * a[2]);
        long c4 = 2 * (a[0] * a[4] + a[1] * a[3]) + (a[2] * a[2]);
        long c5 = 2 * (a[1] * a[4] + a[2] * a[3]);
        long c6 = 2 * (a[2] * a[4]) + (a[3] * a[3]);
        long c7 = 2 * (a[3] * a[4]);
        long c8 = (a[4] * a[4]);

        carryReduce(r, c0, c1, c2, c3, c4, c5, c6, c7, c8);
    }

    @Override
    protected void encode(ByteBuffer buf, int length, byte highByte,
                          long[] result) {
        if (length == 16) {
            long low = buf.getLong();
            long high = buf.getLong();
            encode(high, low, highByte, result);
        } else {
            super.encode(buf, length, highByte, result);
        }
    }

    protected void encode(long high, long low, byte highByte, long[] result) {
        result[0] = low & 0x3FFFFFFL;
        result[1] = (low >>> 26) & 0x3FFFFFFL;
        result[2] = (low >>> 52) + ((high & 0x3FFFL) << 12);
        result[3] = (high >>> 14) & 0x3FFFFFFL;
        result[4] = (high >>> 40) + (highByte << 24L);
    }

    private static final VarHandle AS_LONG_LE = MethodHandles
        .byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    protected void encode(byte[] v, int offset, int length, byte highByte,
                          long[] result) {
        if (length == 16) {
            long low = (long) AS_LONG_LE.get(v, offset);
            long high = (long) AS_LONG_LE.get(v, offset + 8);
            encode(high, low, highByte, result);
        } else {
            super.encode(v, offset, length, highByte, result);
        }
    }

    protected void modReduceIn(long[] limbs, int index, long x) {
        // this only works when BITS_PER_LIMB * NUM_LIMBS = POWER exactly
        long reducedValue = (x * SUBTRAHEND);
        limbs[index - NUM_LIMBS] += reducedValue;
    }

    protected final void modReduce(long[] limbs, int start, int end) {

        for (int i = start; i < end; i++) {
            modReduceIn(limbs, i, limbs[i]);
            limbs[i] = 0;
        }
    }

    protected void modReduce(long[] limbs) {

        modReduce(limbs, NUM_LIMBS, NUM_LIMBS - 1);
    }

    @Override
    protected long carryValue(long x) {
        // This representation has plenty of extra space, so we can afford to
        // do a simplified carry operation that is more time-efficient.

        return x >> BITS_PER_LIMB;
    }


    protected void reduce(long[] limbs) {
        long carry3 = carryOut(limbs, 3);
        long new4 = carry3 + limbs[4];

        long carry4 = carryValue(new4);
        limbs[4] = new4 - (carry4 << BITS_PER_LIMB);

        modReduceIn(limbs, 5, carry4);
        carry(limbs);
    }

    // Convert reduced limbs into a number between 0 and MODULUS-1
    private void finalReduce(long[] limbs) {

        addLimbs(limbs, posModLimbs, limbs);
        // now all values are positive, so remaining operations will be unsigned

        // unsigned carry out of last position and reduce in to first position
        long carry = limbs[NUM_LIMBS - 1] >> BITS_PER_LIMB;
        limbs[NUM_LIMBS - 1] -= carry << BITS_PER_LIMB;
        modReduceIn(limbs, NUM_LIMBS, carry);

        // unsigned carry on all positions
        carry = 0;
        for (int i = 0; i < NUM_LIMBS; i++) {
            limbs[i] += carry;
            carry = limbs[i] >> BITS_PER_LIMB;
            limbs[i] -= carry << BITS_PER_LIMB;
        }
        // reduce final carry value back in
        modReduceIn(limbs, NUM_LIMBS, carry);
        // we only reduce back in a nonzero value if some value was carried out
        // of the previous loop. So at least one remaining value is small.

        // One more carry is all that is necessary. Nothing will be carried out
        // at the end
        carry = 0;
        for (int i = 0; i < NUM_LIMBS; i++) {
            limbs[i] += carry;
            carry = limbs[i] >> BITS_PER_LIMB;
            limbs[i] -= carry << BITS_PER_LIMB;
        }

        // limbs are positive and all less than 2^BITS_PER_LIMB
        // but the value may be greater than the MODULUS.
        // Subtract the max limb values only if all limbs end up non-negative
        int smallerNonNegative = 1;
        long[] smaller = new long[NUM_LIMBS];
        for (int i = NUM_LIMBS - 1; i >= 0; i--) {
            smaller[i] = limbs[i] - posModLimbs[i];
            // expression on right is 1 if smaller[i] is nonnegative,
            // 0 otherwise
            smallerNonNegative *= (int) (smaller[i] >> 63) + 1;
        }
        conditionalSwap(smallerNonNegative, limbs, smaller);

    }

    @Override
    protected void limbsToByteArray(long[] limbs, byte[] result) {

        long[] reducedLimbs = limbs.clone();
        finalReduce(reducedLimbs);

        decode(reducedLimbs, result, 0, result.length);
    }

    @Override
    protected void addLimbsModPowerTwo(long[] limbs, long[] other,
                                       byte[] result) {

        long[] reducedOther = other.clone();
        long[] reducedLimbs = limbs.clone();
        finalReduce(reducedLimbs);

        addLimbs(reducedLimbs, reducedOther, reducedLimbs);

        // may carry out a value which can be ignored
        long carry = 0;
        for (int i = 0; i < NUM_LIMBS; i++) {
            reducedLimbs[i] += carry;
            carry  = reducedLimbs[i] >> BITS_PER_LIMB;
            reducedLimbs[i] -= carry << BITS_PER_LIMB;
        }

        decode(reducedLimbs, result, 0, result.length);
    }

}

