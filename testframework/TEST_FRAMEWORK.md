# Test Framework Summary

This project contains reusable testing infrastructure for Forge 1.12.2 mod verification. It is a library, not a mod-specific test suite.

## Layers

- `com.github.stannismod.forge.testing.junit` — JUnit 4 base classes (`AbstractHeadlessServerTest`, `AbstractClientE2ETest`) that wrap the harness lifecycle in `@Before` / `@After`. Each test method gets a fresh harness; parallelism is delegated to Gradle's `maxParallelForks`.
- `com.github.stannismod.forge.testing.server` starts and controls a real dedicated server process.
- `com.github.stannismod.forge.testing.client` starts and controls a real client process through a socket bridge.
- `com.github.stannismod.forge.testing.client.bridge` runs inside the client JVM and translates test commands into real client-thread actions.

The framework is a library — there is no built-in scenario runner, registry,
or report writer. Consumers use JUnit's runner (via Gradle's `Test` task) for
discovery, execution, parallelism, filtering and reporting.

## JUnit Base Classes

`AbstractHeadlessServerTest` provides a single `RealDedicatedServerHarness` for each test method via `@Before` / `@After`. `harness()` and `client()` are exposed to subclass methods. The class is opt-in gated by the `forge.test.harness.enabled` system property — when unset, every test SKIPS via `org.junit.Assume`.

`AbstractClientE2ETest` does the same for a paired server + `RealClientHarness`, exposing `server()`, `serverClient()`, `clientHarness()` and `bot()`. Gated by both `forge.test.harness.enabled` and `forge.test.client.enabled`.

Scenarios that need to manage two harness lifecycles against the same workDir (persistence-restart tests, fixture-write-before-start tests) skip the base classes and call `RealDedicatedServerHarness.startWith(workDir, cleanupOnClose)` directly from a plain `@Test` method with manual `@Before` / `@After`.

## Dedicated Server Harness

`RealDedicatedServerHarness` starts `GradleStartServer` in a separate JVM with `--nogui`, a temporary game directory, and an automatically reserved localhost port. It waits until the server reports readiness, then exposes a `TestClient`.

`TestClient` writes commands to the server console, appends a unique marker with `say`, and reads stdout until that marker appears. This gives tests deterministic command completion without depending on arbitrary sleeps.

## Real Client Harness

`RealClientHarness` starts `GradleStart` in a separate JVM and connects it to the dedicated server. It also opens a localhost control socket. The client-side bridge connects back and sends `READY` when the bridge is available.

`ClientBot` is the test-facing command API. It can wait for a client world, select a hotbar slot, right-click a block, click or drag GUI points, type text, close screens, inspect client-visible block state, and report player or GUI state.

The harness uses temporary game directories and cleans them up on close. Client output is mirrored to `client.log` when the client bootstrap installs logging.

## Client Bridge

`ForgeTestClientBootstrap` is loaded inside the modded client JVM by the consuming mod when a test system property is present. It listens on the control socket and schedules actions on the Minecraft client thread. This keeps interactions aligned with the real client runtime instead of mutating state directly from the test JVM.

The bridge intentionally performs actions through client-visible paths such as right-clicking blocks, GUI clicks, GUI drags, typing, and hotbar selection. Server probe commands may observe the result, but should not replace the player action under test.

## Expected Consumer Responsibilities

A consuming mod project provides its own test scenarios and any mod-specific server probe commands. The framework provides process control, client control, scenario orchestration, and reporting.

The consuming mod should keep gameplay assertions in its own test source set. Framework classes should stay reusable and avoid importing mod-specific tile entities, blocks, items, or packets.
