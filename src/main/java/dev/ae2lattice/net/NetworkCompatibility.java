package dev.ae2lattice.net;

import java.util.function.Predicate;

import net.minecraftforge.network.NetworkRegistry;

/** Version policy kept independent of channel registration so it can be checked without booting Forge. */
final class NetworkCompatibility {
    // Stable wire format and additive capability. Never require coordinated client/server upgrades.
    static final String BASE_VERSION = "9";
    static final String UNKNOWN_COUNTS_VERSION = "1";
    static final Predicate<String> ACCEPT_BASE = NetworkRegistry.acceptMissingOr(BASE_VERSION);
    static final Predicate<String> ACCEPT_UNKNOWN_COUNTS = NetworkRegistry.acceptMissingOr(UNKNOWN_COUNTS_VERSION);

    private NetworkCompatibility() {}
}
