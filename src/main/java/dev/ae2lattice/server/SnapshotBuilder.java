package dev.ae2lattice.server;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.ItemStack;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import dev.ae2lattice.mixin.CraftingCpuLogicAccessor;
import dev.ae2lattice.mixin.ExecutingCraftingJobAccessor;
import dev.ae2lattice.mixin.TaskProgressAccessor;
import dev.ae2lattice.net.SnapshotData;
import dev.ae2lattice.net.SnapshotData.KeyState;
import dev.ae2lattice.net.SnapshotData.MachineInfo;
import dev.ae2lattice.net.SnapshotData.MachineState;
import dev.ae2lattice.net.SnapshotData.PatternInfo;
import dev.ae2lattice.net.SnapshotData.PatternState;

final class SnapshotBuilder {

    @Nullable
    static SnapshotData build(@Nullable CraftingCPUCluster cpu, ServerHooks.Subscription sub) {
        long now = ServerHooks.tick();
        var job = cpu == null ? null : ((CraftingCpuLogicAccessor) (Object) cpu.craftingLogic).ae2lattice$getJob();
        if (cpu == null || job == null) {
            sub.cpu = new WeakReference<>(cpu);
            sub.jobUid = 0;
            sub.structureVersion = -1;
            // "no job" only needs to be repeated occasionally
            if (now - sub.lastEmptySentTick < 20) return null;
            sub.lastEmptySentTick = now;
            var empty = new SnapshotData();
            empty.emptyReason = cpu == null ? SnapshotData.EMPTY_NO_CPU : SnapshotData.EMPTY_NO_JOB;
            var fin = cpu == null ? null : LatticeTracker.lastFinished(cpu);
            if (fin != null && now - fin.tick() < 20 * 60 * 30) { // remember it for 30 minutes
                empty.finished = new SnapshotData.Finished(fin.id(), fin.success(), fin.output(), fin.remaining(),
                        fin.elapsedNanos(), (int) Math.min(Integer.MAX_VALUE, now - fin.tick()), fin.steps(),
                        fin.machines());
            }
            sub.logState(cpu == null ? "no CPU selected" : "CPU has no job");
            return empty;
        }
        sub.lastEmptySentTick = Long.MIN_VALUE / 2;

        var logic = cpu.craftingLogic;
        var ja = (ExecutingCraftingJobAccessor) (Object) job;
        var jt = LatticeTracker.get(cpu);
        if (jt == null) return null;

        var tasks = ja.ae2lattice$getTasks();
        for (var d : tasks.keySet()) jt.track(d);
        jt.reconcile(ja.ae2lattice$getWaitingFor().list);

        var d = new SnapshotData();
        d.hasJob = true;
        d.jobUid = jt.uid;
        d.structureVersion = jt.structureVersion;
        d.finalOutput = ja.ae2lattice$getFinalOutput();
        d.remainingAmount = ja.ae2lattice$getRemainingAmount();
        var tt = logic.getElapsedTimeTracker();
        d.elapsedNanos = tt.getElapsedTime();
        d.startItems = tt.getStartItemCount();
        d.remainingItems = tt.getRemainingItemCount();

        boolean newView = sub.cpu.get() != cpu || sub.jobUid != jt.uid;
        if (newView || sub.structureVersion != jt.structureVersion) {
            var structure = new ArrayList<PatternInfo>(jt.patterns.size());
            for (var pt : jt.patterns.values()) structure.add(describe(pt));
            d.structure = structure;
            sub.structureVersion = jt.structureVersion;
        }
        if (newView) {
            sub.cpu = new WeakReference<>(cpu);
            sub.jobUid = jt.uid;
            sub.eventSeq = jt.lastSeq(); // don't replay history on open
        }

        var server = cpu.getLevel() != null ? cpu.getLevel().getServer() : null;
        Set<AEKey> keys = new LinkedHashSet<>();
        for (var pt : jt.patterns.values()) {
            var tp = tasks.get(pt.details);
            long remainingRuns = tp == null ? 0 : ((TaskProgressAccessor) tp).ae2lattice$getValue();

            // Per-pattern dispatch/return accounting; never copy the shared item total onto every recipe.
            var primary = pt.details.getPrimaryOutput();
            long inMachines = jt.inMachines(pt);
            if (inMachines == 0) {
                pt.lastOutputTick = now; // the "no output since" clock only runs while something is out
            }

            if ((pt.machines.isEmpty() && now - pt.lastScanTick > 100) || now - pt.lastScanTick > 1200) {
                pt.lastScanTick = now;
                scanProviders(cpu, pt);
            }

            // Which CPU does AE2 hand this item's outputs to next? (its own order: first CPU still waiting for it)
            var servedBy = primary == null || inMachines == 0 ? null : firstWaitingCpu(cpu, primary.what());
            boolean otherCpuFirst = servedBy != null && servedBy != cpu;

            // Ask each machine about itself. Nothing here is estimated.
            var machines = new ArrayList<MachineInfo>(pt.machines.size());
            for (var m : pt.machines.values()) {
                var r = MachineProbe.probe(server, m.dim, m.pos);
                var state = r.state();
                var prov = m.provider.get();
                // The pattern provider holding items it couldn't insert is a fact about the provider, not a guess.
                if ((state == MachineState.IDLE || state == MachineState.UNKNOWN) && prov != null && prov.isBusy()) {
                    state = MachineState.INPUT_FULL;
                }
                var target = targetOf(pt, r);
                // The machine may be running exactly this recipe - but if AE2 gives the result to another CPU
                // first, it's not working for *this* CPU.
                if (inMachines == 0 || otherCpuFirst) target = SnapshotData.Target.OTHER;
                var busyWith = ItemStack.EMPTY;
                if (target == SnapshotData.Target.OTHER) {
                    if (!r.making().isEmpty()) busyWith = new ItemStack(r.making().iterator().next());
                    else if (r.consuming() != null) busyWith = new ItemStack(r.consuming());
                }
                // Slot contents only for steps that still have work (keeps packets small on big jobs).
                var inv = (inMachines != 0 || remainingRuns > 0)
                        ? InventoryReader.read(server, m.dim, m.pos) : List.<SnapshotData.InvGroup>of();
                machines.add(new MachineInfo(m.pos, m.dim.location(), m.icon, m.name, state,
                        r.progress(), r.maxProgress(), r.reason(), target, busyWith, inv,
                        (inMachines != 0 || remainingRuns > 0) ? r.recipe() : List.<SnapshotData.InvGroup>of(),
                        r.recipeId()));
            }

            long since = inMachines > 0 ? now - pt.lastOutputTick : 0;
            d.states.add(new PatternState(pt.id, remainingRuns, inMachines,
                    (int) Math.min(Integer.MAX_VALUE, since), machines,
                    otherCpuFirst ? cpuName(servedBy) : null)
                    .forClient(sub.supportsUnknownCounts, primary == null ? 0 : logic.getWaitingFor(primary.what())));

            for (var o : pt.details.getOutputs()) if (o != null) keys.add(o.what());
            for (var in : pt.details.getInputs()) {
                var poss = in.getPossibleInputs();
                if (poss.length > 0) keys.add(poss[0].what());
            }
        }

        var all = new KeyCounter();
        logic.getAllItems(all);
        for (var e : all) keys.add(e.getKey());
        if (d.finalOutput != null) keys.add(d.finalOutput.what());

        for (var k : keys) {
            d.keys.add(new KeyState(k, logic.getStored(k), logic.getWaitingFor(k), logic.getPendingOutputs(k)));
        }

        d.events = jt.eventsSince(sub.eventSeq);
        sub.eventSeq = jt.lastSeq();
        sub.logState("job running, " + jt.patterns.size() + " pattern(s), output "
                + (d.finalOutput != null ? d.finalOutput.what().getId() : "?"));
        return d;
    }

    /** Mirrors AE2's CraftingService.insertIntoCpus: returned items go to the first CPU (in its set order) waiting. */
    @org.jetbrains.annotations.Nullable
    private static CraftingCPUCluster firstWaitingCpu(CraftingCPUCluster self, AEKey key) {
        var grid = self.getGrid();
        if (grid == null || !(grid.getCraftingService() instanceof CraftingService cs)) return null;
        for (var c : ((dev.ae2lattice.mixin.CraftingServiceAccessor) (Object) cs).ae2lattice$getCpus()) {
            if (c.craftingLogic.getWaitingFor(key) > 0) return c;
        }
        return null;
    }

    private static net.minecraft.network.chat.Component cpuName(CraftingCPUCluster c) {
        var n = c.getName();
        return n != null ? n : net.minecraft.network.chat.Component.literal("another CPU");
    }

    /**
     * Is the machine's current work this step's? Compares the machine's own current recipe outputs (GregTech) or
     * current input item (furnace) with this pattern. UNKNOWN if the machine doesn't tell us.
     */
    private static SnapshotData.Target targetOf(PatternTrack pt, MachineProbe.Reading r) {
        if (!r.making().isEmpty()) {
            for (var o : pt.details.getOutputs()) {
                if (o != null && o.what() instanceof AEItemKey ik && r.making().contains(ik.getItem())) {
                    return SnapshotData.Target.THIS_STEP;
                }
            }
            return SnapshotData.Target.OTHER;
        }
        if (r.consuming() != null) {
            for (var in : pt.details.getInputs()) {
                for (var poss : in.getPossibleInputs()) {
                    if (poss.what() instanceof AEItemKey ik && ik.getItem() == r.consuming()) {
                        return SnapshotData.Target.THIS_STEP;
                    }
                }
            }
            return SnapshotData.Target.OTHER;
        }
        return SnapshotData.Target.UNKNOWN;
    }

    private static PatternInfo describe(PatternTrack pt) {
        List<GenericStack> outs = new ArrayList<>();
        for (var o : pt.details.getOutputs()) if (o != null) outs.add(o);
        List<GenericStack> ins = new ArrayList<>();
        for (var in : pt.details.getInputs()) {
            var poss = in.getPossibleInputs();
            if (poss.length == 0) continue;
            ins.add(new GenericStack(poss[0].what(), poss[0].amount() * in.getMultiplier()));
        }
        return new PatternInfo(pt.id, outs, ins);
    }

    /** Before a pattern has ever been pushed we can still show where it *would* go. */
    private static void scanProviders(CraftingCPUCluster cpu, PatternTrack pt) {
        var grid = cpu.getGrid();
        if (grid == null || !(grid.getCraftingService() instanceof CraftingService cs)) return;
        int n = 0;
        for (var provider : cs.getProviders(pt.details)) {
            for (var t : MachineResolver.resolve(provider)) pt.addMachine(t, provider, false);
            if (++n >= 4) break;
        }
    }

    private SnapshotBuilder() {}
}
