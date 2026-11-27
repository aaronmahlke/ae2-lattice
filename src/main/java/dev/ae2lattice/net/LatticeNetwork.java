package dev.ae2lattice.net;

import java.util.function.Supplier;

import dev.ae2lattice.Lattice;
import dev.ae2lattice.server.ServerHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class LatticeNetwork {
    /** Optional on both sides: clients without Lattice can join servers with it and vice versa. */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Lattice.MOD_ID, "main"),
            () -> NetworkCompatibility.BASE_VERSION,
            NetworkCompatibility.ACCEPT_BASE,
            NetworkCompatibility.ACCEPT_BASE);

    /** Presence-only capability: this peer understands -1 as an unknown per-recipe count. No packets. */
    private static final SimpleChannel UNKNOWN_COUNTS_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Lattice.MOD_ID, "unknown_counts"),
            () -> NetworkCompatibility.UNKNOWN_COUNTS_VERSION,
            NetworkCompatibility.ACCEPT_UNKNOWN_COUNTS,
            NetworkCompatibility.ACCEPT_UNKNOWN_COUNTS);

    public static void register() {
        CHANNEL.messageBuilder(SubscribePacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SubscribePacket::write)
                .decoder(SubscribePacket::read)
                .consumerMainThread(SubscribePacket::handle)
                .add();
        CHANNEL.messageBuilder(SnapshotPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SnapshotPacket::write)
                .decoder(SnapshotPacket::read)
                .consumerMainThread(SnapshotPacket::handle)
                .add();
    }

    public static boolean clientHasLattice(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static boolean clientSupportsUnknownCounts(ServerPlayer player) {
        return UNKNOWN_COUNTS_CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static void sendTo(ServerPlayer player, SnapshotData data) {
        if (clientHasLattice(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SnapshotPacket(data));
        }
    }

    // ---------------------------------------------------------------------------------------------

    public record SubscribePacket(boolean subscribe) {
        void write(FriendlyByteBuf b) {
            b.writeBoolean(subscribe);
        }

        static SubscribePacket read(FriendlyByteBuf b) {
            return new SubscribePacket(b.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            var player = ctx.get().getSender();
            if (player != null) {
                ServerHooks.setSubscribed(player, subscribe);
            }
        }
    }

    public record SnapshotPacket(SnapshotData data) {
        void write(FriendlyByteBuf b) {
            data.write(b);
        }

        static SnapshotPacket read(FriendlyByteBuf b) {
            return new SnapshotPacket(SnapshotData.read(b));
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> dev.ae2lattice.client.ClientState.accept(data));
        }
    }

    private LatticeNetwork() {}
}
