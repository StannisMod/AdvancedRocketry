package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Vacuum, suits, and the air a player breathes. Eight scenarios, one client.
 *
 * <p>Every member works the same lever — flip the overworld's atmosphere density and watch what
 * happens to a player who is, or is not, wearing something that protects him — and every one of them
 * reads the outcome on the real client: health as the client renders it, the armour NBT the
 * inventory screen draws.</p>
 *
 * <h2>Why these eight share one harness</h2>
 *
 * <p>Measured 2026-08-07 at 8 forks, from the result XML: 117.6 + 349.4 + 351.3 + 120.2 s across
 * eight client boots — <b>15.6 minutes</b>.</p>
 *
 * <h2>Sharing a GLOBAL mutator, deliberately</h2>
 *
 * <p>Atmosphere density is per-dimension state with no owner, which is exactly the kind of thing the
 * shared-harness rules say does not group. It groups here because of a stronger property: <b>every
 * scenario SETS the density it needs rather than assuming it</b>, and measures that the dimension
 * actually reads that way before it starts its window. A leftover from the scenario before is then
 * overwritten rather than inherited, and a set that has not propagated yet is an ARRANGEMENT
 * failure instead of a silent change of subject. Each also restores the snapshot in a
 * {@code finally}, so a scenario that dies mid-window does not hand the next one a vacuum.</p>
 *
 * <p>The same argument covers the other two globals these scenarios touch: survival mode and
 * {@code naturalRegeneration}. Both are set per scenario; the shared reset additionally puts the
 * game mode and the player's health back, so "the player must start at full health" — a precondition
 * three of these open with — is true by construction rather than by luck.</p>
 *
 * <h2>The fixture geometry changed, and that is the point</h2>
 *
 * <p>All three source classes stood the player at (8.5, 79, 8.5), ordinary overworld terrain height,
 * and each carried an {@code artest fill … minecraft:air} pre-clear because on some world seeds a
 * hillside filled that volume and the player suffocated — damage that a "vacuum hurts an unsuited
 * player" assertion happily accepts for the wrong reason (the ledger entry for that false green is
 * why the pre-clear exists). Here each scenario builds its own stone platform in open air inside its
 * own plot, so there is no terrain to clear and no seed that can put a hill in it.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code OxygenSuitClientStateE2ETest}, {@code ItemSpaceArmorUseFluidE2ETest},
 * {@code ItemSpaceChestSubInventoryDrainE2ETest}, {@code GasChargePadFillsPressureTankE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VacuumAndSuitClientGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final int PAD_Y = Plot.DEFAULT_Y;
    private static final int PAD_DX = 16;
    private static final int PAD_DZ = 16;
    private static final int PAD_EDGE = 8;
    private static final int ROOM_DX = 40;
    private static final int ROOM_DZ = 40;
    private static final int ROOM_Y = Plot.DEFAULT_Y;
    private static final int STAND_DX = PAD_DX + 4;
    private static final int STAND_DZ = PAD_DZ + 4;

    private static final Pattern DENSITY = Pattern.compile("\"atmosphereDensity\":(-?\\d+)");
    private static final Pattern CHEST_AIR = Pattern.compile("\"chestAir\":(-?\\d+)");

    @Override
    protected String subsystem() {
        return "atmosphere-suit";
    }

    // ── shared arrangement ────────────────────────────────────────────────────

    /**
     * Builds this scenario's platform, stands the player on it, strips his armour and drops him to
     * survival — the harness server runs creative, under which {@code AtmosphereNeedsSuit.isImmune}
     * short-circuits regardless of suit and none of these contracts can be observed at all.
     *
     * <p>Survival comes LAST, after the player is measurably standing on the platform: dropping him
     * into survival while he is still falling from the reset's teleport would cost him fall damage
     * that three of these scenarios would read as the subject.</p>
     */
    private void standOnOwnPlatformInSurvival() throws Exception {
        int dim = plot().dim;
        scenario().arranging("build a platform in open air and stand on it");
        String fill = exec("artest fill " + dim + " " + plot().x(PAD_DX) + " " + PAD_Y + " "
                + plot().z(PAD_DZ) + " " + plot().x(PAD_DX + PAD_EDGE - 1) + " " + PAD_Y + " "
                + plot().z(PAD_DZ + PAD_EDGE - 1) + " minecraft:stone");
        scenario().requireArranged("platform fill must succeed: " + fill,
                fill.contains("\"ok\":true"));
        exec("tp @a " + (plot().x(STAND_DX) + 0.5) + " " + (PAD_Y + 1) + " "
                + (plot().z(STAND_DZ) + 0.5));
        bot().waitTicks(10);

        exec("artest player clear-armor");
        exec("gamerule naturalRegeneration false");
        exec("gamemode survival @a");
        bot().waitTicks(10);

        double health = health(bot().reportState());
        scenario().record("healthOnPlatform", health);
        scenario().requireArranged("the player must be standing unhurt on his platform before the"
                + " window opens — anything less means he arrived falling or inside a block, and"
                + " every damage assertion below would be measuring that instead of the vacuum;"
                + " client health=" + health, health >= 20.0);
    }

    /** Reads the dim's baseline density so {@link #restoreDim} can put it back. */
    private int snapshotDensity() throws Exception {
        String planet = exec("artest planet info " + plot().dim);
        Matcher dm = DENSITY.matcher(planet);
        return dm.find() ? Integer.parseInt(dm.group(1)) : 100;
    }

    /**
     * Sets the dimension's density and waits until it READS that way, rather than assuming the write
     * has propagated. {@code set-density} lands on {@code DimensionProperties} a tick or two later,
     * and a suit drains ~1 mB per atmosphere tick while {@code getAtmosphereType} still reports the
     * old value — so a scenario that starts measuring immediately measures the tail of the previous
     * setting.
     */
    private void setDensityAndConfirm(int density, boolean expectBreathable) throws Exception {
        String set = exec("artest atmosphere set-density " + plot().dim + " " + density);
        scenario().requireArranged("set-density " + density + " failed: " + set,
                set.contains("\"ok\":true"));
        ClientPoll.Result<Integer> reads = ClientPoll.until(
                bot()::waitTicks, this::snapshotDensity,
                d -> expectBreathable ? d >= 1 : d == 0, 2, 20);
        scenario().record("densityReadBack", reads.toString());
        scenario().requireArranged("the dimension must READ " + (expectBreathable ? "breathable"
                        + " (>=1)" : "vacuum (0)") + " before the window opens; " + reads,
                reads.satisfied);
    }

    private void restoreDim(int originalDensity) {
        try {
            exec("artest atmosphere set-density " + plot().dim + " " + Math.max(originalDensity, 1));
        } catch (Exception ignored) {
            // Teardown only — the next scenario sets the density it needs and proves it took.
        }
        try {
            exec("gamerule naturalRegeneration true");
        } catch (Exception ignored) {
            // Same.
        }
    }

    /** Server-side chest air via the static "air" NBT route ({@code ItemAirUtils}). */
    private int readChestAir() throws Exception {
        String resp = exec("artest player held-air");
        Matcher m = CHEST_AIR.matcher(resp);
        assertTrue("held-air response must include chestAir: " + resp, m.find());
        return Integer.parseInt(m.group(1));
    }

    /**
     * Server-side chest air via the COMPONENT route. {@code ItemSpaceChest} stores its O2 buffer
     * inside an embedded inventory's pressure-tank FluidStacks rather than as a top-level NBT key,
     * so the static-NBT probe reads 0 for it.
     */
    private int readChestAirComponentRoute() throws Exception {
        String resp = exec("artest player held-air-component-route");
        Matcher m = CHEST_AIR.matcher(resp);
        assertTrue("held-air-component-route response must include chestAir: " + resp, m.find());
        return Integer.parseInt(m.group(1));
    }

    /**
     * CLIENT-rendered chest-slot air: parses the synced {@code armor[2]} NBT string
     * ({@code air:<n>} for the suit buffer, {@code Amount:<n>} for the fluid tank) — the state the
     * HUD and the inventory screen draw from. Returns -1 if absent.
     */
    private int clientChestAir() throws Exception {
        JsonObject items = bot().reportPlayerItems();
        String nbt = items.getAsJsonArray("armor").get(2).getAsJsonObject().get("nbt").getAsString();
        Matcher m = Pattern.compile("\\bair:(\\d+)").matcher(nbt);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("\\bAmount:(\\d+)").matcher(nbt);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }

    private static double health(JsonObject state) {
        return state.has("health") ? state.get("health").getAsDouble() : -1.0;
    }

    /** Polls the CLIENT's rendered health until it drops below {@code from}, or the budget ends. */
    private double waitForHealthDrop(double from) throws Exception {
        double current = from;
        for (int waited = 0; waited < 200 && current >= from; waited += 20) {
            bot().waitTicks(20);
            current = health(bot().reportState());
        }
        return current;
    }

    // ── ItemSpaceChest (component route) ──────────────────────────────────────

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. Counter-test: same suit and tank in a
     * breathable atmosphere. The breathable {@code AtmosphereType.onTick} is a no-op, so
     * {@code protectsFrom} &rarr; {@code decrementAir} is never called and the tank's oxygen stays
     * at its initial value.
     */
    @Test
    public void breathableAtmosphereDoesNotDrainChestTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the suit chest with a full pressure tank");
            String equip = exec("artest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            assertEquals("baseline chestAir", 1000, readChestAirComponentRoute());

            // The client armor[2] NBT syncs a tick or two AFTER the server-side equip; sampling it
            // immediately reads the -1 "not-synced" sentinel. Event-gated, not time-gated.
            ClientPoll.Result<Integer> baseline = ClientPoll.until(
                    bot()::waitTicks, this::clientChestAir, v -> v == 1000, 2, 20);
            scenario().requireArranged("client-rendered baseline must agree (1000); " + baseline,
                    baseline.satisfied);

            scenario().asserting("80 ticks of breathable atmosphere drain nothing");
            bot().waitTicks(80);

            int chestAirAfter = readChestAirComponentRoute();
            assertEquals("chest air must hold steady when the atmosphere doesn't drain; before=1000"
                    + " after=" + chestAirAfter, 1000, chestAirAfter);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. A nearly-drained chest tank transitions
     * the player from suit-protected to suit-fails-{@code isImmune}: once the tank's last mB is
     * drained, {@code decrementAir(stack, 1)} returns 0 &rarr; {@code chest.protectsFromSubstance}
     * returns false &rarr; {@code isImmune} returns false &rarr; vacuum damage applies.
     */
    @Test
    public void drainedChestTankTransitionsToVacuumDamage() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with only three millibuckets of oxygen");
            String equip = exec("artest player equip-space-chest 3");
            scenario().requireArranged("equip-space-chest with low oxygen must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            assertEquals("baseline chestAir = 3", 3, readChestAirComponentRoute());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            scenario().requireArranged("player must start at full health: " + healthStart,
                    healthStart >= 20.0);

            setDensityAndConfirm(0, false);

            scenario().asserting("the tank drains to nothing and the damage then starts");
            // 3 atmosphere ticks drain the tank to 0; subsequent ticks start firing the
            // vacuum-damage path. Poll until damage is observed or the budget elapses.
            double current = waitForHealthDrop(healthStart);
            int chestAirAfter = readChestAirComponentRoute();
            scenario().record("chestAirAfter", chestAirAfter).record("healthAfter", current);

            assertEquals("tank must be fully drained after the wait window; chestAir="
                    + chestAirAfter, 0, chestAirAfter);
            assertTrue("vacuum damage must apply once the tank is drained; health held at "
                    + current + " (started " + healthStart + ")", current < healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code GasChargePadFillsPressureTankE2ETest}. {@code TileGasChargePad.canPerformFunction}
     * scans the 1x2x1 AABB at the pad's position for a player, reads his CHEST slot, and — if the
     * pad's tank holds oxygen — drains it by the missing-air amount and calls
     * {@code fillable.increment}. Player-visible: the suit air meter rises.
     *
     * <p>Why testClient and not testServer: the pad's AABB scan needs a real {@code EntityPlayer} in
     * the world, and a server-side {@code FakePlayer} is forbidden by project policy. The
     * real-client bot IS a real {@code EntityPlayerMP} on the server side of the harness.</p>
     *
     * <p>Pins the END STATE (air rises over the window) rather than a per-tick mB rate.</p>
     */
    @Test
    public void standingOnPoweredPadRefillsSuitAir() throws Exception {
        int dim = plot().dim;
        int px = plot().x(PAD_DX + 1), py = PAD_Y, pz = plot().z(PAD_DZ + 1);

        scenario().arranging("place a charge pad, fill it with oxygen, and suit the player up");
        // The pad replaces one block of this scenario's own platform, so the player stands ON the
        // pad with solid ground either side of him.
        standOnOwnPlatformInSurvival();
        String place = exec("artest place " + dim + " " + px + " " + py + " " + pz
                + " advancedrocketry:oxygencharger");
        scenario().requireArranged("pad placement must succeed: " + place,
                place.contains("\"ok\":true"));
        String inj = exec("artest fluid inject " + dim + " " + px + " " + py + " " + pz
                + " oxygen 8000");
        scenario().requireArranged("fluid inject must succeed: " + inj, inj.contains("\"ok\":true"));

        // initialOxygen=500: half of the pressure tank's 1000 mB capacity, which leaves headroom for
        // the pad to actually add fluid. Equipping a full tank short-circuits the pad's
        // canPerformFunction body (amtFluid = 0) and the test would measure nothing.
        String equip = exec("artest player equip-space-chest 500");
        scenario().requireArranged("equip-space-chest must succeed: " + equip,
                equip.contains("\"ok\":true"));

        scenario().measuring("the suit's air before standing on the pad");
        int airBefore = readChestAirComponentRoute();
        scenario().record("chestAirBefore", airBefore);
        scenario().requireArranged("baseline chest air must be > 0 (the probe filled the pressure"
                + " tank); actual=" + airBefore + " equip=" + equip, airBefore > 0);

        scenario().asserting("standing on the powered pad raises the suit's air, on both sides");
        exec("tp @p " + (px + 0.5) + " " + (py + 1) + " " + (pz + 0.5));
        bot().waitTicks(5);
        // ~5 seconds of natural pad ticking: the pad's parent libVulpes class polls
        // canPerformFunction on a cadence, and 100 ticks covers multiple fill cycles.
        bot().waitTicks(100);

        int airAfter = readChestAirComponentRoute();
        int clientAfter = clientChestAir();
        scenario().record("chestAirAfter", airAfter).record("clientChestAir", clientAfter);
        assertTrue("client-rendered chest tank must show the refill; client=" + clientAfter
                + " serverBefore=" + airBefore, clientAfter > airBefore);
        assertTrue("chest air must increase after standing on a powered, filled GasChargePad;"
                + " before=" + airBefore + " after=" + airAfter, airAfter > airBefore);
    }

    // ── enchanted-armour route ────────────────────────────────────────────────

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Counter-test: the same enchanted suit in a
     * breathable atmosphere. The breathable type's {@code onTick} is a no-op, so the
     * {@code protectsFrom} branch is never evaluated and no decrement fires.
     */
    @Test
    public void suitedPlayerInBreathableDimDoesNotLoseChestAir() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();
            setDensityAndConfirm(100, true);

            scenario().arranging("equip the enchanted air suit with a full buffer");
            String equip = exec("artest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            assertEquals("baseline chest air", 1000, readChestAir());

            scenario().asserting("80 ticks of breathable atmosphere drain nothing");
            bot().waitTicks(80);

            int chestAirAfter = readChestAir();
            scenario().record("chestAirAfter", chestAirAfter);
            assertEquals("client-rendered chest air must hold in breathable atmosphere",
                    1000, clientChestAir());
            assertEquals("chest air must be unchanged in breathable atmosphere; before=1000 after="
                    + chestAirAfter, 1000, chestAirAfter);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Vacuum plus a full enchanted suit: the atmosphere
     * {@code onTick} fires every 10 game ticks and each fire decrements the chest "air" NBT by 1 via
     * {@code ItemAirUtils.ItemAirWrapper}. Health holds, because the four enchanted slots make
     * {@code isImmune} return true and no {@code attackEntityFrom} ever runs.
     */
    @Test
    public void suitedPlayerInVacuumLosesChestAirOverTime() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the enchanted air suit with a full buffer");
            String equip = exec("artest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            assertEquals("baseline chest air before vacuum exposure", 1000, readChestAir());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            setDensityAndConfirm(0, false);

            scenario().asserting("the suit's air drains and the suit keeps the player unhurt");
            // 80 game ticks ~ 8 atmosphere ticks (every 10), each decrementing chest air by 1.
            bot().waitTicks(80);

            int chestAirAfter = readChestAir();
            int clientAir = clientChestAir();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", clientAir)
                    .record("healthAfter", healthAfter);

            assertTrue("chest air must decrease in vacuum with suit; before=1000 after="
                    + chestAirAfter, chestAirAfter < 1000);
            assertTrue("client-rendered chest air must reflect the drain; client=" + clientAir,
                    clientAir >= 0 && clientAir < 1000);
            // Health lost to anything other than vacuum (suffocation, fall, …) is a fixture failure
            // rather than a suit failure, and the message must say which — so the damage SOURCE
            // goes in the text beside the delta.
            assertTrue("suited player must not take vacuum damage; healthStart=" + healthStart
                    + " healthAfter=" + healthAfter + " diag=" + exec("artest player suit-diag"),
                    healthAfter >= healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceArmorUseFluidE2ETest}. Cross-check: a bare-skinned player in vacuum loses
     * HEALTH (the no-suit branch of {@code AtmosphereVacuum.onTick}) and the {@code chestAir} probe
     * reports -1 (no chest stack). Pins that drain is gated on having a chest with a valid air
     * container — no chest, no decrement, just damage.
     */
    @Test
    public void unsuitedPlayerInVacuumLosesNoAirAndTakesDamage() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().measuring("bare-skinned baseline: no chest, full health");
            assertEquals("bare-skinned baseline chest air must be -1", -1, readChestAir());
            double healthStart = health(bot().reportState());
            scenario().record("healthStart", healthStart);
            scenario().requireArranged("player must start at full health, got " + healthStart,
                    healthStart >= 20.0);

            setDensityAndConfirm(0, false);

            scenario().asserting("vacuum damages the unprotected player, and drains no air");
            double current = waitForHealthDrop(healthStart);
            scenario().record("healthAfter", current);
            assertTrue("vacuum damage must apply to a bare-skinned player; health held at " + current
                    + " (started " + healthStart + ")", current < healthStart);
            assertEquals("chestAir must remain -1 throughout — no chest = no decrement path",
                    -1, readChestAir());
        } finally {
            restoreDim(originalDensity);
        }
    }

    // ── the vacuum itself, on the client ──────────────────────────────────────

    /**
     * From {@code OxygenSuitClientStateE2ETest}. Flips the overworld to a vacuum and observes —
     * through the client bridge — that {@code reportState().health} DROPS. That confirms the
     * server-side {@code AtmosphereVacuum} damage tick ({@code attackEntityFrom}) reaches and is
     * visible on the real Minecraft client, end to end.
     *
     * <p>Kept alongside {@link #unsuitedPlayerInVacuumLosesNoAirAndTakesDamage()}, which asserts the
     * same damage plus the no-chest decrement contract: this one is the narrower, older pin and the
     * one the suit tests cross-check themselves against.</p>
     */
    @Test
    public void vacuumDamageReachesTheClient() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().measuring("the client's own health before the vacuum");
            double healthStart = health(bot().reportState());
            scenario().record("healthStart", healthStart);
            scenario().requireArranged("player should start at full health, got " + healthStart,
                    healthStart >= 20.0);

            setDensityAndConfirm(0, false);

            scenario().asserting("the damage tick reaches the client's rendered health");
            // AtmosphereVacuum damages every 10 world ticks. Polling stops as soon as damage
            // registers — robust against slow ticking under parallel forks, and well clear of
            // lethal exposure.
            double current = waitForHealthDrop(healthStart);
            scenario().record("healthAfter", current);
            assertTrue("vacuum damage never reached the client: health held at " + current
                    + " (started " + healthStart + ")", current < healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * From {@code ItemSpaceChestSubInventoryDrainE2ETest}. Vacuum plus a full suit chest (an
     * oxygen-charged pressure tank in slot 0): the atmosphere {@code onTick} fires every 10 game
     * ticks and each fire drains 1 mB from the tank's FluidStack via
     * {@code ItemSpaceChest.decrementAir}. The player takes no damage — {@code isImmune} holds while
     * the chain does.
     */
    @Test
    public void vacuumDrainsOxygenFromChestSubInventoryTank() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            standOnOwnPlatformInSurvival();

            scenario().arranging("equip the suit chest with a full pressure tank");
            String equip = exec("artest player equip-space-chest 1000");
            scenario().requireArranged("equip-space-chest must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            scenario().requireArranged("equip-space-chest must report oxygen filled in tank: "
                    + equip, equip.contains("\"tankFilled\":1000"));
            assertEquals("baseline chestAir read via ItemAirUtils -> ItemSpaceChest.getAirRemaining"
                    + " -> sum of FluidStack amounts must equal 1000",
                    1000, readChestAirComponentRoute());

            double healthStart = health(bot().reportState());
            scenario().measuring("health before the vacuum window").record("healthStart", healthStart);
            setDensityAndConfirm(0, false);

            scenario().asserting("the tank drains through the component route and the suit holds");
            // 80 game ticks ~ 8 atmosphere ticks (every 10), each decrementing the FluidStack by 1.
            bot().waitTicks(80);

            int clientAirAfter = clientChestAir();
            int chestAirAfter = readChestAirComponentRoute();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", clientAirAfter)
                    .record("healthAfter", healthAfter);

            assertTrue("client-rendered chest state must reflect the drain; client=" + clientAirAfter,
                    clientAirAfter < 1000);
            assertTrue("chest air must decrease through the CHEST sub-inventory route in vacuum;"
                    + " before=1000 after=" + chestAirAfter, chestAirAfter < 1000);
            assertTrue("a full suit must keep isImmune=true while the tank has oxygen; healthStart="
                    + healthStart + " healthAfter=" + healthAfter, healthAfter >= healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    // ── a zone that is still PRESSURISED but no longer breathable ─────────────

    /**
     * Builds a sealed room in this scenario's plot, seals it with a powered vent, overwrites the
     * zone's gas so it reads pressurised-but-stale, and stands the player inside it in survival.
     *
     * <p>The dimension around the room is left BREATHABLE on purpose. Every other scenario in this
     * class makes the whole dimension a vacuum, which would make "the player was hurt" true whether
     * or not the room ever became a zone at all. Here the only thing in the world that can hurt
     * anyone is the room's own air — so an arrangement that silently failed to build a zone surfaces
     * as the control below staying at full health, instead of as a false pass.</p>
     *
     * @return the vent's position as {@code dim x y z}, for the probes the scenario then runs
     */
    private String sealStaleZoneAndStandInIt() throws Exception {
        int dim = plot().dim;
        int vx = plot().x(ROOM_DX), vy = ROOM_Y, vz = plot().z(ROOM_DZ);
        String at = dim + " " + vx + " " + vy + " " + vz;

        scenario().arranging("build a sealed room and seal it with a powered vent");
        exec("artest fill " + dim + " " + (vx - 2) + " " + (vy - 1) + " " + (vz - 2)
                + " " + (vx + 2) + " " + vy + " " + (vz + 2) + " minecraft:stone");
        for (int yy = vy + 1; yy <= vy + 2; yy++) {
            exec("artest fill " + dim + " " + (vx - 2) + " " + yy + " " + (vz - 2)
                    + " " + (vx + 2) + " " + yy + " " + (vz + 2) + " minecraft:stone");
            exec("artest fill " + dim + " " + (vx - 1) + " " + yy + " " + (vz - 1)
                    + " " + (vx + 1) + " " + yy + " " + (vz + 1) + " minecraft:air");
        }
        exec("artest fill " + dim + " " + (vx - 2) + " " + (vy + 3) + " " + (vz - 2)
                + " " + (vx + 2) + " " + (vy + 3) + " " + (vz + 2) + " minecraft:stone");

        String placed = exec("artest place " + at + " advancedrocketry:oxygenVent");
        scenario().requireArranged("the vent must place: " + placed, placed.contains("\"placed\":true"));
        exec("artest energy inject " + at + " 1000000");
        exec("artest fluid inject " + at + " oxygen 16000");
        exec("artest tile force-tick " + at + " 1");
        exec("artest vent reseal " + at);
        exec("artest tile force-tick " + at + " 5");

        // Pressurised, and short of oxygen: the three partials still total one atmosphere, so this
        // is emphatically NOT the vacuum every other scenario here uses — it is a room whose air has
        // been breathed. 50 000 sits below lifeSupportMinPartialO2's 160 000 default.
        String setAir = exec("artest vent setair " + at + " 790000 50000 160000");
        scenario().requireArranged("setair must take: " + setAir, setAir.contains("\"ok\":true"));

        String info = exec("artest vent info " + at);
        scenario().record("ventInfo", info);
        scenario().requireArranged("the room must actually BE a zone before anyone stands in it, and"
                + " it must read as pressurised-but-stale rather than as vacuum: " + info,
                info.contains("\"airO2\":50000") && info.contains("\"airPressure\":100")
                        && info.contains("lowO2"));

        // Stand him in the room while still CREATIVE, and check "unhurt" THERE. Creative
        // short-circuits AtmosphereNeedsSuit.isImmune, so the room cannot hurt him yet — which is
        // what makes the check meaningful: it can only fail on arriving in a wall or falling, the
        // two things it exists to catch. Checking it in survival instead measured the subject:
        // the room bit once during the settling ticks and the precondition read 19.0, i.e. this
        // scenario refusing to run because its own contract had already fired.
        exec("tp @a " + (vx + 0.5) + " " + (vy + 1) + " " + (vz + 0.5));
        bot().waitTicks(10);

        double health = health(bot().reportState());
        scenario().record("healthInRoom", health);
        scenario().requireArranged("the player must be standing unhurt INSIDE the sealed room before"
                + " the window opens; client health=" + health, health >= 20.0);
        return at;
    }

    /**
     * Closes the arrangement: survival LAST, with no settling tick after it, so the damage window
     * starts where the scenario says it does and not a second earlier.
     */
    private double openSurvivalWindow() throws Exception {
        exec("gamerule naturalRegeneration false");
        exec("gamemode survival @a");
        bot().waitTicks(2);
        double health = health(bot().reportState());
        scenario().record("healthAtWindowOpen", health);
        return health;
    }

    /**
     * The control, and the reason the scenario below means anything: a room whose oxygen has fallen
     * under the breathable floor hurts someone standing in it with no suit.
     *
     * <p>This is the half of the suit-fallback contract that is NOT a breach. A breach is already
     * covered by this class's vacuum scenarios; this is the other failure the life-support design
     * names — regeneration not keeping up, leaving a room still full of gas and still lethal.</p>
     */
    @Test
    public void staleZoneAirHurtsAnUnsuitedPlayer() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            setDensityAndConfirm(100, true);
            String at = sealStaleZoneAndStandInIt();
            exec("artest player clear-armor");

            scenario().measuring("health before the stale-air window");
            double healthStart = openSurvivalWindow();

            scenario().asserting("stale zone air damages an unsuited player");
            double healthAfter = waitForHealthDrop(healthStart);
            scenario().record("healthAfter", healthAfter)
                    .record("ventInfoAfter", exec("artest vent info " + at));

            assertTrue("a pressurised room below the breathable oxygen floor must hurt an unsuited"
                    + " player — if this passes at full health the room never became a stale zone and"
                    + " the suited scenario proves nothing; healthStart=" + healthStart
                    + " healthAfter=" + healthAfter, healthAfter < healthStart);
        } finally {
            restoreDim(originalDensity);
        }
    }

    /**
     * The suit fallback, on the case that is not a breach: the suit is a personal contour ON TOP of
     * the zone air, so in a room life support can no longer keep breathable the crew breathe from
     * the suit — and pay for it.
     *
     * <p>Both halves are asserted because either alone is satisfiable by a broken system: unchanged
     * health alone is what a room that never went stale looks like (which is what the control above
     * rules out), and a falling air buffer alone is what a suit draining without protecting anybody
     * looks like.</p>
     */
    @Test
    public void staleZoneAirDrainsTheSuitAndNotTheCrew() throws Exception {
        int originalDensity = snapshotDensity();
        try {
            setDensityAndConfirm(100, true);
            sealStaleZoneAndStandInIt();

            scenario().arranging("equip an air-carrying suit");
            String equip = exec("artest player equip-airsuit 1000");
            scenario().requireArranged("equip-airsuit must succeed: " + equip,
                    equip.contains("\"ok\":true"));
            assertEquals("the suit must start full so any fall belongs to this window",
                    1000, readChestAir());

            // Survival only once the suit is on: an unprotected settling tick here would spend the
            // wearer's health on the very hazard this scenario claims the suit covers.
            scenario().measuring("health and suit air before the stale-air window");
            double healthStart = openSurvivalWindow();

            scenario().asserting("the suit covers the stale zone, and spends air doing it");
            // AtmosphereLowOxygen.onTick gates on `% 20 == 0`, and the isImmune call that spends the
            // air sits inside that gate — so this window is ~6 chances to spend, not 120.
            bot().waitTicks(120);

            int chestAirAfter = readChestAir();
            int clientAirAfter = clientChestAir();
            double healthAfter = health(bot().reportState());
            scenario().record("chestAirAfter", chestAirAfter).record("clientChestAir", clientAirAfter)
                    .record("healthAfter", healthAfter);

            assertTrue("the suit must protect its wearer from stale zone air; healthStart="
                    + healthStart + " healthAfter=" + healthAfter, healthAfter >= healthStart);
            assertTrue("and it must PAY for that protection — a fallback that costs nothing is not a"
                    + " fallback; before=1000 after=" + chestAirAfter, chestAirAfter < 1000);
            assertTrue("the client must render the drained suit, not a stale full one; client="
                    + clientAirAfter, clientAirAfter < 1000);
        } finally {
            restoreDim(originalDensity);
        }
    }
}
