package dev.ae2lattice.net;

import net.minecraftforge.network.NetworkRegistry;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelCompatibilityTest {
    @Test
    public void previousClientAndServerNeedOnlyTheBaseProtocol() {
        assertTrue(NetworkCompatibility.ACCEPT_BASE.test("9"));
        assertTrue(NetworkCompatibility.ACCEPT_UNKNOWN_COUNTS.test(NetworkRegistry.ABSENT.version()));
    }

    @Test
    public void latticeRemainsOptionalOnBothSides() {
        for (var predicate : java.util.List.of(
                NetworkCompatibility.ACCEPT_BASE, NetworkCompatibility.ACCEPT_UNKNOWN_COUNTS)) {
            assertTrue(predicate.test(NetworkRegistry.ABSENT.version()));
            assertTrue(predicate.test(NetworkRegistry.ACCEPTVANILLA));
        }
    }

    @Test
    public void newPeersCanAdvertiseTheOptionalCapability() {
        assertTrue(NetworkCompatibility.ACCEPT_BASE.test("9"));
        assertTrue(NetworkCompatibility.ACCEPT_UNKNOWN_COUNTS.test("1"));
    }

    @Test
    public void unrelatedWireProtocolsAreNotAcceptedWithoutADecoder() {
        assertFalse(NetworkCompatibility.ACCEPT_BASE.test("8"));
        assertFalse(NetworkCompatibility.ACCEPT_BASE.test("10"));
        assertFalse(NetworkCompatibility.ACCEPT_BASE.test("unrecognized"));
    }
}
