# TASK-46: `CompatibilityMgr` is currently vestigial — decide revive vs remove

## Ticket

- Source: discovered 2026-06-01 while auditing `integration.jei` references
  for the issue #76 JEI NoClassDefFoundError guard.
- Status: 🟡 **Backlog — not started.** Deliberately left in place; the
  maintainer may want to give it meaning again rather than delete it.
- Created: 2026-06-01.

## Context

`integration/CompatibilityMgr.java` holds three static booleans plus a
recipe-reload hook, but every live consumer is gone or commented out:

- `AdvancedRocketry.compat = new CompatibilityMgr()` (AdvancedRocketry.java:173)
  — instance created, **never read** anywhere.
- `isSpongeInstalled` — written at `AdvancedRocketry.java:1145`, its only
  read is commented out (`WorldProviderPlanet.java:232`). Written, never read.
- `gregtechLoaded` / `thermalExpansionLoaded` — set only inside
  `getLoadedMods()`, which has **no callers**. Never set, never read.
- `getLoadedMods()` — uncalled.
- `reloadRecipes()` — entirely commented out (also the only reference to
  `integration.jei.ARPlugin` left in the file — a dead import).

So the class does nothing observable today. It was historically the
central "which integration mods are present" flag-holder + a JEI
recipe-reload hook.

## Why keep it for now

Maintainer call (2026-06-01): not certain it should be removed — the
mod-presence flags + a recipe-reload entry point may be given meaning
again (e.g. real GregTech / ThermalExpansion / Sponge branches, or a
working `/ar reloadrecipes`). Deleting now would just have to be
re-created later.

## Options (decide later)

1. **Revive** — wire `getLoadedMods()` into mod init, uncomment the
   reads that need the flags, and restore `reloadRecipes()` behind a
   `Loader.isModLoaded("jei")` guard (so it can't re-introduce the #76
   class-load crash). Then add coverage for the branches that read it.
2. **Remove** — delete `CompatibilityMgr`, the unused `compat` field,
   the dead `import ...jei.ARPlugin`, and the orphaned `isSpongeInstalled`
   write. Smallest footprint; loses the scaffolding.
3. **Leave as-is** — keep as a documented placeholder (current state).

## Dependencies

- Independent. Does NOT block the #76 guard (already shipped in
  `PacketDimInfo`) or any other work.
- If revived, the recipe-reload path MUST stay behind a JEI-loaded guard
  — see the #76 fix rationale (touching `ARPlugin` loads JEI classes).
