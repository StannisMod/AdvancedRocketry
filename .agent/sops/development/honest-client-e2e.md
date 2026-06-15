# SOP: Honest client end-to-end tests

## Context

Read this WHENEVER you are asked to write (or audit) a "client e2e" /
"client-side" test, BEFORE writing the first assertion. Read it together
with [testing-principles](./testing-principles.md) (what a test may pin),
[client-tests-on-linux](./client-tests-on-linux.md) (how to run the tier),
and [sharing-client-harness](./sharing-client-harness.md) (its cost).

The client tier is the slowest and most tempting to fake. A faked client
test is worse than no test: it reports GREEN while the client path is
broken, so it actively hides regressions. This SOP exists to make that
failure mode impossible to commit by accident.

## The trap: false-green via server probes

The AR test harness exposes rich SERVER probes (`/artest …`). It is trivial
to write a "client" test that never touches the client: drive a server probe,
read server state back, assert. It passes — and proves nothing about the
client. Real bugs this hides: a keybind that never sends its packet, a GUI
that never opens, render interpolation that lags 150 blocks, a HUD line whose
lang key is missing.

If the client `.jar` were deleted and the test still passed, it was never a
client test.

## The hard rule (non-negotiable)

When the task is a CLIENT e2e test, BOTH the stimulus and the observation
must go through the real client:

1. **Stimulus enters the real client input surface** — inject a real key
   (`ClientBot.setKey/holdKey`), a real look/mouse (`ClientBot.setLook`), a
   real GUI click (`clickSlot/clickButton`), so the production client code
   (`onClientTick`, `KeyInputEvent`, GUI handlers, the keybind→packet path)
   actually runs. NEVER substitute the server probe that the client would
   have triggered (e.g. do not call `artest … free-flight-input` to "stand
   in for" pressing the key — press the key).

2. **Observation reads real client state** — `report_state.screen`
   (open GUI), `reportRidingEntity` (CLIENT pos/motion the client renders),
   `readStaticField` (client HUD/render fields), `report_state.player*`
   (client look/pos). NEVER assert a server-side entity query as if it were
   the client's view.

Server probes are allowed ONLY for:
- **Setup/arrange** — build the rocket, mount, set flight mode, teleport.
- **A cross-side oracle** — compare the CLIENT observation against the
  server's authoritative value in the SAME test (e.g. "client-rendered Y
  tracks server Y within 6 blocks"). Here the server value is the expected,
  the client value is the actual.

## Gate to apply before claiming a test is "client e2e"

Answer all three. If any is "no", it is NOT a client e2e — fix it or
relabel it.

- [ ] Does the stimulus run real client code? (If I removed the client
      input handler, would the test stop driving the system?)
- [ ] Does at least one assertion read client-observed state?
- [ ] Would the test fail if the client half of the feature were broken
      while the server half stayed correct?

## When a client e2e is actually warranted (don't write them gratuitously)

Client e2e is the RIGHT (and often only) tool when the contract lives in
client-only code or in the client↔server round-trip:

- **Input handling**: keybinds, mouse/look, GUI interaction, the
  keybind→packet path, key-conflict scoping.
- **Client render / interpolation**: dead-reckoning, poscorrection, "does
  the rendered entity track the server".
- **Client UI**: HUD text (incl. lang-key resolution), GUI open/close,
  screen state.
- **Client-side state & sync**: client caches, datawatcher-driven client
  fields.

It is the WRONG tool — push the test DOWN the pyramid — when:

- The logic is pure and side-effect free → **unit** (`testUnit`), e.g.
  `FreeFlightPhysics`, `FreeFlightInput` wire.
- The logic is server-authoritative and has no client-specific behaviour →
  **testServer** (a server probe IS the honest path there).
- You only need to prove a packet's server handling → **testServer** +
  unit on the wire format.

Rule of thumb: write the FEWEST client e2e that cover the client-only
contracts, and pin everything else cheaper. One honest client e2e per
client contract beats ten server probes pretending.

## If the harness can't observe it honestly — extend the harness, don't fake it

If a client contract has no honest observation/stimulus yet, add the
capability to the vendored framework (`testframework/src/main/java/...`,
a git subtree since 2026-06-10) in the SAME commit as the first test that
uses it — no version bumps, no publishing. Recent examples: `setKey/holdKey`
(real key path), `setLook` (real mouse aim), `sendChat` (real chat/command
path), `useItem`/`interactBlock` (real right-clicks), `reportRidingEntity`,
`reportChat` (i18n-resolved overlay), `reportPlayerItems` (client-rendered
stacks incl. NBT), `reportEntities` (client-world entity presence),
`reportMods`. Never weaken the test to fit a missing capability.

## Prevention

- [ ] Applied the three-question gate before writing assertions.
- [ ] Stimulus uses a real client input API, not the equivalent server probe.
- [ ] At least one assertion reads client-observed state.
- [ ] Server probes appear only as setup or as a cross-side oracle.
- [ ] Missing observability was added to FTF, not worked around.

## Known trap: `artest server wait`

`/artest server wait <dim> <ticks>` runs ON the server thread and therefore
blocks ticking while it waits — it is a no-op stall (returns
`elapsedTicks:0`). To let server ticks elapse, wait OFF-thread: client-tier
tests use `bot().waitTicks(n)`; server-tier tests sleep in the test JVM.

## Related documents

- [testing-principles](./testing-principles.md) — contracts vs impl details.
- [client-tests-on-linux](./client-tests-on-linux.md) — running the tier headless.
- [sharing-client-harness](./sharing-client-harness.md) — per-method cost.
- [flake-diagnosis](./flake-diagnosis.md) — when a client test is flaky.
