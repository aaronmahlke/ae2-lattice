package dev.ae2lattice.server;

import java.util.List;
import java.util.Map;

import dev.ae2lattice.TestKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InFlightTrackerTest {
    @Test
    public void partialReturnOnlyCreditsTheOldestRecipe() {
        var key = new TestKey();
        var tracker = new InFlightTracker();
        tracker.add(1, key, 10);
        tracker.add(2, key, 20);
        assertEquals(List.of(new InFlightTracker.Claim(key, 1, 4)), tracker.returned(key, 4));
        assertEquals(6, tracker.pending(1, key));
        assertEquals(20, tracker.pending(2, key));
        assertEquals(List.of(new InFlightTracker.Claim(key, 1, 6), new InFlightTracker.Claim(key, 2, 2)),
                tracker.returned(key, 8));
        assertEquals(0, tracker.pending(1, key));
        assertEquals(18, tracker.pending(2, key));
    }

    @Test
    public void sameTickInterleavedDispatchesRetainTheirActualOrder() {
        var key = new TestKey();
        var tracker = new InFlightTracker();
        tracker.add(1, key, 3);
        tracker.add(2, key, 5);
        tracker.add(1, key, 7);
        assertEquals(List.of(new InFlightTracker.Claim(key, 1, 3), new InFlightTracker.Claim(key, 2, 4)),
                tracker.returned(key, 7));
        assertEquals(7, tracker.pending(1, key));
        assertEquals(1, tracker.pending(2, key));
    }

    @Test
    public void returningPrimaryOutputDoesNotDiscardOutstandingByproducts() {
        var primary = new TestKey();
        var byproduct = new TestKey();
        var tracker = new InFlightTracker();
        tracker.add(1, primary, 8);
        tracker.add(1, byproduct, 2);
        tracker.add(2, byproduct, 5);
        tracker.returned(primary, 8);
        assertEquals(2, tracker.pending(1, byproduct));
        assertEquals(5, tracker.pending(2, byproduct));
        tracker.returned(byproduct, 1);
        assertEquals(1, tracker.pending(1, byproduct));
        assertEquals(5, tracker.pending(2, byproduct));
    }

    @Test
    public void largeBatchesNeverDropTheOldestDispatchesOrStopCreditingEarly() {
        var key = new TestKey();
        var tracker = new InFlightTracker();
        for (int i = 0; i < 20_000; i++) tracker.add(i % 2, key, 1);
        tracker.returned(key, 19_999);
        assertEquals(0, tracker.pending(0, key));
        assertEquals(1, tracker.pending(1, key));
        tracker.add(1, key, 3_000_000_000L);
        assertEquals(3_000_000_001L, tracker.pending(1, key));
    }

    @Test
    public void consecutiveRunsAreCompactedWithoutLosingCounts() {
        var key = new TestKey();
        var tracker = new InFlightTracker();
        for (int i = 0; i < 20_000; i++) tracker.add(1, key, 2);
        assertEquals(List.of(new InFlightTracker.Claim(key, 1, 40_000)), tracker.claims());
    }

    @Test
    public void missingHistoryIsUnattributedAndDoesNotConsumeNewlyDispatchedWorkFirst() {
        var key = new TestKey();
        var tracker = new InFlightTracker();
        tracker.reconcile(Map.of(key, 7L));
        tracker.add(1, key, 3);
        tracker.reconcile(Map.of(key, 10L));
        tracker.returned(key, 7);
        assertEquals(0, tracker.pending(InFlightTracker.UNATTRIBUTED, key));
        assertEquals(3, tracker.pending(1, key));
    }

    @Test
    public void reconciliationClearsStaleCountsAndAddsMissingCountsWithoutDuplicatingThem() {
        var key = new TestKey();
        var other = new TestKey();
        var tracker = new InFlightTracker();
        tracker.add(1, key, 5);
        tracker.add(2, key, 8);
        tracker.add(1, other, 4);
        tracker.reconcile(Map.of(key, 10L));
        assertEquals(2, tracker.pending(1, key));
        assertEquals(8, tracker.pending(2, key));
        assertEquals(0, tracker.pending(1, other));
        tracker.reconcile(Map.of(key, 12L));
        tracker.reconcile(Map.of(key, 12L));
        assertEquals(2, tracker.pending(InFlightTracker.UNATTRIBUTED, key));
    }

    @Test
    public void restoringClaimsPreservesOrderAndPartialAmounts() {
        var key = new TestKey();
        var original = new InFlightTracker();
        original.add(1, key, 10);
        original.add(2, key, 20);
        original.add(1, key, 30);
        original.returned(key, 4);
        var restored = new InFlightTracker();
        for (var claim : original.claims()) restored.add(claim.patternId(), claim.key(), claim.amount());
        assertEquals(original.claims(), restored.claims());
        assertEquals(original.returned(key, 12), restored.returned(key, 12));
        assertEquals(original.claims(), restored.claims());
    }
}
