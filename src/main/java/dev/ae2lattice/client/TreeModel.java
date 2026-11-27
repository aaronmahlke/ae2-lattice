package dev.ae2lattice.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import dev.ae2lattice.net.SnapshotData;
import dev.ae2lattice.net.SnapshotData.KeyState;

import dev.ae2lattice.net.SnapshotData.PatternInfo;
import dev.ae2lattice.net.SnapshotData.PatternState;

/** Client-side graph of the running job: one node per pattern (step), plus leaf nodes for raw materials. */
public final class TreeModel {
    public static final int NODE_W = 120, NODE_H = 30, CHIP_H = 12, COL_W = 185, ROW_H = 56;

    public enum Status { DONE, AVAILABLE, SCHEDULED, QUEUED, CRAFTING, STALLED }

    public static final class Node {
        public final AEKey key;
        public final int patternId; // -1 = raw material
        public float x, y;
        int rank = -1;
        public final List<Edge> in = new ArrayList<>();
        public final List<Edge> out = new ArrayList<>();
        public long pulseUntil;
        public boolean critical;

        Node(AEKey key, int patternId) {
            this.key = key;
            this.patternId = patternId;
        }

        public boolean isLeaf() {
            return patternId < 0;
        }
    }

    public static final class Edge {
        public final Node from, to;
        public final AEKey key;
        public long perRun;
        public boolean critical;

        Edge(Node from, Node to, AEKey key, long perRun) {
            this.from = from;
            this.to = to;
            this.key = key;
            this.perRun = perRun;
        }
    }

    public boolean hasJob;
    public long jobUid;
    @Nullable
    public SnapshotData last;
    public long lastUpdateMs;
    public boolean layoutChanged;

    public final Map<Integer, PatternInfo> patterns = new LinkedHashMap<>();
    public final Map<Integer, PatternState> states = new HashMap<>();
    public final Map<AEKey, KeyState> keys = new HashMap<>();

    public final List<Node> nodes = new ArrayList<>();
    public final Map<Integer, Node> byPattern = new HashMap<>();
    public final List<Edge> edges = new ArrayList<>();
    @Nullable
    public Node root;
    public float minX, minY, maxX, maxY;

    void clear() {
        hasJob = false;
        jobUid = 0;
        last = null;
        patterns.clear();
        states.clear();
        keys.clear();
        nodes.clear();
        byPattern.clear();
        edges.clear();
        root = null;
    }

    /** @return true if this is a new job (camera should re-fit) */
    boolean apply(SnapshotData d, long nowMs) {
        if (!d.hasJob) {
            boolean had = hasJob;
            clear();
            return had;
        }
        boolean newJob = d.jobUid != jobUid;
        if (newJob) {
            clear();
            jobUid = d.jobUid;
        }
        hasJob = true;
        last = d;
        lastUpdateMs = nowMs;
        if (d.structure != null) {
            patterns.clear();
            for (var p : d.structure) patterns.put(p.id(), p);
            rebuild();
            layoutChanged = true;
        }
        states.clear();
        for (var s : d.states) states.put(s.id(), s);
        keys.clear();
        for (var k : d.keys) keys.put(k.key(), k);
        return newJob;
    }

    // ---------------------------------------------------------------------------------------------
    // graph

    private void rebuild() {
        nodes.clear();
        byPattern.clear();
        edges.clear();
        Map<AEKey, Node> producer = new HashMap<>();
        Map<AEKey, Node> leaves = new HashMap<>();

        for (var p : patterns.values()) {
            var po = p.primaryOutput();
            if (po == null) continue;
            var n = new Node(po.what(), p.id());
            nodes.add(n);
            byPattern.put(p.id(), n);
            producer.putIfAbsent(po.what(), n);
        }
        for (var p : patterns.values()) {
            var n = byPattern.get(p.id());
            if (n == null) continue;
            for (var o : p.outputs()) producer.putIfAbsent(o.what(), n);
        }
        for (var p : patterns.values()) {
            var to = byPattern.get(p.id());
            if (to == null) continue;
            Map<Node, Edge> merged = new IdentityHashMap<>();
            for (GenericStack in : p.inputs()) {
                Node from = producer.get(in.what());
                if (from == null || from == to) {
                    from = leaves.computeIfAbsent(in.what(), k -> {
                        var leaf = new Node(k, -1);
                        nodes.add(leaf);
                        return leaf;
                    });
                }
                var existing = merged.get(from);
                if (existing != null && existing.key.equals(in.what())) {
                    existing.perRun += in.amount();
                    continue;
                }
                var e = new Edge(from, to, in.what(), in.amount());
                merged.put(from, e);
                edges.add(e);
                from.out.add(e);
                to.in.add(e);
            }
        }

        root = null;
        if (last != null && last.finalOutput != null) {
            root = producer.get(last.finalOutput.what());
            if (root == null) {
                root = new Node(last.finalOutput.what(), -1);
                nodes.add(root);
            }
        }
        layout();
    }

    private void layout() {
        if (nodes.isEmpty()) return;
        for (var n : nodes) n.rank = -1;
        int cap = nodes.size() + 1;
        if (root != null) root.rank = 0;
        relax(cap);
        // nodes that don't lead to the root (byproducts etc.): seed them next to their consumers or at rank 1
        for (var n : nodes) {
            if (n.rank < 0 && n.out.stream().noneMatch(e -> e.to.rank >= 0)) {
                n.rank = 1;
            }
        }
        relax(cap);
        for (var n : nodes) if (n.rank < 0) n.rank = 1;

        int maxRank = nodes.stream().mapToInt(n -> n.rank).max().orElse(0);
        Map<Integer, List<Node>> byRank = new HashMap<>();
        for (var n : nodes) byRank.computeIfAbsent(n.rank, k -> new ArrayList<>()).add(n);

        Map<Node, Float> placedY = new IdentityHashMap<>();
        for (int r = 0; r <= maxRank; r++) {
            var list = byRank.getOrDefault(r, List.of());
            record Desired(Node n, float d) {}
            var ds = new ArrayList<Desired>();
            for (var n : list) {
                float sum = 0;
                int cnt = 0;
                for (var e : n.out) {
                    var y = placedY.get(e.to);
                    if (y != null) {
                        sum += y;
                        cnt++;
                    }
                }
                ds.add(new Desired(n, cnt > 0 ? sum / cnt : 0));
            }
            ds.sort(Comparator.comparingDouble(Desired::d));
            float prev = Float.NEGATIVE_INFINITY;
            float[] ys = new float[ds.size()];
            float shift = 0;
            for (int i = 0; i < ds.size(); i++) {
                ys[i] = Math.max(ds.get(i).d, prev + ROW_H);
                prev = ys[i];
                shift += ds.get(i).d - ys[i];
            }
            if (!ds.isEmpty()) shift /= ds.size();
            for (int i = 0; i < ds.size(); i++) {
                var n = ds.get(i).n;
                n.y = ys[i] + shift;
                n.x = (maxRank - n.rank) * COL_W;
                placedY.put(n, n.y);
            }
        }
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        for (var n : nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + NODE_W);
            maxY = Math.max(maxY, n.y + NODE_H + CHIP_H);
        }
    }

    private void relax(int cap) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < cap) {
            changed = false;
            for (var e : edges) {
                if (e.to.rank >= 0 && e.from.rank < e.to.rank + 1 && e.to.rank + 1 <= cap) {
                    e.from.rank = e.to.rank + 1;
                    changed = true;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // status helpers

    public Status status(Node n) {
        var ks = keys.get(n.key);
        if (n.isLeaf()) {
            if (n == root && last != null && last.remainingAmount > 0) return Status.SCHEDULED;
            if (ks != null && ks.active() > 0) return Status.CRAFTING;
            if (ks != null && ks.stored() > 0) return Status.AVAILABLE;
            if ((ks != null && ks.pending() > 0) || consumersHaveRunsLeft(n)) return Status.SCHEDULED;
            return Status.DONE;
        }
        var s = states.get(n.patternId);
        if (hasProblem(s)) return Status.STALLED;
        if (s != null && s.inMachines() > 0 && isQueued(s)) return Status.QUEUED;
        if ((s != null && s.inMachines() > 0) || (ks != null && ks.active() > 0)) return Status.CRAFTING;
        if (s != null && s.remainingRuns() > 0) return Status.SCHEDULED;
        if (ks != null && ks.stored() > 0) return Status.AVAILABLE;
        return Status.DONE;
    }

    @Nullable
    public PatternState state(Node n) {
        return n.isLeaf() ? null : states.get(n.patternId);
    }

    /** Output units still to dispatch for this recipe; leaves use the CPU's item totals. */
    public long pendingOutput(Node n) {
        if (n.isLeaf()) {
            var ks = keys.get(n.key);
            return ks == null ? 0 : ks.pending();
        }
        var pattern = patterns.get(n.patternId);
        var s = state(n);
        if (pattern == null || s == null) return 0;
        // Multiple recipes can produce the same item, so its CPU-wide pending count is not this step's.
        long perRun = 0;
        for (var output : pattern.outputs()) {
            if (n.key.equals(output.what())) perRun += output.amount();
        }
        return perRun * s.remainingRuns();
    }

    public long remainingNeed(Edge e) {
        var s = state(e.to);
        return s == null ? 0 : e.perRun * s.remainingRuns();
    }

    /** An edge is "live" while the consuming step still has work scheduled or out in machines. */
    public boolean edgeLive(Edge e) {
        var s = state(e.to);
        return s != null && (s.remainingRuns() > 0 || s.inMachines() > 0);
    }

    /** For raw materials: does a step that uses it still have runs AE2 hasn't handed out yet? */
    public boolean consumersHaveRunsLeft(Node leaf) {
        for (var e : leaf.out) {
            var s = state(e.to);
            if (s != null && s.remainingRuns() > 0) return true;
        }
        return false;
    }

    /** For raw materials: is a step that uses it still working on what it was given? */
    public boolean consumersStillWorking(Node leaf) {
        for (var e : leaf.out) {
            var s = state(e.to);
            if (s != null && s.inMachines() > 0) return true;
        }
        return false;
    }

    /**
     * The step's items are in its machine(s), but every machine that tells us what it's running is running a
     * different step's recipe - so this step is waiting its turn, not being processed.
     */
    public boolean isQueued(PatternState s) {
        boolean anyKnown = false;
        for (var m : s.machines()) {
            if (m.target() == SnapshotData.Target.THIS_STEP) return false;
            if (m.target() == SnapshotData.Target.UNKNOWN) return false;
            anyKnown = true; // OTHER
        }
        return anyKnown;
    }

    /** A step has a problem only if one of its machines says so AND the step still has work. */
    public boolean hasProblem(@Nullable PatternState s) {
        return s != null && (s.inMachines() > 0 || s.remainingRuns() > 0) && s.problem() != null;
    }

    public boolean anyProblem() {
        for (var s : states.values()) if (hasProblem(s)) return true;
        return false;
    }
}
