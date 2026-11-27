## Unreleased

- Preserve mixed-version compatibility with protocol-9 clients and servers. Negotiate unknown-count
  display support on an optional capability channel; older clients receive their legacy item total
  when recipe history is missing, instead of an unsupported negative count.
- Track "in machine" output counts per recipe on the server, including partial returns and large batches.
  Identical returned outputs are credited in dispatch order because AE2 does not identify their source recipe.
- Save recipe output tracking with the crafting CPU so counts survive restarts and chunk reloads.
  Jobs without saved tracking show unknown recipe counts until their untracked outputs return.
- Use recipe-specific "in machine" counts in client tooltips and status colours as well as cards.
- Fix recipe cards and tooltips sharing "to do" counts when multiple recipes produce the same item.
  Counts now use each recipe's remaining runs and output amounts; only a client update is needed.
- Advertise Lattice as optional for clients in Forge's server compatibility display.
- Only accept tree subscriptions and send snapshots for clients that have the Lattice network channel.
- Document optional client installation and the server requirement for live tree data.

## 1.0.0

First public release.

- Live crafting tree on AE2's Crafting Status screen (Tree / Grid toggle, remembered)
- Real machine status and progress for GregTech CEu, furnaces and AE2 molecular assemblers
- Subnet setups followed to the real machine; anvil names shown
- Machine panel with slots, tanks and the running recipe (EMI recipe box when available)
- Click a machine to highlight it in the world
- Correct handling of machines shared between steps and between CPUs
- Item animations, job-finished celebration and summary
- AE2-style CPU list and tooltips
