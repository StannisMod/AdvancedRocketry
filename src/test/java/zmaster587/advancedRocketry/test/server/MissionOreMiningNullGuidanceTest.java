package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 3 — C049 (FIND-023) reproduction + regression guard.
 *
 * <p>Contract under test: {@link zmaster587.advancedRocketry.mission.MissionOreMining#onMissionComplete()}
 * must not crash the server tick when the rocket's guidance computer is missing.
 * The unconditional chip-refill step dereferences
 * {@code rocketStorage.getGuidanceComputer()} with no null guard; the only guard
 * sits inside the {@code drillingPower != 0f} harvest branch, so a mission that
 * completes with {@code drillingPower == 0} and a null guidance computer NPEs on
 * the {@code ServerTickEvent} thread (uncaught → server-tick crash).</p>
 *
 * <p>The probe reproduces the "no guidance computer" runtime state (as a reload
 * that dropped the tile, or a rocket that never had one) by stripping the
 * guidance computer from the mission's rocket storage, then completes the
 * mission. Pre-fix the completion tick NPEs (surfaced via the {@code /artest}
 * error envelope); post-fix a hoisted top-of-method guard returns cleanly.</p>
 */
public class MissionOreMiningNullGuidanceTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");
    private static final Pattern MISSION_ID = Pattern.compile("\"missionId\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private int buildAndAssembleRocket(int baseX) throws Exception {
        int baseY = 64;
        int baseZ = 720;
        ok(client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1));
        int by = Integer.parseInt(bp.group(2));
        int bz = Integer.parseInt(bp.group(3));
        ok(client().execute("artest rocket assemble 0 " + bx + " " + by + " " + bz));
        String list = ok(client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(list);
        int lastId = -1;
        while (rim.find()) lastId = Integer.parseInt(rim.group(1));
        assertTrue("no rocket after assemble: " + list, lastId >= 0);
        return lastId;
    }

    private long startOreMission(int rocketId, float drillingPower) throws Exception {
        String start = ok(client().execute(
                "artest mission start-ore 0 " + rocketId + " 1000 " + drillingPower));
        assertFalse("start-ore must not error: " + start, start.contains("\"error\""));
        Matcher mm = MISSION_ID.matcher(start);
        assertTrue("missing missionId: " + start, mm.find());
        return Long.parseLong(mm.group(1));
    }

    /** The bug: drillingPower == 0 skips the harvest branch (the only null
     *  guard), so a null guidance computer NPEs at the unconditional chip
     *  refill. Completion must instead finish cleanly. */
    @Test
    public void oreCompletionWithNullGuidanceDrillingZeroDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9300);
        long mid = startOreMission(rid, 0.0f);

        String strip = ok(client().execute("artest mission strip-guidance " + mid));
        assertTrue("strip-guidance failed: " + strip, strip.contains("\"ok\":true"));
        assertTrue("guidance computer must be gone: " + strip,
                strip.contains("\"hasGuidanceComputer\":false"));

        String complete = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("completing an ore mission with a missing guidance computer "
                        + "must not NPE the server tick (C049): " + complete,
                complete.contains("NullPointerException"));
        assertFalse("complete-now must not error: " + complete, complete.contains("\"error\""));
        assertTrue("complete-now must report success: " + complete,
                complete.contains("\"ok\":true"));
    }

    /** Control: the sibling branch (drillingPower != 0 with a null guidance
     *  computer) already early-returns without crashing — post-fix both
     *  branches must remain crash-free, unifying the asymmetry. */
    @Test
    public void oreCompletionWithNullGuidanceDrillingNonZeroDoesNotCrash() throws Exception {
        int rid = buildAndAssembleRocket(9400);
        long mid = startOreMission(rid, 1.0f);

        String strip = ok(client().execute("artest mission strip-guidance " + mid));
        assertTrue("strip-guidance failed: " + strip, strip.contains("\"ok\":true"));

        String complete = ok(client().execute("artest mission complete-now " + mid));
        assertFalse("completing with a missing guidance computer must not NPE: " + complete,
                complete.contains("NullPointerException"));
        assertTrue("complete-now must report success: " + complete,
                complete.contains("\"ok\":true"));
    }
}
