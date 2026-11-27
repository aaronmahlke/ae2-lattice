package dev.ae2lattice.server;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * What Lattice has observed about one pattern in one job: which machines it went to, and when output last came
 * back. Outstanding outputs are attributed by JobTrack; scheduled/stored counts come from AE2 and machine
 * state from the machine itself.
 */
public final class PatternTrack {
    public final int id;
    public final IPatternDetails details;
    final Map<String, MachineSlot> machines = new LinkedHashMap<>();
    long lastScanTick = Long.MIN_VALUE / 2;
    /** Last time output came back (or when the current wait started). Measured, not estimated. */
    long lastOutputTick;

    static final class MachineSlot {
        final BlockPos pos;
        final ResourceKey<Level> dim;
        final ItemStack icon;
        final Component name;
        WeakReference<ICraftingProvider> provider;
        /** true once AE2 actually pushed this pattern to it (not just "could") */
        boolean used;

        MachineSlot(BlockPos pos, ResourceKey<Level> dim, ItemStack icon, Component name, ICraftingProvider provider) {
            this.pos = pos;
            this.dim = dim;
            this.icon = icon;
            this.name = name;
            this.provider = new WeakReference<>(provider);
        }
    }

    PatternTrack(int id, IPatternDetails details, long now) {
        this.id = id;
        this.details = details;
        this.lastOutputTick = now;
    }

    void returned(long now) {
        lastOutputTick = now;
    }

    void addMachine(MachineResolver.Target t, ICraftingProvider provider, boolean used) {
        String k = t.dim().location() + "@" + t.pos().asLong();
        var slot = machines.get(k);
        if (slot == null) {
            if (machines.size() >= 8) return;
            slot = new MachineSlot(t.pos(), t.dim(), t.icon(), t.name(), provider);
            machines.put(k, slot);
        } else {
            slot.provider = new WeakReference<>(provider);
        }
        slot.used |= used;
    }
}
