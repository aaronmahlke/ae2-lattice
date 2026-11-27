package dev.ae2lattice.client;

import java.util.List;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.ae2lattice.TestKey;
import dev.ae2lattice.net.SnapshotData;
import dev.ae2lattice.net.SnapshotData.KeyState;
import dev.ae2lattice.net.SnapshotData.PatternInfo;
import dev.ae2lattice.net.SnapshotData.PatternState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TreeModelCountsTest {
    @Test
    public void recipesSharingAnOutputHaveSeparatePendingAndInMachineCounts() {
        var key = new TestKey();
        var model = new TreeModel();
        var data = snapshot(key, 5, 20, 4, 7);
        data.structure = List.of(
                new PatternInfo(1, List.of(new GenericStack(key, 2)), List.of()),
                new PatternInfo(2, List.of(new GenericStack(key, 1)), List.of()));
        model.apply(data, 0);
        var first = model.byPattern.get(1);
        var second = model.byPattern.get(2);
        assertEquals(10, model.pendingOutput(first));
        assertEquals(20, model.pendingOutput(second));
        assertEquals(4, model.inMachines(first));
        assertEquals(7, model.inMachines(second));

        model.apply(snapshot(key, 0, 20, 0, 7), 1);
        assertEquals(0, model.pendingOutput(first));
        assertEquals(0, model.inMachines(first));
        assertEquals(TreeModel.Status.DONE, model.status(first));
        assertEquals(20, model.pendingOutput(second));
        assertEquals(7, model.inMachines(second));
        assertEquals(TreeModel.Status.CRAFTING, model.status(second));
        assertEquals(7, model.inMachines(new TreeModel.Node(key, -1)));
    }

    @Test
    public void missingHistoryRemainsUnknownInsteadOfShowingDoneOrBorrowingTheItemTotal() {
        var key = new TestKey();
        var model = new TreeModel();
        var data = snapshot(key, 0, 0, -1, 7);
        data.structure = List.of(new PatternInfo(1, List.of(new GenericStack(key, 1)), List.of()));
        model.apply(data, 0);
        var node = model.byPattern.get(1);
        assertEquals(-1, model.inMachines(node));
        assertEquals(TreeModel.Status.CRAFTING, model.status(node));
    }

    @Test
    public void fullyDispatchedLegacyJobShowsAnItemNodeWithoutInventingARecipe() {
        var key = new TestKey();
        var model = new TreeModel();
        var data = new SnapshotData();
        data.hasJob = true;
        data.jobUid = 1;
        data.structure = List.of();
        data.finalOutput = new GenericStack(key, 10);
        data.keys.add(new KeyState(key, 0, 10, 0));
        model.apply(data, 0);
        assertEquals(1, model.nodes.size());
        assertEquals(-1, model.root.patternId);
        assertEquals(10, model.inMachines(model.root));
    }

    @Test
    public void pendingOutputUsesBatchAmountsAndExcludesUnrelatedByproducts() {
        var key = new TestKey();
        var other = new TestKey();
        var model = new TreeModel();
        var data = snapshot(key, 3_000_000_000L, 0, 0, 0);
        data.structure = List.of(new PatternInfo(1,
                List.of(new GenericStack(key, 2), new GenericStack(other, 9)), List.of()));
        model.apply(data, 0);
        assertEquals(6_000_000_000L, model.pendingOutput(model.byPattern.get(1)));
    }

    private static SnapshotData snapshot(AEKey key, long firstRuns, long secondRuns, long firstActive, long secondActive) {
        var data = new SnapshotData();
        data.hasJob = true;
        data.jobUid = 1;
        data.finalOutput = new GenericStack(key, 30);
        data.states.add(new PatternState(1, firstRuns, firstActive, 0, List.of(), null));
        data.states.add(new PatternState(2, secondRuns, secondActive, 0, List.of(), null));
        data.keys.add(new KeyState(key, 0, Math.max(0, firstActive) + secondActive, firstRuns * 2 + secondRuns));
        return data;
    }
}
