# SOP: Adding a Forge capability (by example)

## Context

Read before attaching a new piece of state to tiles/items/entities that
several systems read (part wear, suit air, future per-object properties).
The project has a worked reference — `CapabilityWear` modelled on
`CapabilitySpaceArmor` — and new capabilities should follow it for
consistency and to keep consequence-reads decoupled from concrete tile
classes.

## The shape (from `CapabilityWear` / `IPartWear`)

1. **Interface** — the capability's contract, MC-free where possible:
   `IPartWear { int getStage(); int getMaxStage(); void setStage(int);
   boolean transition(); }`.
2. **Holder class** with the injected `Capability` and a convenience
   accessor:
   ```java
   public class CapabilityWear {
       @CapabilityInject(IPartWear.class)
       public static Capability<IPartWear> PART_WEAR = null;

       public static IPartWear get(@Nullable TileEntity te) {
           return (te == null || PART_WEAR == null) ? null
                   : te.getCapability(PART_WEAR, null);
       }
       public static void register() {
           CapabilityManager.INSTANCE.register(IPartWear.class,
               new Capability.IStorage<IPartWear>() { /* no-op: host persists */ },
               DefaultPartWear::new);
       }
   }
   ```
3. **Register in postInit** alongside the sibling capabilities (same place
   `CapabilitySpaceArmor` registers).
4. **Host tile implements** the interface and overrides
   `hasCapability` / `getCapability` to return itself for the cap; it
   persists the state in its own NBT (so the `IStorage` is a no-op). A
   shared base (`TileWearable`) carries this for several block types.

## Read consequences through the cap, not a concrete cast

Consumers resolve the capability (`CapabilityWear.get(te)`) instead of
casting to `TileBrokenPart`. This is what let wear extend from motors to
fuel tanks and seats (different tile classes, same cap) without touching
every consequence site. New code that reacts to the state must go through
the cap too.

## Compatibility

The host's NBT keys are save contract — additive and absence-tolerant
(see [`save-and-wire-compat.md`](./save-and-wire-compat.md)). Don't rename
the capability or its NBT keys once shipped.

## Prevention

- [ ] Interface + holder + postInit register, mirroring the sibling cap.
- [ ] Host tile implements the interface and persists via its own NBT.
- [ ] Consumers read via `Capability.get(te)`, not a concrete cast.
- [ ] NBT keys additive + absence-tolerant.

## Related

- [`save-and-wire-compat.md`](./save-and-wire-compat.md),
  [`config-flag-disableability.md`](./config-flag-disableability.md)
  (gating consequences read through the cap).
