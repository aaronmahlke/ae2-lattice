package dev.ae2lattice.net;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Everything the client needs to draw one CPU's job. Structure (patterns) is only included when it changed.
 */
public final class SnapshotData {

    public record PatternInfo(int id, List<GenericStack> outputs, List<GenericStack> inputs) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            writeStacks(b, outputs);
            writeStacks(b, inputs);
        }

        static PatternInfo read(FriendlyByteBuf b) {
            return new PatternInfo(b.readVarInt(), readStacks(b), readStacks(b));
        }

        @Nullable
        public GenericStack primaryOutput() {
            return outputs.isEmpty() ? null : outputs.get(0);
        }
    }

    /** What the machine itself reports. Nothing here is estimated. */
    public enum MachineState {
        /** GT recipe running / furnace cooking */
        WORKING(false),
        IDLE(false),
        /** GT says it's waiting; see reason (e.g. not enough energy) */
        WAITING(true),
        /** switched off (GT soft mallet / disabled) */
        SUSPENDED(true),
        NO_FUEL(true),
        OUTPUT_FULL(true),
        /** pattern provider has items it couldn't insert yet (machine input is full) */
        INPUT_FULL(false),
        UNLOADED(false),
        /** we can't read this kind of machine */
        UNKNOWN(false);

        public final boolean problem;

        MachineState(boolean problem) {
            this.problem = problem;
        }
    }

    /**
     * @param progress    current progress in ticks as reported by the machine (0 if it doesn't report it)
     * @param maxProgress total ticks for the current recipe/item (0 if it doesn't report it)
     * @param reason      the machine's own explanation, if it gives one (GregTech waiting reason)
     */
    /**
     * One group of a machine's slots, read straight from the machine (never modified).
     * @param kind       "in", "fuel", "out" or "slots" (when the machine doesn't say which is which)
     * @param items      non-empty stacks (capped)
     * @param emptySlots how many empty item slots the group has
     */
    public record InvGroup(String kind, List<ItemStack> items, int emptySlots, List<FluidStack> fluids,
            List<Integer> fluidCapacity) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(kind, 16);
            b.writeVarInt(items.size());
            for (var s : items) b.writeItem(s);
            b.writeVarInt(emptySlots);
            b.writeVarInt(fluids.size());
            for (int i = 0; i < fluids.size(); i++) {
                fluids.get(i).writeToPacket(b);
                b.writeVarInt(fluidCapacity.get(i));
            }
        }

        static InvGroup read(FriendlyByteBuf b) {
            String kind = b.readUtf(16);
            int n = b.readVarInt();
            var items = new ArrayList<ItemStack>(n);
            for (int i = 0; i < n; i++) items.add(b.readItem());
            int empty = b.readVarInt();
            int f = b.readVarInt();
            var fluids = new ArrayList<FluidStack>(f);
            var caps = new ArrayList<Integer>(f);
            for (int i = 0; i < f; i++) {
                fluids.add(FluidStack.readFromPacket(b));
                caps.add(b.readVarInt());
            }
            return new InvGroup(kind, items, empty, fluids, caps);
        }
    }

    /** Is the machine's current work for this step? Decided from the machine's own current recipe/input. */
    public enum Target { UNKNOWN, THIS_STEP, OTHER }

    /**
     * @param target  whether what the machine is doing right now belongs to this step
     * @param busyWith what the machine is making right now if it's NOT this step (empty otherwise)
     */
    public record MachineInfo(BlockPos pos, ResourceLocation dimension, ItemStack icon, Component name,
            MachineState state, int progress, int maxProgress, @Nullable Component reason,
            Target target, ItemStack busyWith, List<InvGroup> inventory, List<InvGroup> recipe,
            @Nullable ResourceLocation recipeId) {
        void write(FriendlyByteBuf b) {
            b.writeBoolean(recipeId != null);
            if (recipeId != null) b.writeResourceLocation(recipeId);
            b.writeVarInt(inventory.size());
            for (var g : inventory) g.write(b);
            b.writeVarInt(recipe.size());
            for (var g : recipe) g.write(b);
            b.writeBlockPos(pos);
            b.writeResourceLocation(dimension);
            b.writeItem(icon);
            b.writeComponent(name);
            b.writeEnum(state);
            b.writeVarInt(progress);
            b.writeVarInt(maxProgress);
            b.writeBoolean(reason != null);
            if (reason != null) b.writeComponent(reason);
            b.writeEnum(target);
            b.writeItem(busyWith);
        }

        static MachineInfo read(FriendlyByteBuf b) {
            ResourceLocation rid = b.readBoolean() ? b.readResourceLocation() : null;
            int ng = b.readVarInt();
            var inv = new ArrayList<InvGroup>(ng);
            for (int i = 0; i < ng; i++) inv.add(InvGroup.read(b));
            int nr = b.readVarInt();
            var rec = new ArrayList<InvGroup>(nr);
            for (int i = 0; i < nr; i++) rec.add(InvGroup.read(b));
            return new MachineInfo(b.readBlockPos(), b.readResourceLocation(), b.readItem(), b.readComponent(),
                    b.readEnum(MachineState.class), b.readVarInt(), b.readVarInt(),
                    b.readBoolean() ? b.readComponent() : null, b.readEnum(Target.class), b.readItem(), inv, rec, rid);
        }

        /** Machine reports progress (ticks, or a percentage when maxProgress is negative, e.g. AE2 assemblers). */
        public boolean hasProgress() {
            return maxProgress != 0;
        }

        public boolean isPercent() {
            return maxProgress < 0;
        }

        public float fraction() {
            if (maxProgress == 0) return 0;
            return Math.min(1f, maxProgress < 0 ? progress / (float) -maxProgress : progress / (float) maxProgress);
        }

        /** True if this machine's current work is this step's (known), or unknown but plausible. */
        public boolean workingOnThisStep(long stepInMachines) {
            return switch (target) {
                case THIS_STEP -> stepInMachines != 0;
                case OTHER -> false;
                case UNKNOWN -> stepInMachines > 0;
            };
        }
    }

    /**
     * @param remainingRuns      runs AE2 still has to push (AE2's own task list)
     * @param inMachines         outstanding output units attributed to this recipe; -1 if prior history is unknown
     * @param sinceOutputTicks   ticks since this step last got output back while something was out (measured)
     */
    /**
     * @param servedBy if set, AE2 hands this item's outputs to that other CPU first (AE2's own CPU order),
     *                 so this CPU's step is waiting behind it
     */
    public record PatternState(int id, long remainingRuns, long inMachines, int sinceOutputTicks,
            List<MachineInfo> machines, @Nullable Component servedBy) {
        /** Older clients expect a nonnegative item count, so retain their old total when history is missing. */
        public PatternState forClient(boolean supportsUnknownCounts, long legacyItemTotal) {
            if (inMachines >= 0 || supportsUnknownCounts) return this;
            return new PatternState(id, remainingRuns, Math.max(0, legacyItemTotal), sinceOutputTicks,
                    machines, servedBy);
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeVarLong(remainingRuns);
            b.writeVarLong(inMachines);
            b.writeVarInt(sinceOutputTicks);
            b.writeVarInt(machines.size());
            for (var m : machines) m.write(b);
            b.writeBoolean(servedBy != null);
            if (servedBy != null) b.writeComponent(servedBy);
        }

        static PatternState read(FriendlyByteBuf b) {
            int id = b.readVarInt();
            long rr = b.readVarLong();
            long inf = b.readVarLong();
            int since = b.readVarInt();
            int n = b.readVarInt();
            var ms = new ArrayList<MachineInfo>(n);
            for (int i = 0; i < n; i++) ms.add(MachineInfo.read(b));
            Component served = b.readBoolean() ? b.readComponent() : null;
            return new PatternState(id, rr, inf, since, ms, served);
        }

        /** First machine reporting a problem, if any. */
        @Nullable
        public MachineInfo problem() {
            for (var m : machines) if (m.state().problem) return m;
            return null;
        }

        /** First machine that reports real progress on this step. */
        @Nullable
        public MachineInfo progressing() {
            for (var m : machines) {
                if (m.state() == MachineState.WORKING && m.hasProgress() && m.workingOnThisStep(inMachines)) return m;
            }
            return null;
        }
    }

    public record KeyState(AEKey key, long stored, long active, long pending) {
        void write(FriendlyByteBuf b) {
            AEKey.writeKey(b, key);
            b.writeVarLong(stored);
            b.writeVarLong(active);
            b.writeVarLong(pending);
        }

        static KeyState read(FriendlyByteBuf b) {
            return new KeyState(AEKey.readKey(b), b.readVarLong(), b.readVarLong(), b.readVarLong());
        }
    }

    /** push: amount = number of pattern runs pushed, key = null. return: key/amount returned to the CPU. */
    public record Event(int patternId, boolean push, @Nullable AEKey key, long amount) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(patternId + 1);
            b.writeBoolean(push);
            b.writeBoolean(key != null);
            if (key != null) AEKey.writeKey(b, key);
            b.writeVarLong(amount);
        }

        static Event read(FriendlyByteBuf b) {
            int id = b.readVarInt() - 1;
            boolean push = b.readBoolean();
            AEKey key = b.readBoolean() ? AEKey.readKey(b) : null;
            return new Event(id, push, key, b.readVarLong());
        }
    }

    public boolean hasJob;
    public long jobUid;
    public int structureVersion;
    @Nullable
    public GenericStack finalOutput;
    public long remainingAmount;
    public long elapsedNanos;
    public long startItems;
    public long remainingItems;
    public boolean suspended;
    /** null = unchanged since the last snapshot this client received */
    @Nullable
    public List<PatternInfo> structure;
    public List<PatternState> states = new ArrayList<>();
    public List<KeyState> keys = new ArrayList<>();
    public List<Event> events = new ArrayList<>();

    public static final byte EMPTY_NO_CPU = 1, EMPTY_NO_JOB = 2;
    /** Why there is no job (only meaningful when hasJob == false). */
    public byte emptyReason;

    /** How the last job on this CPU ended (as AE2 reported it), if recently. */
    public record UsedMachine(ItemStack icon, Component name, BlockPos pos, ResourceLocation dimension) {}

    public record Finished(long id, boolean success, GenericStack output, long remaining, long elapsedNanos,
            int ageTicks, int steps, List<UsedMachine> machines) {}

    @Nullable
    public Finished finished;

    public void write(FriendlyByteBuf b) {
        b.writeBoolean(hasJob);
        if (!hasJob) {
            b.writeByte(emptyReason);
            b.writeBoolean(finished != null);
            if (finished != null) {
                b.writeLong(finished.id());
                b.writeBoolean(finished.success());
                GenericStack.writeBuffer(finished.output(), b);
                b.writeVarLong(finished.remaining());
                b.writeVarLong(finished.elapsedNanos());
                b.writeVarInt(finished.ageTicks());
                b.writeVarInt(finished.steps());
                b.writeVarInt(finished.machines().size());
                for (var m : finished.machines()) {
                    b.writeItem(m.icon());
                    b.writeComponent(m.name());
                    b.writeBlockPos(m.pos());
                    b.writeResourceLocation(m.dimension());
                }
            }
            return;
        }
        b.writeLong(jobUid);
        b.writeVarInt(structureVersion);
        GenericStack.writeBuffer(finalOutput, b);
        b.writeVarLong(remainingAmount);
        b.writeVarLong(elapsedNanos);
        b.writeVarLong(startItems);
        b.writeVarLong(remainingItems);
        b.writeBoolean(suspended);
        b.writeBoolean(structure != null);
        if (structure != null) {
            b.writeVarInt(structure.size());
            for (var p : structure) p.write(b);
        }
        b.writeVarInt(states.size());
        for (var s : states) s.write(b);
        b.writeVarInt(keys.size());
        for (var k : keys) k.write(b);
        b.writeVarInt(events.size());
        for (var e : events) e.write(b);
    }

    public static SnapshotData read(FriendlyByteBuf b) {
        var d = new SnapshotData();
        d.hasJob = b.readBoolean();
        if (!d.hasJob) {
            d.emptyReason = b.readByte();
            if (b.readBoolean()) {
                long id = b.readLong();
                boolean ok = b.readBoolean();
                var out = GenericStack.readBuffer(b);
                long rem = b.readVarLong(), el = b.readVarLong();
                int age = b.readVarInt(), steps = b.readVarInt(), n = b.readVarInt();
                var ms = new ArrayList<UsedMachine>(n);
                for (int i = 0; i < n; i++) {
                    ms.add(new UsedMachine(b.readItem(), b.readComponent(), b.readBlockPos(), b.readResourceLocation()));
                }
                d.finished = new Finished(id, ok, out, rem, el, age, steps, ms);
            }
            return d;
        }
        d.jobUid = b.readLong();
        d.structureVersion = b.readVarInt();
        d.finalOutput = GenericStack.readBuffer(b);
        d.remainingAmount = b.readVarLong();
        d.elapsedNanos = b.readVarLong();
        d.startItems = b.readVarLong();
        d.remainingItems = b.readVarLong();
        d.suspended = b.readBoolean();
        if (b.readBoolean()) {
            int n = b.readVarInt();
            d.structure = new ArrayList<>(n);
            for (int i = 0; i < n; i++) d.structure.add(PatternInfo.read(b));
        }
        int n = b.readVarInt();
        for (int i = 0; i < n; i++) d.states.add(PatternState.read(b));
        n = b.readVarInt();
        for (int i = 0; i < n; i++) d.keys.add(KeyState.read(b));
        n = b.readVarInt();
        for (int i = 0; i < n; i++) d.events.add(Event.read(b));
        return d;
    }

    static void writeStacks(FriendlyByteBuf b, List<GenericStack> list) {
        b.writeVarInt(list.size());
        for (var s : list) GenericStack.writeBuffer(s, b);
    }

    static List<GenericStack> readStacks(FriendlyByteBuf b) {
        int n = b.readVarInt();
        var l = new ArrayList<GenericStack>(n);
        for (int i = 0; i < n; i++) {
            var s = GenericStack.readBuffer(b);
            if (s != null) l.add(s);
        }
        return l;
    }
}
