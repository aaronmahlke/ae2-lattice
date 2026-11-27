package dev.ae2lattice.server;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.ExecutingCraftingJob;
import dev.ae2lattice.net.SnapshotData.Event;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

/** Lattice's record of one running job on one CPU. */
public final class JobTrack {
    final WeakReference<ExecutingCraftingJob> job;
    public final long uid = ThreadLocalRandom.current().nextLong();
    final Map<IPatternDetails, PatternTrack> patterns = new LinkedHashMap<>();
    private final Map<Integer, PatternTrack> byId = new HashMap<>();
    final InFlightTracker inflight = new InFlightTracker();
    private int nextId;
    int structureVersion;

    private record SeqEvent(long seq, long tick, Event event) {}

    private final ArrayDeque<SeqEvent> events = new ArrayDeque<>();
    private long lastSeq;

    JobTrack(ExecutingCraftingJob job) {
        this.job = new WeakReference<>(job);
    }

    PatternTrack track(IPatternDetails d) {
        var pt = patterns.get(d);
        if (pt == null) {
            pt = new PatternTrack(nextId++, d, ServerHooks.tick());
            patterns.put(d, pt);
            byId.put(pt.id, pt);
            structureVersion++;
        }
        return pt;
    }

    void onPush(IPatternDetails d, ICraftingProvider provider) {
        long now = ServerHooks.tick();
        var pt = track(d);
        var primary = d.getPrimaryOutput();
        if (primary != null && inflight.pending(pt.id, primary.what()) == 0) pt.lastOutputTick = now;
        for (var output : d.getOutputs()) {
            if (output != null) inflight.add(pt.id, output.what(), output.amount());
        }
        for (var t : MachineResolver.resolve(provider)) {
            pt.addMachine(t, provider, true);
        }
        addEvent(new Event(pt.id, true, null, 1), now);
    }

    void onReturn(AEKey what, long amount) {
        long now = ServerHooks.tick();
        for (var claim : inflight.returned(what, amount)) {
            var pt = byId.get(claim.patternId());
            if (pt != null) pt.returned(now);
            addEvent(new Event(claim.patternId(), false, what, claim.amount()), now);
        }
    }

    void reconcile(KeyCounter waiting) {
        var amounts = new HashMap<AEKey, Long>();
        for (var entry : waiting) amounts.put(entry.getKey(), entry.getLongValue());
        inflight.reconcile(amounts);
    }

    /** -1 means the CPU has output awaiting attribution from before Lattice recorded the job. */
    long inMachines(PatternTrack pt) {
        var primary = pt.details.getPrimaryOutput();
        if (primary == null) return 0;
        if (inflight.pending(InFlightTracker.UNATTRIBUTED, primary.what()) > 0) return -1;
        return inflight.pending(pt.id, primary.what());
    }

    CompoundTag writeTag() {
        var data = new CompoundTag();
        data.putInt("version", 1);
        var savedPatterns = new ListTag();
        for (var pt : patterns.values()) {
            var entry = new CompoundTag();
            entry.putInt("id", pt.id);
            entry.put("definition", pt.details.getDefinition().toTag());
            savedPatterns.add(entry);
        }
        data.put("patterns", savedPatterns);
        var savedClaims = new ListTag();
        for (var claim : inflight.claims()) {
            var entry = new CompoundTag();
            entry.put("key", claim.key().toTagGeneric());
            entry.putInt("pattern", claim.patternId());
            entry.putLong("amount", claim.amount());
            savedClaims.add(entry);
        }
        data.put("pending", savedClaims);
        return data;
    }

    void readTag(CompoundTag data, Level level) {
        if (data.getInt("version") != 1) return;
        var ids = new HashMap<Integer, Integer>();
        var savedPatterns = data.getList("patterns", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedPatterns.size(); i++) {
            var entry = savedPatterns.getCompound(i);
            var definition = AEItemKey.fromTag(entry.getCompound("definition"));
            var pattern = definition == null ? null : PatternDetailsHelper.decodePattern(definition, level);
            if (pattern != null) ids.put(entry.getInt("id"), track(pattern).id);
        }
        var restored = new InFlightTracker();
        var savedClaims = data.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedClaims.size(); i++) {
            var entry = savedClaims.getCompound(i);
            var key = AEKey.fromTagGeneric(entry.getCompound("key"));
            if (key != null) restored.add(ids.getOrDefault(entry.getInt("pattern"), InFlightTracker.UNATTRIBUTED),
                    key, entry.getLong("amount"));
        }
        inflight.clear();
        for (var claim : restored.claims()) inflight.add(claim.patternId(), claim.key(), claim.amount());
    }

    private void addEvent(Event e, long now) {
        var last = events.peekLast();
        if (last != null && last.tick == now && last.event.patternId() == e.patternId()
                && last.event.push() == e.push() && java.util.Objects.equals(last.event.key(), e.key())) {
            events.pollLast();
            e = new Event(e.patternId(), e.push(), e.key(), last.event.amount() + e.amount());
            events.addLast(new SeqEvent(last.seq, now, e));
            return;
        }
        events.addLast(new SeqEvent(++lastSeq, now, e));
        while (events.size() > 1024 || (!events.isEmpty() && now - events.peekFirst().tick > 200)) {
            events.pollFirst();
        }
    }

    long lastSeq() {
        return lastSeq;
    }

    List<Event> eventsSince(long seq) {
        var out = new ArrayList<Event>();
        for (var e : events) if (e.seq > seq) out.add(e.event);
        return out;
    }
}
