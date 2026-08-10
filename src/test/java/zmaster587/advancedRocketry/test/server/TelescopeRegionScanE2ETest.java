package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The observatory's region scan, driven on a real server through the machine itself.
 *
 * <p>The unit tier pins the scan's arithmetic; this pins the MACHINE: that an observatory can be
 * aimed at a region of the galaxy, that the observation runs on the world's own clock, and that what
 * it resolved is written onto the crystal sitting in the machine — which is the player-facing point
 * of the whole instrument.</p>
 *
 * <p>Every number here is the SERVER's answer; the probes state their own side.</p>
 *
 * <p>Position-isolated at x=4300-4380 (clear of the observatory-multiblock fixtures at x=4000-4060).</p>
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
     * A look that costs one tick per sector and nothing to point — it takes a production-length
     * observation out of the test's wall clock without changing anything about what a scan does.
     */
    private void shortScans() throws Exception {
        exec("artest config set telescopeScanBaseTicks 0");
        exec("artest config set telescopeScanTicksPerSector 1");
        exec("artest config set telescopeScanRangeSectors 24");
        exec("artest config set telescopeScanHalfWidthSectors 1");
        exec("artest config set telescopeScanMaxSectors 64");
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

    private String placeObservatory(int x) throws Exception {
        String placed = exec("artest telescope place 0 " + x + " " + CY + " " + CZ);
        assertTrue("could not place an observatory: " + placed, placed.contains("\"ok\":true"));
        return exec("artest telescope info 0 " + x + " " + CY + " " + CZ);
    }

    /** Poll the machine until its observation is finished. Bounded: 40 x 250 ms = 10 s. */
    private String awaitScanComplete(int x) throws Exception {
        String info = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            info = exec("artest telescope info 0 " + x + " " + CY + " " + CZ);
            if (!info.contains("\"scanning\":true")) {
                return info;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("the observation never finished: " + info);
    }

    @Test
    public void anObservatoryWritesWhatItResolvedOntoTheCrystalInIt() throws Exception {
        final int x = 4300;
        shortScans();
        String info = placeObservatory(x);

        // Where the machine stands, in galactic terms. Without an address it has nothing to aim FROM,
        // and that refusal is the contract rather than a silent default sky — so read it, don't assume.
        assertTrue("the observatory's own world must have a galactic address: " + info,
                info.contains("\"origin\":\""));
        String[] home = text(info, "origin").split("_");
        long targetX = Long.parseLong(home[0]) + 4;

        // Put a system where the scan will look, so what the instrument finds is determinate.
        String system = exec("artest telescope system " + targetX + " " + home[1] + " " + home[2]);
        assertTrue("could not place a system to be found: " + system, system.contains("\"ok\":true"));

        String crystal = exec("artest telescope crystal 0 " + x + " " + CY + " " + CZ);
        assertEquals("the crystal must start blank, or the count afterwards means nothing",
                0L, field(crystal, "addresses"));

        String started = exec("artest telescope scan 0 " + x + " " + CY + " " + CZ + " 1 0 0 4");
        assertTrue("the scan did not start: " + started, started.contains("\"ok\":true"));
        assertTrue("a started scan must be looking somewhere", started.contains("\"scanning\":true"));
        assertTrue("an observation must take time: " + started, field(started, "duration") > 0);

        String done = awaitScanComplete(x);
        assertTrue("the crystal learned nothing from a scan of a region holding a system: " + done,
                field(done, "addresses") >= 1);
        assertTrue("and the machine must report having discovered it: " + done,
                field(done, "lastDiscoveries") >= 1);
    }

    @Test
    public void anObservatoryLooksAtOneRegionAtATime() throws Exception {
        final int x = 4340;
        shortScans();
        placeObservatory(x);
        exec("artest telescope crystal 0 " + x + " " + CY + " " + CZ);

        // A long look, so the second aim certainly lands while the first is still running.
        exec("artest config set telescopeScanTicksPerSector 200");
        String first = exec("artest telescope scan 0 " + x + " " + CY + " " + CZ + " 1 0 0 20");
        assertTrue("the first scan did not start: " + first, first.contains("\"ok\":true"));

        String second = exec("artest telescope scan 0 " + x + " " + CY + " " + CZ + " 0 0 1 3");
        assertTrue("a second aim must be refused while the instrument is busy: " + second,
                second.contains("\"ok\":false") && second.contains("\"reason\":\"busy\""));

        String after = exec("artest telescope info 0 " + x + " " + CY + " " + CZ);
        assertEquals("the refused aim must not have moved the instrument off its region",
                text(first, "min"), text(after, "min"));
    }

    @Test
    public void aFartherRegionIsALongerObservationOnTheRealClock() throws Exception {
        final int x = 4380;
        shortScans();
        exec("artest config set telescopeScanTicksPerSector 40");
        placeObservatory(x);
        exec("artest telescope crystal 0 " + x + " " + CY + " " + CZ);

        String near = exec("artest telescope scan 0 " + x + " " + CY + " " + CZ + " 1 0 0 2");
        long nearDuration = field(near, "duration");
        awaitScanComplete(x);

        String far = exec("artest telescope scan 0 " + x + " " + CY + " " + CZ + " 1 0 0 20");
        long farDuration = field(far, "duration");

        assertTrue("a farther region must be a longer observation: near=" + nearDuration
                + " far=" + farDuration, farDuration > nearDuration);
    }
}
