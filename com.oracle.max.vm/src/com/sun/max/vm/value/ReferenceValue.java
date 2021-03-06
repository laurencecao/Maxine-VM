/*
 * Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.vm.value;

import java.io.*;

import com.sun.cri.ci.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.type.*;

/**
 * Abstract type for different boxed representations of object references (and null).
 *
 * @see Reference
 */
public abstract class ReferenceValue extends Value<ReferenceValue> {

    protected ReferenceValue() {
    }

    @Override
    public Kind<ReferenceValue> kind() {
        return Kind.REFERENCE;
    }

    public static ReferenceValue from(Object object) {
        return ObjectReferenceValue.from(object);
    }

    public static ReferenceValue fromReference(Reference reference) {
        return ObjectReferenceValue.from(reference.toJava());
    }

    @Override
    public void write(DataOutput stream) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Boxed representation of 'null'.
     */
    public static final ReferenceValue NULL = ObjectReferenceValue.NULL_OBJECT;

    public abstract ClassActor getClassActor();

    @Override
    public CiConstant asCiConstant() {
        return CiConstant.forObject(asObject());
    }
}
