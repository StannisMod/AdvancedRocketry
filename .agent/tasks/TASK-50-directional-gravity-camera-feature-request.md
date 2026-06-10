# TASK-50: Directional Gravity + Camera Rotation (feature request)

**Status**: 📋 Backlog (feature request — not started)
**Created**: 2026-06-10
**Assignee**: Manual

---

## Context

**Problem**:
AR planets only support scalar gravity (a per-dimension multiplier applied by
`MixinEntityGravity` → `GravityHandler.applyGravity`). The upstream kaduvill
tree carried the skeleton of a much bigger experimental feature: **directional
gravity** — gravity pulling along an arbitrary axis (walls/ceiling as "down"),
with matching player movement physics and a camera that rotates so the chosen
gravity direction looks like "down".

The feature was never finished upstream (~95% of it was commented out), and we
deliberately did not port the dead code during the ASM→Mixin migration. This
task records where the prototype lives so it can be revisited.

**Goal**:
Decide whether to resurrect directional gravity; if yes, re-implement it on the
Mixin platform using the upstream prototype as the design reference.

---

## Where the prototype lives (forensics)

**Removed from our tree in commit `877d1495`**
("refactor: restore Mixin platform over PR ASM coremod") — it deleted
`src/main/java/zmaster587/advancedRocketry/asm/ClassTransformer.java`, which
carried the hook skeleton. View it with:

```bash
git show 877d1495^:src/main/java/zmaster587/advancedRocketry/asm/ClassTransformer.java
# identical copy in the upstream tip:
git show c1c791d3:src/main/java/zmaster587/advancedRocketry/asm/ClassTransformer.java
```

Prototype inventory (all in upstream tip `c1c791d3`, PR base `280dd59b`):

| Piece | Location | State upstream |
|---|---|---|
| `EntityLivingBase` hooks: `moveEntity`, `moveFlying`, `jump`, `moveEntityWithHeading`, `getLookVec` + injected `gravRotation` field (1=N, 2=E, 3=S, 4=W, 5=up) | `ClassTransformer.java` lines ~308–462 | ~95% commented out; only field injection + ctor init were active |
| `EntityRenderer.orientCamera` hooks → `ClientHelper.transformCamera()/transformCamera2()` | `ClassTransformer.java` lines ~465–532 | fully commented out |
| Actual math (movement transforms, jump vector, modified look vector, camera transform) | `client/ClientHelper.java` | fully commented out; **the dead file still exists in our working tree** (`src/main/java/zmaster587/advancedRocketry/client/ClientHelper.java`, body commented) |

Conclusion from the June 2026 port audit: this was experimental and
non-functional even upstream — dropping it was NOT a porting regression. It is
a feature request, not a lost feature.

---

## Acceptance Criteria (if/when picked up)

- [ ] A living entity on a dimension/zone with directional gravity accelerates
      along the configured axis (not just -Y).
- [ ] Player movement (walk, jump, flying drift) is consistent with the rotated
      gravity frame.
- [ ] Camera orients so the gravity axis reads as "down"; HUD stays usable.
- [ ] Scalar per-dimension gravity (existing `MixinEntityGravity` behaviour)
      is unchanged when no direction override is set.

---

## Implementation sketch (Mixin platform)

- `gravRotation` per-entity state: capability or `EntityDataManager` parameter
  instead of ASM field injection.
- `@Mixin(EntityLivingBase)` for `travel`/`jump`/`getLookVec` (1.12.2 names:
  `travel(FFF)`, `jump()`, `getLook(F)`) replacing the commented ASM hooks.
- Client: `@Mixin(EntityRenderer)` around `orientCamera(F)` for the camera
  transform; resurrect the math from the commented `ClientHelper`.
- Add behavioural pins to `MixinHookBehaviourPinsTest` + a client e2e via the
  FTF bot (real key injection + client-side readback per SOP).

---

## Out of Scope

- PlusTiC Portly rocket yaw compat (separate small item; transformer dropped in
  `6a0dd09b`, config stub still at `ARConfiguration.java:62`).
- Any change to scalar gravity behaviour or `GravityHandler`.

---

## Refs

- Removal commit: `877d1495` (ClassTransformer deleted; "J21 anchor moot")
- Upstream prototype: `c1c791d3` (kaduvill/1.12 tip, merged for attribution in
  `6d011231`, PR #70), PR base `280dd59b`
- Dead math file still in tree: `src/main/java/zmaster587/advancedRocketry/client/ClientHelper.java`
- Port audit that surfaced this: June 2026 kaduvill-port analysis (fix/various)

---

**Last Updated**: 2026-06-10
