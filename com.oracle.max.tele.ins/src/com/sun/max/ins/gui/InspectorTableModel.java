/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.max.ins.gui;

import javax.swing.table.*;

import com.sun.max.ins.*;
import com.sun.max.ins.view.*;
import com.sun.max.tele.*;

/**
 * A table model specialized for the Inspector's table-based views.
 */
public abstract class InspectorTableModel extends AbstractTableModel implements InspectionHolder {

    private final Inspection inspection;
    private final String tracePrefix;

    public InspectorTableModel(Inspection inspection) {
        this.inspection = inspection;
        this.tracePrefix = "[" + getClass().getSimpleName() + "] ";
    }

    public final Inspection inspection() {
        return inspection;
    }

    public final MaxVM vm() {
        return inspection.vm();
    }

    public InspectorGUI gui() {
        return inspection.gui();
    }

    public final InspectionFocus focus() {
        return inspection.focus();
    }

    public final InspectionViews views() {
        return inspection.views();
    }

    public final InspectionActions actions() {
        return inspection.actions();
    }

    public final InspectionPreferences preference() {
        return inspection.preference();
    }

    /**
     * Returns a human-readable text string describing the identity of a row
     * in the table, suitable for the prologue of a tool tip text over each
     * cell.  The prologue identifies the row, to be followed by any additional
     * information, if available, about the particular table cell under the cursor.
     * <br>
     * The default is the string "Row n".
     *
     * @param row index of a row in the table
     * @return a human-readable string describing the particular row.
     */
    public String getRowDescription(int row) {
        return "Row " + row;
    }

    /**
     * @return default prefix text for trace messages; identifies the class being traced.
     */
    protected String tracePrefix() {
        return tracePrefix;
    }

    /**
     * Updates any clients of the table data, notifying them that some contents have changed.
     */
    public void refresh() {
        fireTableDataChanged();
    }
}
