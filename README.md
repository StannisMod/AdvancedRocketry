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
    testImplementation("com.github.stannismod.forge:forge-test-framework:0.2.1:dev")
}
```

### Verify a publication

```bash
ls ~/.m2/repository/com/github/stannismod/forge/forge-test-framework/
```

Expected layout for version `0.2.1`:

```
0.2.1/
├── forge-test-framework-0.2.1.jar         # reobf
├── forge-test-framework-0.2.1-dev.jar     # dev (Forge dev workspace consumes this)
├── forge-test-framework-0.2.1-sources.jar # sources
├── forge-test-framework-0.2.1.module      # Gradle module metadata
└── forge-test-framework-0.2.1.pom         # Maven POM
```
