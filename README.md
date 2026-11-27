<p align="center"><img src="docs/icon.png" width="96"></p>
<h1 align="center">Lattice for AE2</h1>

![Lattice crafting tree](docs/tree.png)

A live, animated **crafting tree** for Applied Energistics 2 crafting CPUs.

AE2's crafting status is a flat grid of numbers. Lattice shows the same job as a tree: every step, how it
connects, which machine is working on it, and what that machine is actually doing right now.

## Features

- **Tree view** on AE2's Crafting Status screen - toggle with the **Tree** button, back with **Grid** (or `G`).
  Remembers which view you used last (configurable).
- **Every step as a card**: what's ready, in machine, queued and still to do - straight from AE2's own numbers.
- **Machine chips**: the real machine for each step (with its icon and anvil name), and its real status:
  - GregTech CEu machines and multiblocks: recipe progress, idle / switched off, and GregTech's own waiting reason
  - Furnaces, blast furnaces, smokers: cooking progress, no fuel, output full
  - AE2 Molecular Assemblers: crafting progress, no power
  - Subnet setups (pattern provider → ME interface → storage bus → machine) are followed to the real machine
- **Hover a machine** to see its slots and tanks, and the recipe it's running (drawn by EMI if installed).
- **Click a machine** to highlight it in the world (through walls).
- **Problems in red** only when a machine itself reports one - nothing is estimated.
- **Shared machines and CPUs handled correctly**: only the step/CPU actually being served shows as working.
- **Item animations** along the lines when AE2 sends ingredients to a machine.
- **Job finished**: a short celebration and a summary (time, steps, machines used).
- AE2-style CPU list with the same tooltips.

![Machine panel](docs/machine-panel.png)

## Read-only

Lattice observes what AE2 is doing and reads machine state: no items are created, moved or removed, and no
machine is controlled. It saves its own recipe tracking metadata alongside the crafting CPU's saved data.
The only action button is **Cancel**, which is AE2's own cancel.

Recipe cards count outstanding outputs separately for each pattern. AE2 does not identify which recipe or
machine returned an item, so identical outputs are credited to the earliest outstanding dispatch for that
item. These counts describe outstanding work, not proof of which physical machine finished first.
If a job was already running before this tracking was installed, missing recipe history is shown as
**unknown** until those untracked outputs return; item-only nodes still show AE2's combined item totals.
The server and clients can be updated independently. Updating both gives the corrected counts in both
cards and tooltips. Clients without Lattice can still join.

## Requirements

- Minecraft **1.20.1**, Forge **47.1+**
- Applied Energistics 2 **15.2+** (tested with 15.4.12 and Star Technology's AE2 build)
- Optional: EMI (recipe box in the machine panel, hides its panel while the tree is open), GregTech CEu Modern

## Install

Put the jar in the **server's** `mods` folder. Installing Lattice on clients is **optional**:

- Clients with Lattice can use the tree view and receive live crafting and machine data from the server.
- Clients without Lattice can join normally and use AE2's standard crafting status screen.
- A client with Lattice can also join a server without it, but the tree view needs Lattice on the server
  to receive data.

The builds produced in this fork (`1.0.0`, `1.0.0-client-fix`, and count-fix) share the same base protocol (9); matching mod
versions are not required. A newer server accepts older clients, and a newer client accepts older
servers. Features are enabled only when supported by the other side. Older clients retain their own
tooltip/status behaviour and receive the legacy combined item total when per-recipe history is unknown.
Newer clients show **unknown** instead. Per-recipe counts with known history work on older clients too.
Unrelated upstream builds using a different wire protocol are not covered by this compatibility guarantee.

In singleplayer, just install it on the client. AE2 and the modpack's other required mods are still needed
as usual.

## Config

- `config/ae2lattice-client.toml`: default view, animations, celebration (+ sound), highlight duration
- `<world>/serverconfig/ae2lattice-server.toml`: how often open tree views are updated

## Building

```
./gradlew build
```

## License

MIT - see [LICENSE](LICENSE).
