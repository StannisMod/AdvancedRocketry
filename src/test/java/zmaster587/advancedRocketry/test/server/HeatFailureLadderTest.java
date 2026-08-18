package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What being too hot COSTS: the two rungs of the failure ladder that a ship can reach today.
 *
 * <p>The ladder's clause is an ORDER and a SUBJECT per rung, never a threshold. So every scenario
 * here reads the threshold off the server and arranges the world on the far side of it - a test that
 * named a temperature would be restating a tuned number instead of the rule that crossing it changes
 * what the room, or the drive, IS.</p>
 *
 * <ul>
 *   <li><b>Crew damage - subject: the zone's air.</b> An overheated compartment presents one of the
 *       hostile atmospheres a scorching planet already presents, so the same suit protects against
 *       it and there is no second damage path. What is pinned here is that the room turns hostile
 *       and turns back, and that the gases only choose which variant of hostile it is.</li>
 *   <li><b>Hyperdrive refusal - subject: the coolant the drive is bolted to.</b> Free, and above the
 *       commit line: the pilot is told no and his capacitor is still full.</li>
 * </ul>
 *
 * <p><b>What this cannot cover, and why.</b> The crew rung's damage itself is not observable on this
 * tier: {@code AtmosphereHandler.onTick} returns before the effect path for a connectionless player,
 * which is every player a headless server can supply, so a health assertion here would measure the
 * harness rather than the mechanic. The damage a hostile atmosphere does is pinned where a real
 * player exists - {@code test/client/VacuumAndSuitClientGroupE2ETest
 * .overheatedZoneAirHurtsAnUnsuitedCrewman} - and the derivation that puts the player in one is
 * pinned here and in {@code unit/AirStateTest}.</p>
 */
public class HeatFailureLadderTest extends AbstractSharedServerTest {

    private static final Pattern CONFIG_VALUE = Pattern.compile("\"value\":(-?\\d+)");
    private static final Pattern ATMOSPHERE_TYPE = Pattern.compile("\"type\":\"([^\"]*)\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\":\"([^\"]*)\"");

    /** The compartment: a sealed room with a vent, well clear of every other fixture. */
    private static final int ROOM_X = 2000;
    private static final int ROOM_Y = 64;
    private static final int ROOM_Z = 3100;

    /** The ships. One with coolant against its drive, one with none - the second is the control. */
    private static final String SHIP_COOLED = "2840 82 2840";
    private static final String NAV_COOLED = "0 2840 80 2840";
    private static final String SHIP_DRY = "2900 82 2900";
    private static final String NAV_DRY = "0 2900 80 2900";

    /** The drive's own coolant run: three pipes laid along the top of the generator. */
    private static final int PIPES = 3;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    // ─── Rung one: the air is what hurts the crew ──────────────────────────────

    /**
     * A compartment past the crew threshold is a hostile atmosphere, and cooling it gives the room
     * back.
     *
     * <p>The second half is what makes the first a measurement. "The room reports VeryHot" would
     * also be true of a build that reported VeryHot for every room, and of one that latched on the
     * first hazard it ever saw - which is a real failure mode this very code path has had.</p>
     */
    @Test
    public void anOverheatedCompartmentTurnsHostileAndCoolingItGivesTheRoomBack() throws Exception {
        buildRoomWithVent();
        int veryHot = configInt("shipHeatCrewVeryHotKelvin");
        int ambient = configInt("shipHeatAmbientKelvin");
        assertTrue("premise: the rung must be switched on, or this scenario asks nothing",
                veryHot > 0 && ambient < veryHot);

        setAir(790_000, 210_000, 0, ambient * 1000);
        assertEquals("premise: a room at cabin temperature must be an ordinary breathable room",
                "PressurizedAir", atmosphereInRoom());

        setAir(790_000, 210_000, 0, (veryHot + 10) * 1000);
        assertEquals("past the threshold the room itself is the hazard, and it is the same hazard a "
                + "scorching planet presents - so the suit that works there works here",
                "VeryHot", atmosphereInRoom());

        setAir(790_000, 210_000, 0, ambient * 1000);
        assertEquals("and the room must come BACK when it is cooled: a rung that latched would leave "
                + "a crew in a repaired ship still dying in it", "PressurizedAir", atmosphereInRoom());
    }

    /**
     * The temperature chooses the rung; the gases choose which variant of it.
     *
     * <p>Two facts about one room - it is cooking you and there is nothing to breathe - and the
     * existing atmosphere types say both at once. Asserting the plain variant alongside is what
     * stops this passing on a build that simply always answers with the NoO2 one.</p>
     */
    @Test
    public void hotAirWithNothingToBreatheIsBothHazardsAtOnce() throws Exception {
        buildRoomWithVent();
        int superheated = configInt("shipHeatCrewSuperheatedKelvin");
        assertTrue("premise: the harsher rung must be switched on", superheated > 0);

        setAir(1_000_000, 0, 0, (superheated + 10) * 1000);
        assertEquals("a suffocating room that is also lethally hot must say so",
                "SuperheatedNoOxygen", atmosphereInRoom());

        setAir(790_000, 210_000, 0, (superheated + 10) * 1000);
        assertEquals("and the same temperature with air to breathe is the plain lethal one",
                "Superheated", atmosphereInRoom());
    }

    // ─── Rung four: the drive will not fire ────────────────────────────────────

    /**
     * A drive whose coolant is past the threshold refuses, says why, and charges nothing for it.
     *
     * <p>The unspent capacitor is half the clause. A refusal raised below the commit line costs the
     * pilot nothing, and the whole reason this check lives at the gate rather than at the burst is
     * that a paid refusal is the failure the jump sequence is built to prevent.</p>
     */
    @Test
    public void anOverheatedDriveRefusesToFireAndTheRefusalIsFree() throws Exception {
        buildArmedShip(SHIP_COOLED, NAV_COOLED);
        layCoolantAlongTheGenerator();
        int refusal = configInt("shipHeatDriveRefusalKelvin");
        assertTrue("premise: the rung must be switched on", refusal > 0);

        String cold = exec("artest drive info 0 " + SHIP_COOLED);
        assertTrue("premise: the ship must be able to jump before it is cooked: " + cold,
                cold.contains("\"allowed\":true"));
        long coldReading = field(cold, "driveCoolantMilliK");
        assertTrue("premise: the drive must actually have found the coolant against it, or the "
                + "refusal below would be about a loop nobody measured: " + cold, coldReading > 0);
        assertTrue("premise: and that coolant must start well below the threshold: " + cold,
                coldReading < refusal * 1000L);
        long chargeBefore = field(cold, "charge");
        assertTrue("premise: with an empty bank the gate would refuse for a different reason: " + cold,
                chargeBefore >= field(cold, "burstCost"));

        String cooked = cook(refusal);
        assertTrue("premise: the loop must actually have been driven past the threshold: " + cooked,
                field(cooked, "temperatureMilliK") >= refusal * 1000L);

        String hot = exec("artest drive info 0 " + SHIP_COOLED);
        assertTrue("a drive whose coolant is past the threshold must not fire: " + hot,
                hot.contains("\"allowed\":false"));
        assertEquals("and the pilot must be told which of the refusals this is: " + hot,
                "msg.jumpgate.driveoverheated", text(hot, MESSAGE));
        assertTrue("the gate must be reading the loop bolted to the generator: " + hot,
                field(hot, "driveCoolantMilliK") >= refusal * 1000L);

        String pressed = exec("artest drive press 0 " + SHIP_COOLED);
        assertTrue("a refused jump must not wind the drive up: " + pressed,
                pressed.contains("\"spooling\":false"));
        assertEquals("and must cost the pilot nothing - this refusal is above the commit line: "
                + pressed, chargeBefore, field(exec("artest drive info 0 " + SHIP_COOLED), "charge"));

        String shed = exec("artest heat cycle 0 " + pipeAt(0) + " 0 1");
        assertTrue("premise: the loop must actually have shed what it was holding: " + shed,
                field(shed, "temperatureMilliK") < refusal * 1000L);
        assertTrue("cooling the ship is the whole of the fix - the gate is read-only and remembers "
                        + "nothing: " + exec("artest drive info 0 " + SHIP_COOLED),
                exec("artest drive info 0 " + SHIP_COOLED).contains("\"allowed\":true"));
    }

    /**
     * A drive with no coolant against it is unmeasured, and an unmeasured drive is not refused.
     *
     * <p>Zero is the answer for "nobody is watching this", and it must not read as "cold enough" by
     * accident nor as "too hot" by defaulting the safe way: a ship built before anyone laid a pipe
     * has to keep flying.</p>
     */
    @Test
    public void aDriveWithNoCoolantAgainstItIsNotMeasuredAndNotRefused() throws Exception {
        buildArmedShip(SHIP_DRY, NAV_DRY);

        String info = exec("artest drive info 0 " + SHIP_DRY);

        assertEquals("with nothing bolted to the drive there is nothing to read: " + info,
                0L, field(info, "driveCoolantMilliK"));
        assertTrue("and an unmeasured drive must still be allowed to jump: " + info,
                info.contains("\"allowed\":true"));
    }

    // ─── the rig ───────────────────────────────────────────────────────────────

    /** A sealed room with a powered, sealed vent - the same rig the zone-air tests run on. */
    private void buildRoomWithVent() throws Exception {
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y - 1) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + ROOM_Y + " " + (ROOM_Z + 2) + " minecraft:stone");
        for (int yy = ROOM_Y + 1; yy <= ROOM_Y + 2; yy++) {
            exec("artest fill 0 " + (ROOM_X - 2) + " " + yy + " " + (ROOM_Z - 2)
                    + " " + (ROOM_X + 2) + " " + yy + " " + (ROOM_Z + 2) + " minecraft:stone");
            exec("artest fill 0 " + (ROOM_X - 1) + " " + yy + " " + (ROOM_Z - 1)
                    + " " + (ROOM_X + 1) + " " + yy + " " + (ROOM_Z + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z + 2) + " minecraft:stone");

        String vent = exec("artest place 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        String energy = exec("artest energy inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " 1000000");
        assertTrue("energy inject failed: " + energy, energy.contains("\"ok\":true"));
        String oxygen = exec("artest fluid inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " oxygen 16000");
        assertTrue("oxygen inject failed: " + oxygen, oxygen.contains("\"ok\":true"));
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 1");
        exec("artest vent reseal 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z);
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 5");
    }

    private void setAir(int n2, int o2, int co2, int milliK) throws Exception {
        String set = exec("artest vent setair 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " " + n2 + " " + o2 + " " + co2 + " " + milliK);
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    /** What a person standing in the compartment breathes, as the handler publishes it. */
    private String atmosphereInRoom() throws Exception {
        String resp = exec("artest atmosphere get 0 " + ROOM_X + " " + (ROOM_Y + 1) + " " + ROOM_Z);
        Matcher m = ATMOSPHERE_TYPE.matcher(resp);
        assertTrue("no atmosphere type in: " + resp, m.find());
        return m.group(1);
    }

    /** A ship that can jump: a drive, a full bank, a navigation computer, a target, armed. */
    private void buildArmedShip(String afc, String nav) throws Exception {
        String built = exec("artest drive build 0 " + afc + " 4 8 0 0 0");
        assertTrue("drive build failed: " + built, !built.contains("\"error\""));
        exec("artest drive charge 0 " + afc + " full");
        exec("artest nav place " + nav);
        exec("artest nav link " + nav + " " + afc);
        exec("artest nav target " + nav + " 7 0 0");
        String armed = exec("artest drive arm 0 " + afc + " on");
        assertTrue("the ship must be armed, or the gate refuses for a different reason: " + armed,
                armed.contains("\"armed\":true"));
    }

    /**
     * Coolant pipe along the top of the generator. The drive fixture stands the generator two blocks
     * from the flight computer with its coils running one way, so the row above it is clear - and
     * "bolted to the drive" is exactly this: pipe touching the machine's own footprint.
     */
    private void layCoolantAlongTheGenerator() throws Exception {
        for (int i = 0; i < PIPES; i++) {
            String placed = exec("artest place 0 " + pipeAt(i) + " advancedrocketry:heatPipe");
            assertTrue("pipe place failed at " + pipeAt(i) + ": " + placed,
                    placed.contains("\"placed\":true"));
        }
        String solved = exec("artest subnet solve all 0 1");
        assertTrue("the loop never solved, so it does not exist yet: " + solved,
                solved.contains("\"ticksSolved\":1"));
    }

    /** Charge the drive's loop past {@code refusalKelvin} in one call, and answer what it reached. */
    private String cook(int refusalKelvin) throws Exception {
        String empty = exec("artest heat cycle 0 " + pipeAt(0) + " 0 1");
        long capacity = field(empty, "heatCapacity");
        assertTrue("premise: the loop must have thermal mass to charge: " + empty, capacity > 0);
        int ambient = configInt("shipHeatAmbientKelvin");
        long charge = (refusalKelvin + 100L - ambient) * capacity;
        return exec("artest heat cycle 0 " + pipeAt(0) + " " + charge + " 1");
    }

    /** The i-th pipe of the drive's coolant run: above the generator, running away from the coils. */
    private String pipeAt(int i) {
        String[] afc = SHIP_COOLED.split(" ");
        int x = Integer.parseInt(afc[0]) + 2;
        int y = Integer.parseInt(afc[1]) + 1;
        int z = Integer.parseInt(afc[2]) - i;
        return x + " " + y + " " + z;
    }

    private int configInt(String key) throws Exception {
        String resp = exec("artest config get " + key);
        assertTrue("config get " + key + " failed: " + resp, resp.contains("\"ok\":true"));
        Matcher m = CONFIG_VALUE.matcher(resp);
        assertTrue("no value in: " + resp, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static long field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue("expected a numeric field " + name + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String text(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        assertTrue("pattern " + pattern.pattern() + " not found in: " + json, m.find());
        return m.group(1);
    }
}
