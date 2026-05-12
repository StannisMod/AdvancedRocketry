# Forge Test Framework

Reusable testing infrastructure for Forge 1.12.2 mods. The framework can be
consumed in three ways (in order of preference for downstream mods):

1. **Maven artifact from `mavenLocal()`** — locally published; works for any
   developer who has cloned this repo. See [Publishing](#publishing).
2. **Gradle composite build** (`includeBuild '../ForgeTestFramework'`) — handy
   when you are iterating on the framework and a consumer mod in lockstep.
3. **Pre-built jar** — fallback for environments without the source repo.
   Less hygienic; prefer one of the above.

See [TEST_FRAMEWORK.md](TEST_FRAMEWORK.md) for the framework summary and
extension points.

## Primary usage — JUnit-native (since 0.2.2)

For Forge dev workspaces with JUnit on the test classpath, extend one of the
two base classes that wrap the harness lifecycle in `@Before` / `@After`:

```java
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ServerSmokeTest extends AbstractHeadlessServerTest {
    @Test
    public void worldGenerates() throws Exception {
        // server is already up; `client()` is the TestClient
        assertTrue(client().execute("list").size() > 0);
    }
}
```

For E2E tests that need a real Minecraft client connected to the server:

```java
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

public class GuiSmokeTest extends AbstractClientE2ETest {
    @Test
    public void guiOpens() throws Exception {
        bot().openInventory();
        // serverClient() drives the server, bot() drives the client
    }
}
```

Each test method gets its own harness — perfect for parallel execution via
Gradle's `maxParallelForks`. Scenarios are independent (each harness picks a
free port via `ServerSocket(0)`, uses its own tempDir) so cross-fork
interference is impossible.

Opt-in via system properties (defaults are `false` → tests SKIP via
`org.junit.Assume`):

| Property | Default | Purpose |
|---|---|---|
| `forge.test.harness.enabled` | `false` | enable `AbstractHeadlessServerTest` |
| `forge.test.client.enabled` | `false` | enable `AbstractClientE2ETest` (needs OpenGL display) |

## Legacy `HeadlessGameTest` runner

The original `TestRegistry` / `TestOrchestrator` / `TestBootstrap` classes
remain for standalone (non-JUnit) usage — e.g. invoking the suite from
`main()` in a CI script. For Forge dev workspaces with JUnit, **prefer the
JUnit base classes above**: they get parallelism, per-test reporting,
`--tests` filtering and IDE integration for free.

## Local commands

Use Java 8.

```bash
./gradlew test
./gradlew build
```

## Publishing

The framework publishes three jars under
`com.github.stannismod.forge:forge-test-framework:<version>`:

| Classifier | Purpose |
|---|---|
| (none) | reobfuscated jar — SRG names, for production Forge runtimes |
| `dev` | deobfuscated jar — MCP names, for Forge dev workspaces (consumed by tests) |
| `sources` | source jar |

### Publish to `mavenLocal()` (`~/.m2/repository`)

```bash
./gradlew publishToMavenLocal
```

Then in the consumer mod's `build.gradle(.kts)`:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    // dev classifier is required for Forge dev workspaces (MCP-named MC classes).
    testImplementation("com.github.stannismod.forge:forge-test-framework:0.2.2:dev")
}
```

### Verify a publication

```bash
ls ~/.m2/repository/com/github/stannismod/forge/forge-test-framework/
```

Expected layout for version `0.2.2`:

```
0.2.2/
├── forge-test-framework-0.2.2.jar         # reobf
├── forge-test-framework-0.2.2-dev.jar     # dev (Forge dev workspace consumes this)
├── forge-test-framework-0.2.2-sources.jar # sources
├── forge-test-framework-0.2.2.module      # Gradle module metadata
└── forge-test-framework-0.2.2.pom         # Maven POM
```
