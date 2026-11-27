# Compatibility requirements

- The user requires backward-compatible releases. Preserve interoperability with the previously delivered
  Minecraft 1.20.1 / Forge Lattice builds; clients and servers must be able to update independently.
- Keep Lattice optional on both sides. Clients without Lattice must still be able to join.
- Keep the base protocol 9 channel, packet IDs, field order, and existing value meanings compatible.
  Do not bump that protocol or loosen its version check as a substitute for compatibility.
- Negotiate new features through optional capability channels or optional extension packets. Use a legacy
  fallback when the other endpoint lacks a capability; do not send new sentinel values to older clients.
- Keep capability meanings stable. Future capabilities should be additive, not require matching mod versions.
- Add regression coverage for old-client/new-server and new-client/old-server behaviour when changing networking.
- Read older saved tracking data and tolerate absent tracking data. Keep Lattice metadata separate from AE2's
  crafting state so an older Lattice build can still load the world.
