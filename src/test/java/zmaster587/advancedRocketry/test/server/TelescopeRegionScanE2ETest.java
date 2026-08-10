package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The observatory's region survey, driven on a real server through the machine itself.
 *
 * <p>The unit tier pins the survey's arithmetic; this pins the MACHINE: that an observatory can be
 * aimed at a region, that it sweeps through it on the world's own clock when research is on and
 * resolves it outright when research is off, that stopping and re-aiming cost nothing but the cell in
 * flight, and that what it resolved is written onto the crystal sitting in the machine.</p>
 *
 * <p>Every number here is the SERVER's answer; the probes state their own side.</p>
 *
 * <p>Position-isolated at x=4300-4460 (clear of the observatory-multiblock fixtures at x=4000-4060).</p>
 */
public class TelescopeRegionScanE2ETest extends AbstractSharedServerTest {

    private static final int CY = 64;
    private static final int CZ = 4300;

    private String exec(String command) throws Exception {
        return join(client().execute(command));
    }

    private static String join(java.util.List<String> response) {
        return String.join("\n", response);
    }

    /**
     * A small, fast survey. {@code research} chooses which half of the design is under test: off is
     * the default game, where what the instrument reaches is resolved outright; on is the research
     * mode, where the sweep is paced and the time curve is the mechanic.
     */
    private void surveySetup(boolean research, int cellsPerStep, int ticksPerSector) throws Exception {
        exec("artest config set planetsMustBeDiscovered " + research);
        exec("artest config set telescopeScanBaseTicks 0");
        exec("artest config set telescopeScanTicksPerSector " + ticksPerSector);
        exec("artest config set telescopeScanRangeSectors 24");
        exec("artest config set telescopeScanHalfWidthSectors 1");
        exec("artest config set telescopeScanMaxSectors 1000");
        exec("artest config set telescopeScanCellsPerStep " + cellsPerStep);
        exec("artest config set telescopePassiveRadiusSectors 1");
    }

    /** The value of a numeric JSON field in a probe reply. */
    private static long field(String json, String name) {
        String key = "\"" + name + "\":";
        int at = json.indexOf(key);
        assertTrue("probe reply has no field " + name + ": " + json, at >= 0);
        int from = at + key.length();
        int to = from;
        while (to < json.length() && "-0123456789".indexOf(json.charAt(to)) >= 0) {
            to++;
        }
        return Long.parseLong(json.substring(from, to));
    }

    /** The value of a string JSON field in a probe reply. */
    private static String text(String json, String name) {
        String key = "\"" + name + "\":\"";
        int at = json.indexOf(key);
        assertTrue("probe reply has no field " + name + ": " + json, at >= 0);
        int from = at + key.length();
        return json.substring(from, json.indexOf('"', from));
    }

    private String where(int x) {
        return "0 " + x + " " + CY + " " + CZ;
    }

    /** An observatory with a blank crystal in it, and the cell it stands in. */
    private String[] observatoryWithCrystal(int x) throws Exception {
        String placed = exec("artest telescope place " + where(x));
        assertTrue("could not place an observatory: " + placed, placed.contains("\"ok\":true"));
        String crystal = exec("artest telescope crystal " + where(x));
        assertEquals("the crystal must start blank, or every count afterwards means nothing",
                0L, field(crystal, "addresses"));
        String info = exec("artest telescope info " + where(x));
        assertTrue("the observatory's own world must have a galactic address: " + info,
                info.contains("\"origin\":\""));
        return text(info, "origin").split("_");
    }

    /** Put a system with a planet in it at a cell, so what the instrument finds is determinate. */
    private void systemAt(long sx, String sy, String sz) throws Exception {
        String system = exec("artest telescope system " + sx + " " + sy + " " + sz);
        assertTrue("could not place a system to be found: " + system, system.contains("\"ok\":true"));
    }

    /** Poll the machine until its survey is finished. Bounded: 40 × 250 ms = 10 s. */
    private String awaitSurveyComplete(int x) throws Exception {
        String info = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            info = exec("artest telescope info " + where(x));
            if (!info.contains("\"scanning\":true")) {
                return info;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("the survey never finished: " + info);
    }

    @Test
    public void withoutResearchWhatTheInstrumentReachesIsResolvedOutright() throws Exception {
        final int x = 4300;
        surveySetup(false, 2, 40);
        String[] home = observatoryWithCrystal(x);
        systemAt(Long.parseLong(home[0]) + 4, home[1], home[2]);

        String started = exec("artest telescope scan " + where(x) + " 1 0 0 4");
        assertTrue("the survey did not start: " + started, started.contains("\"ok\":true"));

        String done = awaitSurveyComplete(x);
        assertTrue("the crystal learned nothing from a region holding a system: " + done,
                field(done, "addresses") >= 1);
        assertTrue("and the machine must report what it discovered: " + done,
                field(done, "lastDiscoveries") >= 1);
    }

    @Test
    public void withResearchTheSurveySweepsCellByCell() throws Exception {
        final int x = 4340;
        // One cell a step — the claim under test — and a step short enough that 27 of them fit in
        // the poll budget: at 20 ticks per sector of distance a single cell took 4 s, so the whole
        // region wanted 108 s against a 10 s budget and the sweep was blamed for the arithmetic.
        surveySetup(true, 1, 1);
        String[] home = observatoryWithCrystal(x);

        String started = exec("artest telescope scan " + where(x) + " 1 0 0 4");
        assertTrue("the survey did not start: " + started, started.contains("\"ok\":true"));
        assertTrue("a region worth sweeping must hold more than one cell: " + started,
                field(started, "cells") > 1);
        assertEquals("a fresh survey has resolved nothing yet", 0L, field(started, "cellsDone"));

        long total = field(started, "cells");
        long seen = 0;
        for (int attempt = 0; attempt < 60 && seen < total; attempt++) {
            Thread.sleep(250L);
            String info = exec("artest telescope info " + where(x));
            if (!info.contains("\"scanning\":true")) {
                seen = total;
                break;
            }
            long done = field(info, "cellsDone");
            assertTrue("a sweep must never go backwards: " + done + " after " + seen, done >= seen);
            seen = done;
        }
        assertTrue("the sweep never advanced through its region (" + seen + "/" + total + ")",
                seen >= total);
    }

    @Test
    public void stoppingASurveyIsFreeAndKeepsWhatWasAlreadyLearned() throws Exception {
        final int x = 4380;
        surveySetup(true, 1, 60);
        String[] home = observatoryWithCrystal(x);
        systemAt(Long.parseLong(home[0]) + 3, home[1], home[2]);

        exec("artest telescope scan " + where(x) + " 1 0 0 3");
        String before = exec("artest telescope info " + where(x));
        assertTrue("the survey must be running before it can be stopped: " + before,
                before.contains("\"scanning\":true"));
        long learned = field(before, "addresses");

        String stopped = exec("artest telescope abort " + where(x));
        assertTrue("stopping must be free and immediate: " + stopped, stopped.contains("\"ok\":true"));
        assertTrue("the instrument must be idle after a stop: " + stopped,
                stopped.contains("\"scanning\":false"));
        assertTrue("stopping must not take back what was already resolved: " + stopped,
                field(stopped, "addresses") >= learned);
    }

    @Test
    public void aimingAgainMovesTheRegionWithoutLosingWhatWasLearned() throws Exception {
        final int x = 4420;
        surveySetup(true, 1, 60);
        String[] home = observatoryWithCrystal(x);

        String first = exec("artest telescope scan " + where(x) + " 1 0 0 3");
        assertTrue("the first survey did not start: " + first, first.contains("\"ok\":true"));
        long learned = field(first, "addresses");

        String second = exec("artest telescope scan " + where(x) + " 0 0 1 5");
        assertTrue("re-aiming mid-survey must be allowed: " + second, second.contains("\"ok\":true"));
        assertNotEquals("re-aiming must actually move the region",
                text(first, "min"), text(second, "min"));
        assertTrue("and must keep every address already written: " + second,
                field(second, "addresses") >= learned);
    }

    @Test
    public void theLocalRadarSurveysTheObservatorysOwnNeighbourhood() throws Exception {
        final int x = 4460;
        surveySetup(false, 4, 40);
        String[] home = observatoryWithCrystal(x);

        String passive = exec("artest telescope passive " + where(x));
        assertTrue("the local radar did not start: " + passive, passive.contains("\"ok\":true"));
        assertTrue("the local radar is a mode of the machine, not a survey of somewhere else: " + passive,
                passive.contains("\"passive\":true"));

        // Its region must contain the cell the observatory itself stands in.
        long homeX = Long.parseLong(home[0]);
        String minKey = text(passive, "min");
        String maxKey = text(passive, "max");
        long lo = Long.parseLong(minKey.split("_")[0]);
        long hi = Long.parseLong(maxKey.split("_")[0]);
        assertTrue("the radar must look around home (" + homeX + "), not at " + minKey + ".." + maxKey,
                lo <= homeX && homeX <= hi);
    }

    @Test
    public void aSurveyInFlightSurvivesItsChunkBeingUnloaded() throws Exception {
        final int x = 4540;
        // Research on, one cell a step, a step long enough that the sweep is certainly mid-region
        // when the chunk goes away.
        surveySetup(true, 1, 10);
        observatoryWithCrystal(x);

        String started = exec("artest telescope scan " + where(x) + " 1 0 0 3");
        assertTrue("the survey did not start: " + started, started.contains("\"ok\":true"));
        Thread.sleep(1500L);
        String before = exec("artest telescope info " + where(x));
        assertTrue("the survey must still be running to be interrupted: " + before,
                before.contains("\"scanning\":true"));
        long doneBefore = field(before, "cellsDone");
        String region = text(before, "min");

        String cycled = exec("artest chunk cycle 0 " + (x >> 4) + " " + (CZ >> 4));
        assertTrue("the chunk was never actually dropped, so nothing was proven: " + cycled,
                cycled.contains("\"dropped\":true") && cycled.contains("\"reloaded\":true"));

        String after = exec("artest telescope info " + where(x));
        assertTrue("the survey did not come back with the chunk: " + after,
                after.contains("\"scanning\":true"));
        assertEquals("it must come back looking at the same region", region, text(after, "min"));
        assertTrue("and must not have forgotten the cells it had already surveyed: was "
                        + doneBefore + ", now " + field(after, "cellsDone"),
                field(after, "cellsDone") >= doneBefore);
    }

    @Test
    public void anUnfedInstrumentStallsInsteadOfSurveyingForFree() throws Exception {
        final int x = 4580;
        surveySetup(true, 1, 1);
        // A price no bare observatory can pay: it has no data buses, so it has no distance data.
        exec("artest config set telescopeSurveyDataPerStep 50");
        try {
            observatoryWithCrystal(x);
            String started = exec("artest telescope scan " + where(x) + " 1 0 0 2");
            assertTrue("the survey did not start: " + started, started.contains("\"ok\":true"));

            Thread.sleep(2000L);
            String after = exec("artest telescope info " + where(x));
            assertTrue("an instrument with no data must still be waiting, not finished: " + after,
                    after.contains("\"scanning\":true"));
            assertEquals("and must not have resolved a single cell on credit",
                    0L, field(after, "cellsDone"));
        } finally {
            exec("artest config set telescopeSurveyDataPerStep 0");
        }
    }

    @Test
    public void whatTheTelescopeWroteIsWhatAShipCanBeAimedBy() throws Exception {
        final int x = 4620;
        surveySetup(false, 4, 1);
        String[] home = observatoryWithCrystal(x);
        systemAt(Long.parseLong(home[0]) + 3, home[1], home[2]);

        exec("artest telescope scan " + where(x) + " 1 0 0 3");
        String surveyed = awaitSurveyComplete(x);
        assertTrue("the survey must have written something to hand over: " + surveyed,
                field(surveyed, "addresses") >= 1);

        // Carry the crystal to a navigation computer, the way a player would.
        int navX = x + 4;
        String placed = exec("artest nav place 0 " + navX + " " + CY + " " + CZ);
        assertTrue("could not place a navigation computer: " + placed, placed.contains("\"ok\":true"));
        String handed = exec("artest telescope handover " + where(x) + " " + navX + " " + CY + " " + CZ);
        assertTrue("the crystal did not reach the console: " + handed, handed.contains("\"ok\":true"));
        assertTrue("and it must arrive holding what the telescope wrote: " + handed,
                field(handed, "addresses") >= 1);

        String status = exec("artest nav status 0 " + navX + " " + CY + " " + CZ);
        assertTrue("the console must read the telescope's own crystal: " + status,
                field(status, "ship") >= 1);
    }

    @Test
    public void aFartherRegionIsALongerSurveyOnTheRealClock() throws Exception {
        final int x = 4500;
        surveySetup(true, 2, 40);
        observatoryWithCrystal(x);

        String near = exec("artest telescope scan " + where(x) + " 1 0 0 2");
        long nearTicks = field(near, "estimatedTicks");
        exec("artest telescope abort " + where(x));

        String far = exec("artest telescope scan " + where(x) + " 1 0 0 20");
        long farTicks = field(far, "estimatedTicks");
        exec("artest telescope abort " + where(x));

        assertTrue("a farther region must be a longer survey: near=" + nearTicks + " far=" + farTicks,
                farTicks > nearTicks);
    }
}
