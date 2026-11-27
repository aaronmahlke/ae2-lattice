package dev.ae2lattice.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import appeng.api.stacks.AEKey;

/** Outstanding output units, credited in dispatch order because AE2 returns items without recipe provenance. */
final class InFlightTracker {
    static final int UNATTRIBUTED = -1;

    record Claim(AEKey key, int patternId, long amount) {}

    private static final class Pending {
        final int patternId;
        long amount;

        Pending(int patternId, long amount) {
            this.patternId = patternId;
            this.amount = amount;
        }
    }

    private final Map<AEKey, ArrayDeque<Pending>> pending = new LinkedHashMap<>();

    void add(int patternId, AEKey key, long amount) {
        if (amount <= 0) return;
        var queue = pending.computeIfAbsent(key, k -> new ArrayDeque<>());
        var last = queue.peekLast();
        // Consecutive runs of the same recipe need one counter, without dropping large batches.
        if (last != null && last.patternId == patternId) last.amount += amount;
        else queue.addLast(new Pending(patternId, amount));
    }

    long pending(int patternId, AEKey key) {
        var queue = pending.get(key);
        if (queue == null) return 0;
        long count = 0;
        for (var entry : queue) if (entry.patternId == patternId) count += entry.amount;
        return count;
    }

    List<Claim> returned(AEKey key, long amount) {
        var credited = new ArrayList<Claim>();
        if (amount <= 0) return credited;
        var queue = pending.get(key);
        long left = amount;
        while (queue != null && !queue.isEmpty() && left > 0) {
            var first = queue.peekFirst();
            long take = Math.min(left, first.amount);
            first.amount -= take;
            left -= take;
            credited.add(new Claim(key, first.patternId, take));
            if (first.amount == 0) queue.removeFirst();
        }
        if (queue != null && queue.isEmpty()) pending.remove(key);
        if (left > 0) credited.add(new Claim(key, UNATTRIBUTED, left));
        return credited;
    }

    /** Keep totals aligned with AE2, without assigning unobserved work to an arbitrary recipe. */
    void reconcile(Map<AEKey, Long> waiting) {
        var keys = new HashSet<>(pending.keySet());
        keys.addAll(waiting.keySet());
        for (var key : keys) {
            long tracked = 0;
            var queue = pending.get(key);
            if (queue != null) for (var entry : queue) tracked += entry.amount;
            long actual = Math.max(0, waiting.getOrDefault(key, 0L));
            if (actual > tracked) add(UNATTRIBUTED, key, actual - tracked);
            else if (actual < tracked) returned(key, tracked - actual);
        }
    }

    List<Claim> claims() {
        var result = new ArrayList<Claim>();
        pending.forEach((key, queue) -> {
            for (var entry : queue) result.add(new Claim(key, entry.patternId, entry.amount));
        });
        return result;
    }

    void clear() {
        pending.clear();
    }
}
