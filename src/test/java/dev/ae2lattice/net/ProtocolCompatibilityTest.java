package dev.ae2lattice.net;

import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class ProtocolCompatibilityTest {
    @Test
    public void oldClientDecodesNewServerSnapshotWithKnownRecipeCounts() {
        var data = currentSnapshot(12);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.write(buffer);
            var old = dev.ae2lattice.net.legacy.SnapshotData.read(buffer);
            assertEquals(data.jobUid, old.jobUid);
            assertEquals(1, old.structure.size());
            assertEquals(12, old.states.get(0).inMachines());
            assertEquals(5, old.states.get(0).remainingRuns());
            assertEquals(7, old.events.get(0).amount());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void oldClientGetsItsLegacyTotalInsteadOfAnUnknownCountSentinel() {
        var data = currentSnapshot(-1);
        data.states = List.of(data.states.get(0).forClient(false, 27));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.write(buffer);
            var old = dev.ae2lattice.net.legacy.SnapshotData.read(buffer);
            assertEquals(27, old.states.get(0).inMachines());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void capableClientsRetainUnknownCountsAndKnownCountsNeverNeedAFallback() {
        var unknown = currentSnapshot(-1).states.get(0);
        assertSame(unknown, unknown.forClient(true, 27));
        var known = currentSnapshot(12).states.get(0);
        assertSame(known, known.forClient(false, 27));
        assertEquals(0, unknown.forClient(false, 0).inMachines());
    }

    @Test
    public void newClientDecodesOldServerSnapshot() {
        var old = new dev.ae2lattice.net.legacy.SnapshotData();
        old.hasJob = true;
        old.jobUid = 123;
        old.structureVersion = 2;
        old.remainingAmount = 30;
        old.elapsedNanos = 8_000_000_000L;
        old.structure = List.of(new dev.ae2lattice.net.legacy.SnapshotData.PatternInfo(3, List.of(), List.of()));
        old.states.add(new dev.ae2lattice.net.legacy.SnapshotData.PatternState(3, 5, 40, 21, List.of(), null));
        old.events.add(new dev.ae2lattice.net.legacy.SnapshotData.Event(3, true, null, 7));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            old.write(buffer);
            var current = SnapshotData.read(buffer);
            assertEquals(old.jobUid, current.jobUid);
            assertEquals(old.elapsedNanos, current.elapsedNanos);
            assertEquals(3, current.structure.get(0).id());
            assertEquals(40, current.states.get(0).inMachines());
            assertEquals(5, current.states.get(0).remainingRuns());
            assertEquals(7, current.events.get(0).amount());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void noJobSnapshotRemainsCompatibleInBothDirections() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var current = new SnapshotData();
            current.emptyReason = SnapshotData.EMPTY_NO_CPU;
            current.write(buffer);
            var old = dev.ae2lattice.net.legacy.SnapshotData.read(buffer);
            assertFalse(old.hasJob);
            assertEquals(SnapshotData.EMPTY_NO_CPU, old.emptyReason);
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            old.write(buffer);
            var decoded = SnapshotData.read(buffer);
            assertFalse(decoded.hasJob);
            assertEquals(old.emptyReason, decoded.emptyReason);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static SnapshotData currentSnapshot(long inMachines) {
        var data = new SnapshotData();
        data.hasJob = true;
        data.jobUid = 123;
        data.structureVersion = 2;
        data.remainingAmount = 30;
        data.elapsedNanos = 8_000_000_000L;
        data.structure = List.of(new SnapshotData.PatternInfo(3, List.of(), List.of()));
        data.states.add(new SnapshotData.PatternState(3, 5, inMachines, 21, List.of(), null));
        data.events.add(new SnapshotData.Event(3, true, null, 7));
        return data;
    }
}
