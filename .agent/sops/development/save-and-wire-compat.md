# SOP: Save & wire compatibility — what you must never rename

## Context

Read before renaming, reordering, or deleting any identifier that crosses
a persistence or network boundary. These break **existing saves** and
**modpack interop** silently — the code compiles, tests that don't pin the
exact string pass, and players lose worlds or desync. Treat the items
below as frozen public contract.

## Frozen identifiers (never rename / reorder / repurpose)

- **Registry names** of blocks, items, entities, tile entities,
  satellites, fluids, enchantments, advancements. Breaks `/give`, saved
  chunks, recipes, JEI, other mods' references.
- **NBT keys and their value shapes.** A renamed key = data silently lost
  on load. New keys must be **additive** and tolerant of absence (read
  with a default), so old saves still load.
- **Network packet IDs and field order.** The client decodes by position;
  reordering fields or inserting one mid-stream desyncs every client on
  the old protocol.
- **`enum` ordinals used on the wire or in NBT.** Append new constants at
  the **end**; never insert or reorder. (e.g. AR's `PacketType` is
  extended by appending so ordinals stay stable.)
- **Capability keys**, OreDict entries, lang keys referenced by code,
  achievement/advancement IDs.

## Adding state the compatible way

- New NBT: pick a fresh key, write it always, read it with a default when
  absent. (TASK-45's service-station input inventory added key
  `"repairInv"` this way — old stations load with an empty inventory, no
  migration needed.)
- New packet field: append at the end, bump a version if the decoder
  can't tell old from new.
- New enum constant: append last.

## Pin the schema, not the values

Per [`testing-principles.md`](./testing-principles.md): pin that NBT *has*
a key `X` and round-trips, that a registry name *is* the exact string,
that a packet's field order is stable — these are real contracts. Do
**not** pin the incidental value flowing through unless other code
switches on it.

## Tooling guardrail

The IDE's `rename_refactoring` must **never** be pointed at any identifier
above — it will happily rename the string literal and the registry/NBT
key with it. See [`mcp-intellij-usage.md`](./mcp-intellij-usage.md).

## Prevention

- [ ] No registry/NBT/lang/packet identifier renamed or reordered.
- [ ] New NBT keys additive + absence-tolerant.
- [ ] New enum constants appended last.
- [ ] Save round-trip pinned for any new persisted state.

## Related

- [`mixin-coremod-dev-vs-prod.md`](./mixin-coremod-dev-vs-prod.md),
  [`mcp-intellij-usage.md`](./mcp-intellij-usage.md),
  [`testing-principles.md`](./testing-principles.md).
