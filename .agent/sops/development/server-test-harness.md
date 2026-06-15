# SOP: Server-test harness — isolation & config injection

## Context

Read before writing a `test/server/` class. Server tests boot a **real
dedicated server** in a separate JVM via `RealDedicatedServerHarness`
(ForgeTestFramework) and drive it with `/artest` probes. The two base
classes have different lifecycles and different state-leak risks; getting
the choice wrong causes cross-test contamination or wasted boot time.

## Pick the base class

| Base | Lifecycle | Use when |
|---|---|---|
| `AbstractSharedServerTest` | one boot per **class** (`@BeforeClass`/`@AfterClass`), ~12 s cold start, ~5 s saved per extra method | the default — several tests that share one world, isolated by position |
| `AbstractHeadlessServerTest` | one boot per **method** (`@Before`/`@After`), ~10–15 s each | you must mutate load-time config or a pre-boot fixture (XML/`.cfg`) per test |

Guard with `Assume.assumeTrue(... PROP_HARNESS_ENABLED ...)` so the class
skips cleanly when the harness is disabled (the `testServer` gradle task
enables it).

## Shared-harness state-leak contract

A shared harness is reused across the class's methods, so:

- **Position-isolate**: give each `@Test` its own `BASE_X/Z` region; never
  reuse coordinates.
- **Read fresh IDs**: don't assume specific entity/dim id ranges.
- **Reset any global you mutate** — config flags, wear, weather — in
  `@After` or via a probe reset (`/artest weather`/`weight` resets, or
  `config set` back to default), in a `finally` so a failing assert still
  restores. A leaked config flag silently changes the next test.

## Injecting config — three methods by flag type

The test JVM is separate from the server JVM; mutating `ARConfiguration`
in the test does nothing to the server.

1. **Runtime-read flag** → `/artest config set <key> <value>` (key must be
   in `CONFIG_WHITELIST`). Works because the server reads the field live.
2. **Load-time flag** (read once when a world/dimension is created, then
   sticky) → write the `.cfg`/`planetDefs.xml` into `workDir` **before**
   `RealDedicatedServerHarness.startWith(workDir, ...)`. A runtime
   `config set` is too late to change it.
3. **Pure unit test** (no server) → mutate
   `ARConfiguration.getCurrentConfig().<field>` in `try/finally`.

### The load-time stickiness gotcha

The classic trap: per-dimension state installed at creation is **sticky**
for the dimension's life. The `ARWeatherWorldInfo` wrapper installs (or
not) based on the flag *when the planet first loads*; flipping the flag at
runtime afterward does not wrap/unwrap it. So to test a runtime gate on an
already-wrapped planet, **load it in the desired wrap state first**
(access it while the flag is on), then flip the flag. If you flip first
and load second, you get the other state and measure the wrong thing.

## Two-boot tests (persistence / restart)

For "survives restart" contracts, call
`RealDedicatedServerHarness.startWith(workDir, false)` twice against the
**same** `workDir`, closing the first before opening the second (see
`WeatherPersistenceTest`). Always close every harness in `@After`.

## Prevention

- [ ] Right base class for the lifecycle you need.
- [ ] Per-test position isolation; fresh-id reads.
- [ ] Every mutated global reset in `finally`/`@After`.
- [ ] Config set by the method that matches the flag's read-time
      (runtime vs load-time).
- [ ] Load-time/sticky state loaded in the desired state before the flag
      flips.

## Related

- [`artest-probe-authoring.md`](./artest-probe-authoring.md),
  [`flake-diagnosis.md`](./flake-diagnosis.md),
  [`config-flag-disableability.md`](./config-flag-disableability.md),
  [`testing-principles.md`](./testing-principles.md).
