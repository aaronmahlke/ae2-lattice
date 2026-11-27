package dev.ae2lattice.server;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingCPUMenu;
import dev.ae2lattice.Lattice;
import dev.ae2lattice.LatticeConfig;
import dev.ae2lattice.mixin.CraftingCPUMenuAccessor;
import dev.ae2lattice.net.LatticeNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class ServerHooks {
    private static long tick;

    /** Players that currently have the tree view open. */
    private static final Map<UUID, Subscription> SUBS = new HashMap<>();

    static final class Subscription {
        final String playerName;
        final boolean supportsUnknownCounts;
        String lastLogged = "";

        Subscription(String playerName, boolean supportsUnknownCounts) {
            this.playerName = playerName;
            this.supportsUnknownCounts = supportsUnknownCounts;
        }

        /** Logs only when the state changes, so this never spams. */
        void logState(String state) {
            if (!state.equals(lastLogged)) {
                lastLogged = state;
                Lattice.LOG.debug("Lattice: tree view for {}: {}", playerName, state);
            }
        }

        WeakReference<CraftingCPUCluster> cpu = new WeakReference<>(null);
        long jobUid;
        int structureVersion = -1;
        long eventSeq;
        long lastEmptySentTick = Long.MIN_VALUE / 2;
    }

    public static long tick() {
        return tick;
    }

    public static void setSubscribed(ServerPlayer player, boolean on) {
        if (on && LatticeNetwork.clientHasLattice(player)) {
            SUBS.put(player.getUUID(), new Subscription(player.getGameProfile().getName(),
                    LatticeNetwork.clientSupportsUnknownCounts(player)));
            Lattice.LOG.debug("Lattice: {} opened the tree view (menu: {})", player.getGameProfile().getName(),
                    player.containerMenu.getClass().getSimpleName());
        } else {
            SUBS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tick++;
        if (SUBS.isEmpty()) return;
        int interval;
        try {
            interval = LatticeConfig.UPDATE_INTERVAL_TICKS.get();
        } catch (IllegalStateException notLoaded) {
            interval = 4;
        }
        if (tick % interval != 0) return;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var it = SUBS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (!(player.containerMenu instanceof CraftingCPUMenu menu)) {
                entry.getValue().logState("not in a crafting status menu ("
                        + player.containerMenu.getClass().getSimpleName() + ")");
                continue;
            }
            try {
                var cpu = ((CraftingCPUMenuAccessor) (Object) menu).ae2lattice$getCpu();
                var data = SnapshotBuilder.build(cpu, entry.getValue());
                if (data != null) LatticeNetwork.sendTo(player, data);
            } catch (Throwable t) {
                Lattice.LOG.warn("Lattice: failed to build snapshot", t);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        SUBS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent e) {
        SUBS.clear();
        LatticeTracker.clear();
    }

    private ServerHooks() {}
}
