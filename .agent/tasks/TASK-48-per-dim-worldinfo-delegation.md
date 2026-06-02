# TASK-48: Per-dimension WorldInfo state vanilla delegates to overworld (feature request)

## Ticket

- Source: research spun off from [[TASK-47]] (per-dim time / beds, #66) on
  2026-06-02 — "what else is ideologically per-world but vanilla shares with
  the overworld?".
- Status: 🟦 **Feature request — not urgent. Needs additional design work.**
  No implementation planned yet; this is a scoping/research document.
- Created: 2026-06-02.

## Context

AR planets are `WorldServerMulti` whose `worldInfo` is a `DerivedWorldInfo`:
every getter delegates to the overworld's `WorldInfo` and every setter is a
no-op. AR has already overridden two slices of this in its custom WorldInfo
(`ARWeatherWorldInfo` → `ARDimensionWorldInfo` after TASK-47): **weather**
(shipped) and **time** (TASK-47). This task catalogues the *remaining*
state that is conceptually per-dimension but is currently forced to the
overworld value, as candidates for the same per-dim treatment.

This is the natural continuation of the "each planet is its own world"
direction, but each item carries real design questions (persistence,
client sync, command semantics, save migration, mod-compat) — hence
"needs design work", not a ready-to-build plan.

## Candidates (from a full read of `DerivedWorldInfo`)

1. **GameRules** (`getGameRulesInstance` → delegate). **Highest value, sharpest
   coupling.** Both the time `+1` increment and the sleep skip are gated by
   `doDaylightCycle` read from the *shared* overworld GameRules
   (`WorldServer.tick:198`), so even after TASK-47 `/gamerule doDaylightCycle
   false` freezes every planet. Per-dim `doDaylightCycle`, `doWeatherCycle`,
   `keepInventory`, `doMobSpawning`, `mobGriefing`, etc. would make planets
   truly independent. Design questions: per-dim GameRules storage + a
   command surface to set them per-dim; how to inherit defaults from
   overworld; client never reads server GameRules so no sync issue, but the
   `/gamerule` command targets the sender's world — needs a per-world
   GameRules instance to exist first.
2. **Spawn point** (`getSpawnX/Y/Z`, `setSpawn` — setters are no-ops). Each
   dim could have its own world spawn; today compasses and `setSpawn` on a
   planet resolve to / are lost against the overworld. AR already has its own
   respawn-dimension logic (`WorldProviderPlanet.getRespawnDimension`), so
   this overlaps and must be reconciled.
3. **Difficulty** (`getDifficulty`/`isDifficultyLocked`, setters no-op). A
   "hard planet" is impossible today. Per-dim difficulty affects mob spawning
   / damage. Design question: command + persistence + how it interacts with
   the server-global difficulty and peaceful-mode mob purging.
4. **Terrain type** (`getTerrainType`/`setTerrainType` — setter no-op).
   Note: `WorldProviderPlanet.init` already calls
   `world.getWorldInfo().setTerrainType(planetWorldType)`, which is silently
   swallowed by the derived info; `getTerrainType` returns the overworld
   type. Low-impact but a concrete example of a lost per-dim setter.
5. **Game type / gamemode** (`getGameType`). Per-dim default gamemode
   (e.g. an adventure planet). Niche.

## Precedent

Weather (shipped) and time ([[TASK-47]]) are the proof that the
custom-WorldInfo + per-dim saved-data pattern works end-to-end (server tick,
NBT persistence, and vanilla's per-dimension `SPacketTimeUpdate` /
weather-sync). Any item here would follow the same shape.

## Why not now

- Each item needs its own design pass (persistence schema, command surface,
  client sync where relevant, save migration, mod-compat with anything that
  reads these off `WorldInfo`).
- None is required to close #66; TASK-47 is self-contained.
- GameRules in particular is a sizeable subsystem (per-world GameRules
  instance + `/gamerule` routing) and should be its own task if promoted.

## Suggested first step if promoted

Spike per-dim GameRules (item 1) only, behind a config flag, starting with
`doDaylightCycle` + `doWeatherCycle` since they directly complete the
TASK-47 per-dim day/night story. Everything else stays delegated until a
concrete need appears.
