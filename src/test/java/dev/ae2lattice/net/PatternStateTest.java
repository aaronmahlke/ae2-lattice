package dev.ae2lattice.net;

import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PatternStateTest {
    @Test
    public void existingPacketFormatRoundTripsKnownAndUnknownCounts() {
        for (long count : new long[] {-1, 0, 12, 3_000_000_000L}) {
            var state = new SnapshotData.PatternState(2, 5, count, 0, List.of(), null);
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                state.write(buffer);
                assertEquals(state, SnapshotData.PatternState.read(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
