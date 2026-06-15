# SOP: Building & running AdvancedRocketry locally

## Context

Read once per session that will compile, run, or test the mod. This
captures the environment facts that are NOT in `build.gradle` and that
have repeatedly cost time when rediscovered from scratch (a hung
10.5-hour run, a branch that silently won't build, a client that crashes
before any test runs).

## The base branch must be RFG-buildable

- **`origin/1.12` = StannisMod fork**: Groovy `build.gradle`,
  RetroFuturaGradle (RFG). **Builds under JDK 25.** Base every feature
  branch here.
- **Raw `1.12` = dercodeKoenig**: `build.gradle.kts`, FancyGradle. **Does
  NOT build under JDK 25.** If a checkout suddenly won't configure, check
  you're not on a FancyGradle base.

```bash
export JAVA_HOME=/home/dev/jdks/jdk-25.0.3+9   # RFG needs JDK 25
```

## Always bound MC runs with a wall-clock timeout

`testServer`, `testClient`, `runClient`, `runServer` can hang
indefinitely (port bind, LWJGL init, a stuck tick loop). One run hung
**10.5 hours**. Never launch them un-bounded.

```bash
timeout --signal=KILL <seconds> ./gradlew <task> --no-daemon ...
```

## testServer

- Serial: `--max-workers=1` (parallel forks add flake — see
  [`flake-diagnosis.md`](./flake-diagnosis.md)).
- **Cache-bust between every rerun**, or Gradle reports `UP-TO-DATE` and
  re-runs **zero** tests while still printing `BUILD SUCCESSFUL`:
  ```bash
  rm -rf build/{reports,test-results,tmp}/testServer
  ```
- The `testServer` task already sets
  `-Dforge.test.harness.enabled=true`; running individual classes works
  with `--tests "*ClassName"`.
- Filter to the classes you changed; a full-suite run is minutes of boot
  time you usually don't need.

## testClient (headless)

- Needs a **real X server**: `DISPLAY=:100` against a fresh Xvfb.
  Xorg `:99` (amdgpu DDX) is incompatible with LWJGL 2.9.4 — the client
  crashes before tests run.
- `build.gradle` forwards `DISPLAY` / `XAUTHORITY` /
  `LIBGL_ALWAYS_SOFTWARE` into the spawned client JVM.
- If the ForgeTestFramework was modified but not published to
  mavenLocal, add `-PuseLocalFramework=true`.

```bash
DISPLAY=:100 timeout --signal=KILL 1200 ./gradlew testClient \
  -PuseLocalFramework=true --max-workers=1 --no-daemon
```

## Multiple worktrees

Build a non-checked-out worktree without `cd` (which can trip a
permission prompt):

```bash
./gradlew -p <worktree-dir> compileJava --no-daemon
```

See [`fix-propagation-across-branches.md`](./fix-propagation-across-branches.md)
for replicating a change across branches.

## Prevention

- [ ] `JAVA_HOME` exported to JDK 25 before any gradle.
- [ ] Every MC run wrapped in `timeout --signal=KILL`.
- [ ] `testServer` reruns cache-busted; per-run PASS count grepped.
- [ ] `testClient` on `DISPLAY=:100`, not `:99`.

## Related

- [`flake-diagnosis.md`](./flake-diagnosis.md) — the cache-bust + serial
  rules and why parallel forks flake.
- [`harness-capabilities-and-limits.md`](./harness-capabilities-and-limits.md)
  — what these runs can and cannot verify.
