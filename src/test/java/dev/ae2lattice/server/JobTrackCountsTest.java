package dev.ae2lattice.server;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import dev.ae2lattice.TestKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JobTrackCountsTest {
    @Test
    public void successfulDispatchesAndReturnsDriveSeparateRecipeCounts() {
        var product = new TestKey();
        var byproduct = new TestKey();
        var first = new Pattern(new GenericStack(product, 10), new GenericStack(byproduct, 2));
        var second = new Pattern(new GenericStack(product, 20));
        var job = new JobTrack(null);
        job.onPush(first, null);
        job.onPush(second, null);
        assertEquals(10, job.inMachines(job.track(first)));
        assertEquals(20, job.inMachines(job.track(second)));
        job.onReturn(product, 4);
        assertEquals(6, job.inMachines(job.track(first)));
        assertEquals(20, job.inMachines(job.track(second)));
        job.onReturn(product, 6);
        assertEquals(0, job.inMachines(job.track(first)));
        assertEquals(2, job.inflight.pending(job.track(first).id, byproduct));
    }

    @Test
    public void legacyHistoryIsUnknownUntilItsOutputsReturn() {
        var product = new TestKey();
        var pattern = new Pattern(new GenericStack(product, 3));
        var job = new JobTrack(null);
        job.inflight.add(InFlightTracker.UNATTRIBUTED, product, 7);
        job.onPush(pattern, null);
        assertEquals(-1, job.inMachines(job.track(pattern)));
        job.onReturn(product, 7);
        assertEquals(3, job.inMachines(job.track(pattern)));
    }

    private record Pattern(GenericStack... outputs) implements IPatternDetails {
        public AEItemKey getDefinition() { throw new UnsupportedOperationException(); }
        public IInput[] getInputs() { return new IInput[0]; }
        public GenericStack[] getOutputs() { return outputs; }
    }
}
