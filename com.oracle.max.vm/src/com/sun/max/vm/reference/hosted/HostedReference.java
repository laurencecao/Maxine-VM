/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
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
 */
package com.sun.max.vm.reference.hosted;

import com.sun.max.vm.reference.*;

/**
 */
public class HostedReference extends Reference {

    private final Object object;

    public HostedReference(Object object) {
        this.object = object;
    }

    @Override
    public boolean equals(Reference other) {
        if (other == object) {
            return true;
        }
        if (other instanceof HostedReference) {
            final HostedReference href = (HostedReference) other;
            return href.object == object;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return object.hashCode();
    }

    public Object getObject() {
        return object;
    }
}
