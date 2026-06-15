# SOP: Mixin / coremod / ASM — dev vs prod gotchas

## Context

**Re-read before touching anything under `mixin/`, `asm/`,
`mixins.advancedrocketry.json`, access transformers, or the coremod
plugin.** This subsystem has produced the most expensive bugs in the
project (bug ledger #4, #6, and a launch-crash in a packaged modpack)
because the dev workspace and a packaged/modpack jar load classes
differently, and the failures are often **silent**.

## The root cause behind every entry here

In the **dev workspace** classes carry **MCP names**, there is no jar
manifest, and there is no separate Mixin host. In a **packaged / modpack
jar** classes are **SRG/reobf-named**, the manifest carries
`MixinConfigs`, and **MixinBooter** owns Mixin on the `LaunchClassLoader`.
Code that works in one environment can no-op or crash in the other.

## Rule 1 — Let the Mixin host own bootstrap; never self-bootstrap

**Do NOT call `MixinBootstrap.init()` / `Mixins.addConfiguration()` from
the coremod.** The coremod loads on the `AppClassLoader`; referencing
`org.spongepowered.asm.*` there re-initiates loading of
`GlobalProperties$Keys` on a second classloader → JVM throws
`LinkageError` ("loader constraint violation") → FML crashes at launch.

A `try/catch` around the calls is **NOT enough**: catching the
`LinkageError` leaves a half-initialised Mixin service that poisons the
host's `MixinTweaker`, which then dies with **"No mixin host service is
available."**

**The supported pattern** — implement MixinBooter's `IEarlyMixinLoader`
on the coremod plugin and return the config name; the host queues it on
the right classloader, in both dev and prod:

```java
public class AdvancedRocketryPlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.advancedrocketry.json");
    }
    // no MixinBootstrap / Mixins references anywhere in this class
}
```

## Rule 2 — Refmap lookups fail in the dev classloader

A mixin whose `@Inject` / `@Redirect` / `@Accessor` targets a renamed MC
method relies on the **refmap** translating MCP→SRG. In dev that
translation is wrong (runtime is MCP-named), with three failure shapes:

- `@Accessor` → **crashes** with `InvalidAccessorException` (ledger #4,
  found via `runClient`).
- `@Inject` / `@Redirect` → **silently no-op** (ledger #6 — the feature
  just doesn't work in dev; works in a reobf jar).
- Because `mixins.advancedrocketry.json` is `"required": true`, the
  **first** mixin to fail PREINJECT aborts the **whole config**, so the
  other mixins silently never apply.

**Mitigations:**
- `-Dmixin.env.disableRefMap=true` on dev `runClient`/`runServer` (and
  the harness layers inherit it) — makes dev use the runtime names
  directly. This fixed ledger #6.
- Prefer an **access transformer** over `@Accessor` when you only need to
  widen a field/method: an AT applies at classload independent of refmap
  state, in both dev and reobf. This fixed ledger #4 (`AccessorWorld` →
  `public ... World.field_72986_A`).
- When debugging, `-Dmixin.debug=true` prints which mixin failed first.

## Rule 3 — Diagnose with the right tool

`@Accessor` failures crash loudly (easy). `@Inject`/`@Redirect` failures
are silent — verify the injection actually fires by instrumenting the
target with a print marker and running the class in **isolation**, or add
a server-side probe that drives the hooked path (see
[`artest-probe-authoring.md`](./artest-probe-authoring.md)).

## Rule 4 — Never rename what a save or another loader depends on

Registry IDs, NBT keys, lang keys, packet field order, capability keys —
see [`save-and-wire-compat.md`](./save-and-wire-compat.md). The IDE's
`rename_refactoring` must never touch these
([`mcp-intellij-usage.md`](./mcp-intellij-usage.md)).

## Prevention

- [ ] Coremod registers mixins via `IEarlyMixinLoader`, not
      `MixinBootstrap.init()`.
- [ ] New `@Inject`/`@Redirect` verified to actually fire in dev (marker
      or probe), not assumed.
- [ ] Field/method widening done with an AT, not `@Accessor`, unless
      refmap correctness is proven.
- [ ] A new mixin that fails won't silently disable the rest — test the
      behaviours of the *other* mixins after adding one.

## Related

- `.agent/history/known-bugs-ledger.md` — entries #4, #6 (the source
  cases).
- [`save-and-wire-compat.md`](./save-and-wire-compat.md),
  [`config-flag-disableability.md`](./config-flag-disableability.md)
  (gating weather mixins via `IEarlyMixinLoader`).
