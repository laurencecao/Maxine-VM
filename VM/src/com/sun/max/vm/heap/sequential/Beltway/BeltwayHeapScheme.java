/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/*VCSID=fcddde17-0fd0-4689-ae78-1a8d2af07e88*/
package com.sun.max.vm.heap.sequential.Beltway;

import com.sun.max.annotate.*;
import com.sun.max.lang.*;
import com.sun.max.memory.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.heap.profile.*;
import com.sun.max.vm.heap.sequential.*;
import com.sun.max.vm.heap.util.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.object.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.runtime.*;
import com.sun.max.vm.thread.*;

/**
 * @author Christos Kotselidis
 */

public class BeltwayHeapScheme extends GripUpdatingHeapScheme implements HeapScheme, Allocator {

    public static final CardRegion _cardRegion = new CardRegion();
    public static final SideTable _sideTable = new SideTable();
    protected Address _adjustedCardTableAddress = Address.zero();

    public static final HeapVerifier _heapVerifier = new HeapVerifier();
    // public static final HeapProfiler _heapProfiler = new HeapProfiler();

    private static final BeltwaySequentialHeapRootsScanner _heapRootsScanner = new BeltwaySequentialHeapRootsScanner();
    public static final OutOfMemoryError _outOfMemoryError = new OutOfMemoryError();

    protected static BeltwayConfiguration _beltwayConfiguration = new BeltwayConfiguration();
    protected static BeltManager _beltManager = new BeltManager();
    protected static BeltwayCollector _beltCollector = new BeltwayCollector();
    protected static StopTheWorldDaemon _collectorThread;

    protected static MaxineVM.Phase _phase;
    public static boolean _outOfMemory = false;

    public static BeltwayCollectorThread[] _gcThreads = new BeltwayCollectorThread[BeltwayConfiguration._numberOfGCThreads];
    public static long _lastThreadAllocated;

    public static volatile long _allocatedTLABS = 0;
    public static Object _tlabCounterMutex = new Object();
    public static volatile long _retrievedTLABS = 0;
    public static Object _tlabRetrieveMutex = new Object();
    public static boolean _inGC = false;
    public static boolean _inScavening = false;

    public static TLAB[] _scavengerTLABs = new TLAB[BeltwayConfiguration._numberOfGCThreads + 1];

    public BeltwayHeapScheme(VMConfiguration configuration) {
        super(configuration);

    }

    @Override
    public void initialize(MaxineVM.Phase phase) {
        _phase = phase;
        if (phase == MaxineVM.Phase.PROTOTYPING) {
            _beltManager.createBelts();
        } else if (phase == MaxineVM.Phase.RUNNING) {
            if (BeltwayConfiguration._parallelScavenging) {
                createGCThreads();
            }
        }
    }

    @INLINE
    protected final Address allocateMemory(Size size) {
        final Address endOfCodeRegion = VirtualMemory.getEndOfCodeRegion();
        if (VirtualMemory.allocateMemoryAtFixedAddress(endOfCodeRegion, size)) {
            return endOfCodeRegion;
        }
        FatalError.unexpected("Error! Could not map fix the requested memory size");
        return Address.zero();

    }

    @INLINE
    public final BeltManager getBeltManager() {
        return _beltManager;
    }

    @INLINE
    public final BeltwaySequentialHeapRootsScanner getRootScannerVerifier() {
        _heapRootsScanner.setPointerIndexVisitor(getPointerIndexGripVerifier());
        return _heapRootsScanner;
    }

    @INLINE
    public final BeltwaySequentialHeapRootsScanner getRootScannerUpdater() {
        _heapRootsScanner.setPointerIndexVisitor(getPointerIndexGripUpdater());
        return _heapRootsScanner;
    }

    @INLINE
    public final HeapVerifier getVerifier() {
        return _heapVerifier;
    }

    @INLINE
    protected Size calculateHeapSize() {
        Size size = Heap.initialSize();
        if (Heap.maxSize().greaterThan(size)) {
            size = Heap.maxSize();
        }
        return size.roundedUpBy(HeapSchemeConfiguration.ALLIGNMENT).asSize();
    }

    public void wipeMemory(Belt belt) {
        Pointer cell = belt.start().asPointer();
        while (cell.lessThan(belt.end())) {
            cell.setLong(DebugHeap.UNINITIALIZED);
            cell = cell.plusWords(1);
        }
    }

    @INLINE
    protected final void createGCThreads() {
        for (int i = 0; i < BeltwayConfiguration._numberOfGCThreads; i++) {
            _gcThreads[i] = new BeltwayCollectorThread(i);
        }

    }

    public final void startGCThreads() {
        for (int i = 0; i < _gcThreads.length; i++) {
            _gcThreads[i].trigger();
        }

    }

    public void initializeGCThreads(BeltwayHeapScheme beltwayHeapScheme, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        for (int i = 0; i < _gcThreads.length; i++) {
            _gcThreads[i].initialize(beltwayHeapScheme, from, to);
        }
    }

    public void scanBootHeap(RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        Heap.bootHeapRegion().beltWayVisitCells(getVisitor(), _copyAction, from, to);
    }

    public void printCardTable() {
        final int startCardIndex = _cardRegion.getCardIndexFromHeapAddress(Heap.bootHeapRegion().start());
        final int endCardIndex = _cardRegion.getCardIndexFromHeapAddress(_beltManager.getEnd());
        for (int i = startCardIndex; i < endCardIndex; i++) {
            if (_cardRegion.isCardMarked(i)) {
                Debug.print("0");
            } else {
                Debug.print("1");
            }
            Debug.print(" -- ");
            Debug.println(_cardRegion.getHeapAddressFromCardIndex(i));
        }
    }

    public void testCardAllignment(Pointer address) {
        final int boundaryIndex = _cardRegion.getCardIndexFromHeapAddress(address);
        final int prevIndex = _cardRegion.getCardIndexFromHeapAddress(address.minusWords(1));
        if (boundaryIndex == prevIndex) {
            Debug.println("Error in TLAB allignment");
            Debug.println("Erroneous Address");
            Debug.print(address);
            Debug.println("boundaryIndex");
            Debug.println(boundaryIndex);
            Debug.println("prevIndex");
            Debug.println(prevIndex);
            FatalError.unexpected("ERROR in CARD ALLIGNMENT");

        }
    }

    public final void scanCards(RuntimeMemoryRegion origin, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        final int startCardIndex = _cardRegion.getCardIndexFromHeapAddress(origin.start());
        final int endCardIndex = _cardRegion.getCardIndexFromHeapAddress(origin.end());
        for (int i = startCardIndex; i < endCardIndex; i++) {
            if (_cardRegion.isCardMarked(i)) {
                final Address heapStartAddress = _cardRegion.getHeapAddressFromCardIndex(i);
                final Pointer gcTLABStart = getGCTLABStartFromAddress(heapStartAddress);
                if (!gcTLABStart.isZero()) {
                    final Pointer gcTLABEnd = getGCTLABEndFromStart(gcTLABStart);
                    CellVisitorImpl.linearVisitAllCellsTLAB(getVisitor(), _copyAction, gcTLABStart, gcTLABEnd, from, to);
                    SideTable.markScavengeSideTable(gcTLABStart);
                }
            }

        }
    }

    public void testCards(RuntimeMemoryRegion origin, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        final int startCardIndex = _cardRegion.getCardIndexFromHeapAddress(origin.start());
        final int endCardIndex = _cardRegion.getCardIndexFromHeapAddress(origin.end());

        for (int i = startCardIndex; i < endCardIndex; i++) {
            if (_cardRegion.isCardMarked(i)) {
                Debug.print("Card: ");
                Debug.print(i);
                Debug.println("  is Dirty ");

                final Address heapStartAddress = _cardRegion.getHeapAddressFromCardIndex(i);

                Debug.print("Correspoding heap Address: ");
                Debug.println(heapStartAddress);
            }
        }
    }

    public final void linearScanRegion(RuntimeMemoryRegion origin, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        CellVisitorImpl.linearVisitAllCells(getVisitor(), _copyAction, origin, from, to);
    }

    public final void linearScanRegionBelt(Belt origin, RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        CellVisitorImpl.linearVisitAllCellsBelt(getVisitor(), _copyAction, origin, from, to);
    }

    public void scanCode(RuntimeMemoryRegion from, RuntimeMemoryRegion to) {
        Code.visitCells(getVisitor(), _copyAction, from, to);
    }

    @INLINE
    public Pointer gcBumpAllocate(RuntimeMemoryRegion belt, Size size) {
        return _beltManager.gcBumpAllocate((Belt) belt, size);
    }

    @INLINE
    public Pointer gcSynchAllocate(RuntimeMemoryRegion belt, Size size) {
        return _beltManager.gcAllocate((Belt) belt, size);
    }

    @INLINE
    public Pointer gcAllocate(RuntimeMemoryRegion belt, Size size) {
        if (BeltwayConfiguration._useGCTlabs) {
            return gcTlabAllocate(belt, size);
        }
        return gcBumpAllocate(belt, size);

    }

    @INLINE
    public boolean isGcThread(VmThread vmThread) {
        return vmThread.javaThread() instanceof StopTheWorldDaemon;
    }

    /**
     * Allocation from heap (SlowPath). This method delegates the allocation to the belt denoted by the Belt Manager.
     * Currently we are synchronizing to avoid race conditions. TODO: Recalculate tlabs' sizes
     *
     * @param size The size of the allocation.
     * @return the pointer to the address in which we can allocate. If null, a GC should be triggered.
     */
    @INLINE
    private Pointer allocateSlowPath(Belt belt, Size size) {
        return _beltManager.allocate(belt, size);
    }

    @INLINE
    protected Pointer bumpAllocateSlowPath(Belt belt, Size size) {
        final Pointer pointer = _beltManager.bumpAllocate(belt, size);
        if (pointer.isZero()) {
            throw _outOfMemoryError;
        }
        return pointer;
    }

    @Override
    @INLINE
    @NO_SAFEPOINTS("TODO")
    public Pointer allocate(Size size) {
        return Pointer.zero();
    }

    @INLINE
    @NO_SAFEPOINTS("TODO")
    public Pointer heapAllocate(Belt belt, Size size) {
        final Pointer pointer = allocateSlowPath(belt, size);
        if (pointer.equals(Pointer.zero())) {
            if (belt.getIndex() == (BeltwayConfiguration.getNumberOfBelts() - 1)) {
                throw _outOfMemoryError;
            }
            if (!(VMConfiguration.hostOrTarget().heapScheme().collect(size))) {
                throw _outOfMemoryError;
            }
            return allocateSlowPath(belt, size);
        }
        return pointer;
    }

    @INLINE
    @NO_SAFEPOINTS("TODO")
    public Pointer tlabAllocate(Belt belt, Size size) {
        final VmThread thread = VmThread.current();
        final TLAB tlab = thread.getTLAB();

        if (tlab.isSet()) {
            final Pointer pointer = tlab.allocate(size);
            if (pointer.equals(Pointer.zero())) { // TLAB is full
                //Debug.println("TLAB is full, try to allocate a new TLAB");

                final Size newSize = calculateTLABSize(size);
                final Size allocSize = newSize.asPointer().minusWords(1).asSize();
                final Pointer newTLABAddress = allocateTLAB(belt, allocSize);

                //Debug.print("New Tlab Address: ");
                //Debug.println(newTLABAddress);

                if (newTLABAddress.isZero()) { // TLAB allocation failed, nursery is full, Trigger GC
                    //Debug.println("Nursery is full, trigger GC");
                    if (!VMConfiguration.hostOrTarget().heapScheme().collect(size)) {
                        throw _outOfMemoryError;

                    }
                    initializeFirstTLAB(belt, tlab, size);
                    return tlab.allocate(size);
                }
                // new TLAB has been successfully allocated, Rest thread's TLAB to the new one and do
                initializeTLAB(tlab, newTLABAddress, newSize);
                return tlab.allocate(size);
            }

            return pointer;
        }
        // Allocate first TLAB
        initializeFirstTLAB(belt, tlab, size);
        return tlab.allocate(size);

    }

    @INLINE
    public Pointer gcTlabAllocate(RuntimeMemoryRegion gcRegion, Size size) {
        final VmThread thread = VmThread.current();
        final TLAB tlab = thread.getTLAB();
        _lastThreadAllocated = thread.serial();
        if (tlab.isSet()) { // If the TLABS has been set
            final Pointer pointer = tlab.allocate(size);

            // If the return address is zero, it means that the TLAB is full
            if (pointer.equals(Pointer.zero())) {
                final Size newSize = calculateTLABSize(size);
                final Size allocSize = newSize.asPointer().minusWords(1).asSize();
                final Pointer newTLABAddress = gcAllocateTLAB(gcRegion, allocSize);
                _sideTable.markCreatingSideTable(newTLABAddress);
                if (!SideTable.isScavenged(tlab.start())) {
                    SideTable.markStartSideTable(tlab.start());
                }
                if (newTLABAddress.isZero()) { // TLAB allocation failed, nursery is full, Trigger GC
                    //Debug.println("Nursery is full, trigger GC");
                    if (!collect(size) || BeltwayHeapScheme._outOfMemory) {
                        throw _outOfMemoryError;

                    }
                    initializeFirstGCTLAB(gcRegion, tlab, size);
                    return tlab.allocate(size);
                }

                //Debug.lock();
                //Debug.print("GC TLAB Set Successfully: ");
                //Debug.println(newTLABAddress);
                //Debug.print(" from thread with id: ");
                //Debug.println(thread.id());
                //Debug.unlock();
                //_allocatedTLABS++;
                initializeTLAB(tlab, newTLABAddress, newSize);
                return tlab.allocate(size);
            }

            // Successful allocation in the existing TLAB, return the address
            return pointer;
        }
        // Allocate first GC TLAB
        initializeFirstGCTLAB(gcRegion, tlab, size);
        // Allocate in the first tlab
        return tlab.allocate(size);

    }

    @INLINE
    public Size calculateTLABSize(Size size) {
        Size defaultSize;
        defaultSize = BeltwayConfiguration.TLAB_SIZE;
        if (_inGC) {
            defaultSize = BeltwayConfiguration.GC_TLAB_SIZE;
        }
        Size newSize = defaultSize;
        while (size.greaterThan(newSize.minus(100))) {
            newSize = newSize.plus(200);
            newSize = newSize.roundedUpBy(defaultSize).asSize();
        }
        return newSize;
    }

    @INLINE
    public void initializeFirstTLAB(Belt belt, TLAB tlab, Size size) {
        //Debug.println("Try to set initial Tlabs");
        final Size newSize = calculateTLABSize(size);
        final Size allocSize = newSize.asPointer().minusWords(1).asSize();
        final Pointer newTLABAddress = allocateTLAB(belt, allocSize);

        if (newTLABAddress.isZero()) {
            FatalError.unexpected("Nursery is full, trigger GC in the First Allocation(?) Smth is wrong....");
        } else {
            //Debug.println("TLAB Set Successfully: ");
            initializeTLAB(tlab, newTLABAddress, newSize);
        }
    }

    @INLINE
    public void initializeFirstGCTLAB(RuntimeMemoryRegion gcRegion, TLAB tlab, Size size) {
        //Debug.println("Try to set initial GC TLAB");
        final Size newSize = calculateTLABSize(size);
        final Size allocSize = newSize.asPointer().minusWords(1).asSize();
        final Pointer newTLABAddress = gcAllocateTLAB(gcRegion, allocSize);
        _sideTable.markCreatingSideTable(newTLABAddress);
        if (newTLABAddress.isZero()) {
            FatalError.unexpected("Nursery is full, trigger GC in the First Allocation(?) Smth is wrong!");
        } else {
            //Debug.print("GC TLAB  Set Successfully: ");
            //Debug.println(newTLABAddress);
            // synchronized (_tlabCounterMutex) {
            //_allocatedTLABS++;
            // }
            initializeTLAB(tlab, newTLABAddress, newSize);
            //Debug.print("Initialized TLAB ");
            // Card Checking
            //testCardAllignment(newTLABAddress);

        }
    }

    @INLINE
    public void initializeTLAB(TLAB tlab, Pointer newTLABAddress, Size size) {
        tlab.initializeTLAB(newTLABAddress.asAddress(), newTLABAddress.asAddress(), size);
    }

    @INLINE
    public Pointer allocateTLAB(Belt belt, Size size) {
        Pointer pointer = heapAllocate(belt, size);
        if (pointer != Pointer.zero()) {
            if (VMConfiguration.hostOrTarget().debugging()) {
                // Subtract one word as it will be overwritten by the debug word of the TLAB descriptor
                pointer = pointer.minusWords(1);
            }
            if (Heap.verbose()) {
                HeapStatistics.incrementMutatorTlabAllocations();
            }
        }
        return pointer;
    }

    @INLINE
    public Pointer gcAllocateTLAB(RuntimeMemoryRegion gcRegion, Size size) {
        Pointer pointer = gcSynchAllocate(gcRegion, size);
        if (!pointer.isZero()) {
            if (VMConfiguration.hostOrTarget().debugging()) {
                // Subtract one word as it will be overwritten by the debug word of the TLAB descriptor
                pointer = pointer.minusWords(1);
            }
        } else {
            throw BeltwayHeapScheme._outOfMemoryError;
        }
        return pointer;
    }

    @INLINE
    public Pointer allocate(RuntimeMemoryRegion to, Size size) {
        return null;
    }

    @INLINE
    @Override
    public synchronized boolean collect(Size size) {
        return false;
    }

    @INLINE
    protected synchronized boolean minorCollect(Size size) {

        return false;
    }

    @INLINE
    protected synchronized boolean majorCollect(Size size) {
        return false;
    }

    /**
     * Perform thread-local initializations specific to the heap scheme when starting a new VM thread. For instance
     * install card table address.
     */
    @Override
    public void initializeVmThread(VmThread vmThread) {

    }

    /**
     * Allocate a new array object and fill in its header and initial data.
     */
    @INLINE
    @NO_SAFEPOINTS("TODO")
    @Override
    public Object createArray(DynamicHub dynamicHub, int length) {
        final Size size = Layout.getArraySize(dynamicHub.classActor().componentClassActor().kind(), length);
        final Pointer cell = allocate(size);
        return Cell.plantArray(cell, size, dynamicHub, length);
    }

    /**
     * Allocate a new tuple and fill in its header and initial data. Obtain the cell size from the given tuple class
     * actor.
     */
    @INLINE
    @NO_SAFEPOINTS("TODO")
    @Override
    public Object createTuple(Hub hub) {
        final Pointer cell = allocate(hub.tupleSize());
        return Cell.plantTuple(cell, hub);
    }

    @INLINE
    @NO_SAFEPOINTS("TODO")
    public <Hybrid_Type extends Hybrid> Hybrid_Type createHybrid(DynamicHub hub) {
        final Size size = hub.tupleSize();
        final Pointer cell = allocate(size);
        @JavacSyntax("type checker not able to infer type here")
        final Class<Hybrid_Type> type = null;
        return UnsafeLoophole.cast(type, Cell.plantHybrid(cell, size, hub));
    }

    @INLINE
    @NO_SAFEPOINTS("TODO")
    @Override
    public <Hybrid_Type extends Hybrid> Hybrid_Type expandHybrid(Hybrid_Type hybrid, int length) {
        final Size newSize = Layout.hybridLayout().getArraySize(length);
        final Pointer newCell = allocate(newSize);
        return Cell.plantExpandedHybrid(newCell, newSize, hybrid, length);
    }

    @INLINE
    @NO_SAFEPOINTS("TODO")
    @Override
    public Object clone(Object object) {
        final Size size = Layout.size(Reference.fromJava(object));
        final Pointer cell = allocate(size);
        return Cell.plantClone(cell, size, object);
    }

    @Override
    public boolean contains(Address address) {
        return false;
    }

    @Override
    public boolean collectGarbage(Size requestedFreeSpace) {
        return VMConfiguration.hostOrTarget().heapScheme().collect(requestedFreeSpace);
    }

    @Override
    public Size reportFreeSpace() {
        return _beltManager.reportFreeSpace();
    }

    @Override
    public void runFinalization() {

    }

    @Override
    public <Object_Type> boolean flash(Object_Type object, Procedure<Object_Type> procedure) {
        return false;
    }

    @Override
    public boolean pin(Object object) {
        return false;
    }

    @Override
    public void unpin(Object object) {

    }

    @Override
    public boolean isPinned(Object object) {
        return false;
    }

    @INLINE
    @Override
    public void writeBarrier(Reference reference) {

    }

    @Override
    public void scanRegion(Address start, Address end) {

    }

    @Override
    public Address adjustedCardTableAddress() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void initializePrimordialCardTable(Pointer primordialLocalSpace, Pointer auxiliarySpace) {
        VmThreadLocal.ADJUSTED_CARDTABLE_BASE.setConstantWord(primordialLocalSpace, CardRegion.adjustedCardTableBase(auxiliarySpace));
    }

    private Size cardTableSize(Size coveredRegionSize) {
        return coveredRegionSize.unsignedShiftedRight(CardRegion.CARD_SHIFT);
    }

    @Override
    public int auxiliarySpaceSize(int bootImageSize) {
        return cardTableSize(Size.fromInt(bootImageSize)).toInt();
    }

    // /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // TLAB code////
    // /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    public void fillLastTLAB() {
        // TODO: why not use the thread id?
        final TLAB tlab = VmThreadMap.ACTIVE.getThreadFromSerial(_lastThreadAllocated).getTLAB();
        tlab.fillTLAB();
    }

    public void markSideTableLastTLAB() {
        // TODO: why not use the thread id?
        final TLAB tlab = VmThreadMap.ACTIVE.getThreadFromSerial(_lastThreadAllocated).getTLAB();
        SideTable.markStartSideTable(tlab.start());
    }

    public void resetCurrentTLAB() {
        final BeltwayCollectorThread thread = (BeltwayCollectorThread) Thread.currentThread();
        final TLAB tlab = thread.getScavengeTLAB();
        tlab.unSet();
    }

    private static class StopTheWorldTLABReset implements Procedure<VmThread> {

        @Override
        public void run(VmThread thread) {
            thread.getTLAB().unSet();
        }
    }

    private static final StopTheWorldTLABReset _stopTheWorldReset = new StopTheWorldTLABReset();

    public Pointer getGCTLABStartFromAddress(Address address) {
        Address tlabAddress = address;
        if (!address.isAligned(HeapSchemeConfiguration.GC_TLAB_SIZE.toInt())) {
            //Debug.println("Is not alligned");
            tlabAddress = address.roundedDownBy(HeapSchemeConfiguration.GC_TLAB_SIZE.toInt());
        }
        while (!SideTable.isStart(SideTable.getChunkIndexFromHeapAddress(tlabAddress))) {
            if (SideTable.isScavenged(SideTable.getChunkIndexFromHeapAddress(tlabAddress))) {
                return Pointer.zero();
            }
            tlabAddress = tlabAddress.asPointer().minusWords(1);
            tlabAddress = tlabAddress.roundedDownBy(HeapSchemeConfiguration.GC_TLAB_SIZE.toInt());
        }
        return tlabAddress.asPointer();
    }

    public Pointer getGCTLABEndFromStart(Address address) {
        int index = SideTable.getChunkIndexFromHeapAddress(address) + 1;
        while (_sideTable.isMiddle(index)) {
            index++;
        }
        return _sideTable.getHeapAddressFromChunkIndex(index).asPointer();
    }

    public Pointer getNextAvailableGCTask(int searchIndex, int stopSearchIndex) {
        int startSearchIndex = searchIndex;
        while (startSearchIndex < stopSearchIndex) {
            if (SideTable.isStart(startSearchIndex)) {
                if (_sideTable.compareAndSwapStart(startSearchIndex) == SideTable.START) {
                    return _sideTable.getHeapAddressFromChunkIndex(startSearchIndex).asPointer();
                }
            }
            startSearchIndex++;
        }
        if (SideTable.isStart(startSearchIndex)) {
            if (_sideTable.compareAndSwapStart(startSearchIndex) == SideTable.START) {
                return _sideTable.getHeapAddressFromChunkIndex(startSearchIndex).asPointer();
            }
        }
        return Pointer.zero();
    }

    private void createScavengerTLABs() {
        for (int i = 0; i < BeltwayConfiguration._numberOfGCThreads + 1; i++) {
            _scavengerTLABs[i] = new TLAB();
        }
    }

    public long numberOfGarbageTurnovers() {
        return 0;
    }

}
