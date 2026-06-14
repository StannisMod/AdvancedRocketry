# Honest-client-e2e delta — 2026-06-11

**Branch**: `fix/various`
**Parent audits**: [`2026-05-27-full-coverage-audit.md`](./2026-05-27-full-coverage-audit.md),
[`2026-05-29-coverage-delta.md`](./2026-05-29-coverage-delta.md)
**Trigger**: full sweep of the testClient tier against
[`sops/development/honest-client-e2e.md`](../sops/development/honest-client-e2e.md)
(the SOP postdates most of the tier — written 2026-06-03, tier mostly built in May).

## Sweep verdicts (before → after)

Of 29 client e2e classes audited: 9 honest, 6 partial, 13 false-green
risk, 1 mislabeled. All 20 non-honest were remediated the same day:

| Group | Tests | Remediation |
|---|---|---|
| `/ar` command tests | WorldCommandFetchTest, WorldCommandFetchModeratorTest, WorldCommandPlayerEquippedE2ETest | `exec-as-player` stimulus → real client chat (`sendChat`); outcomes read at the player layer (client position/dim/inventory/chat overlay), server probes demoted to oracles |
| Ride tests | HovercraftRideE2ETest, ElevatorCapsuleRideE2ETest | throttle = real W key, dismount = real sneak; assertions via `reportRidingEntity` (mount stays a probe — SOP-allowed arrange) |
| Item-use tests | ItemHovercraftSpawn, OreScannerRightClick, ItemAtmosphereAnalzer, ItemSealDetector, ItemBiomeChanger | real `useItem`/`interactBlock` clicks (+ `setLook` aim); observations via client entities / client screen / client chat (i18n resolved); arrange-only probe splits (`equip-orescanner`, `equip-biomechanger`, `satellite poslist-size`) |
| Relabeled to testServer (user-approved) | VacuumGuards, Advancements→AdvancementsTrigger, LowGravFallDamage, AtmospherePlayerEvent | server-side handler contracts that the client tier drove via probes anyway; headless player supplied by `artest player ensure-fake` + `tick-living` |
| Partial completions | RocketBuilderGui (client sees the spawned EntityRocket), suit ×2 + GasChargePad (client-rendered chest NBT) | player-layer completion asserts added |

Remaining PARTIAL (accepted): `RailgunCargoTransitE2ETest` — stimulus and
assertions are server probes; the cargo-transit contract is double-pinned at
the server tier (`RailgunFiringContractTest`), and the client-visible surface
(hatch GUI contents) needs a dedicated GUI leg. Candidate follow-up, not a
silent exception.

## Framework capabilities added (vendored testframework/)

`send_chat`, `report_mods`, `use_item`, `interact_block` (week of 06-10),
`report_chat`, `report_player_items`, `report_entities` (06-11) — each landed
in the same commit as the first test using it.

## Production bugs found by the sweep (all fixed on `fix/various`)

Connectionless player-shaped entities (Forge FakePlayers — spawned by
turtles/block-breakers/test harnesses) crashed AR server-side:

1. `EntityEventHandler.onJoinWorld` / `onPlayerChangedDimension` —
   unconditional `player.connection.sendPacket` (+ CCE-prone cast for non-MP
   EntityPlayer impls). Guarded.
2. `PlanetWeatherManager.syncToPlayer` — same. Guarded.
3. `AtmosphereHandler.onTick` effect paths — potion sync +
   `PacketOxygenState` (now via `AtmosphereType.sendToRealPlayer`) took the
   server tick loop down for connectionless players in non-breathable dims.
   Effects now skip them; cache/sync bookkeeping still runs.
4. (Latent, found by the OreScanner rewrite) `ItemOreScanner.onItemRightClick`
   casts the stored satellite id to `int` before the registry lookup — long
   ids silently never resolve and the GUI never opens. Documented at the
   probe; production fix not yet decided (ids are int-safe in practice).

## Probe defect found (open)

`artest server wait <dim> <ticks>` executes ON the server thread
(console command), so its sleep-poll loop blocks ticking entirely: it
returns `elapsedTicks:0` after burning its wall budget and stalls the
server for the duration. Every existing caller got a silent no-op wait.
Relocated tests wait off-thread (test-JVM sleep) instead. Follow-up:
fix or retire the probe and sweep its callers (RocketDescentLandingTest
et al.).

## Flake note (same day)

`WorldCommandFetchModeratorTest` (3 JVMs: server + 2 GL clients, ~7 GB):
green standalone at 11:05 after the sendChat rewrite; from ~12:30 the
first client's bridge dies seconds after world-join ("Client bridge
closed unexpectedly" at the first waitTicks), reproducibly, while TWO
sibling-session Minecraft clients run on the same box/display (pgrep
evidence per the shared-box memory). Code unchanged between green and
red. Verdict: shared-box display/RAM contention, not a test defect —
re-run when the box is quiet before treating as a regression.
`GuidanceComputerGuiE2ETest` and one `ItemSealDetector` method failed
once in the full-suite run under the same load and passed on re-run
(the seal test's chat poll was also hardened: 20-line window, 200-tick
cap).

## Coverage-audit cross-check (same sweep)

The 2026-05-27/29/31 audit trio remains trustworthy: all Deep/Partial pins
exist (one stale name fixed: `ARWeatherWorldInfoTest` →
`ARDimensionWorldInfoTest`), every accepted §3 proposal shipped or tracked,
no dangling debt. Pyramid counter: trust `tasks/README.md` (regen 2026-06-03)
over audit snapshots.
