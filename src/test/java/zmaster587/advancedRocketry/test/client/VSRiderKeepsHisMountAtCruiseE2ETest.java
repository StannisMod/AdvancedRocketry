package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A player riding a mount aboard a CRUISING ship never loses that mount.
 *
 * <p><b>The report.</b> Above roughly half throttle the client threw the maintainer out of his pilot
 * seat every few seconds and picked him up again, for the whole burn, while the server kept him
 * mounted and the ship flew on. The seat, not the ship, is what became unusable.</p>
 *
 * <p><b>The mechanism.</b> A mount glued to a moving ship outruns the tracking anchor its own
 * {@code EntityTrackerEntry} publishes; once the rider is more than the mount's tracking range from
 * that stale anchor the server stops sending it, the client destroys it, and a passenger whose
 * vehicle was removed is dismounted on the next tick. This test measures BOTH ends in the same
 * samples: the server-side cause (is the rider still in the mount's tracking set, and how far has
 * the anchor fallen behind him) and the client-side consequence ({@code reportRidingEntity} plus the
 * client's own view of the mount entity).</p>
 *
 * <p><b>Why the subject is a PASSENGER mount and not the pilot's seat.</b> Same mechanism, but only
 * this subject can be put under load on demand. A mount bound to a LINKED pilot seat republishes six
 * flight-telemetry floats, and every write re-pins its own anchor - vanilla's third refresh
 * disjunct - so its anchor never goes stale while anything about the flight is changing. Measured on
 * that subject: at 4.075 blocks/tick the lag never exceeded 3.34 blocks against a 16-block range, and
 * no speed the physics mod permits (it freezes a ship at ~223 blocks/s) can close that gap. The
 * pilot's own mount reaches the stale-anchor state the moment he stops working the controls and
 * coasts - which is exactly when the report's log shows the bursts - but a test cannot hold a real
 * ship that quiet on demand. A passenger's chair publishes nothing and is carried by the same deck.</p>
 *
 * <p><b>The stimulus must be HORIZONTAL.</b> Vanilla's visibility test compares X and Z and ignores Y
 * entirely, so the obvious idiom in this suite - hold the throttle and climb - cannot exhibit this at
 * any speed. That is also why taking off from a planet at full throttle is symptom-free: it is
 * {@code fwd=0, vert=1}.</p>
 *
 * <p><b>The instrument carries its own proof.</b> The bot is dismounted on purpose before the cruise
 * and the poll is required to SEE it; and the achieved speed and anchor lag are asserted, so a green
 * cannot mean "the arrangement never loaded the mechanism". Confirmed RED on the clean build at
 * 24/40 samples unseated with a 36.8-block anchor lag, GREEN with the fix at 0/40 and a 41.0-block
 * lag - i.e. the guard held under a HARDER load than the one that broke it.</p>
 *
 * <p>Gated on real VS - run with {@code -PwithVS}.</p>
 */
public class VSRiderKeepsHisMountAtCruiseE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    /**
     * The mount is registered with a tracking range of 16 blocks and an anchor republished every 20
     * ticks, so its anchor falls behind by {@code speed x 20} blocks between refreshes: past
     * 16/20 = 0.8 blocks/tick on either horizontal axis it leaves its own box. The cruise must clear
     * that, or the leg proves nothing.
     */
    private static final double EVICTION_THRESHOLD_BLOCKS_PER_TICK = 0.8;

    /** Commanded cruise, blocks/SECOND (the physics velocity unit): 2 blocks/tick, the reported speed. */
    private static final double COMMANDED_SPEED_BLOCKS_PER_SECOND = 40.0;

    /** Ticks between the two samples a cruise-speed measurement is taken from. */
    private static final int SETTLE_SAMPLE_TICKS = 10;

    /** How close two successive speed samples must be before the cruise counts as STEADY. Loose
     *  enough to survive physics jitter, tight enough that the telemetry the mount publishes has
     *  stopped moving - which is the condition the anchor staleness needs. */
    private static final double STEADY_EPSILON = 0.002;

    /** Four full 20-tick tracking cycles: on the broken build the mount is evicted for roughly half
     *  of every one of them. */
    private static final int OBSERVE_TICKS = 80;
    private static final int POLL_EVERY_TICKS = 2;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    private static double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected " + p.pattern() + " in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static int readInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("expected \"" + key + "\" in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static boolean readBool(String json, String key) {
        return Pattern.compile("\"" + key + "\":true").matcher(json).find();
    }

    private boolean riding() throws Exception {
        return bot().reportRidingEntity().get("riding").getAsBoolean();
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private static int readIntOr(String json, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    @Test
    public void aSeatedRiderNeverLosesTheMountHeIsRidingWhileTheShipCruises() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies (run with -PwithVS)", serverHasVs());

        // Headless: nobody is near the ship between probe calls, so pin it loaded. This is
        // arrangement only - what is under test is what the client is TOLD about an entity it
        // already has, not whether the ship loads.
        exec("artest vs permaload true");

        String setup = exec("artest space transit-setup-empty");
        assertTrue("ARRANGEMENT: empty cell setup must succeed: " + setup, readBool(setup, "ok"));
        int dim = readInt(setup, "originDim");

        int bx = 40, by = 64, bz = 40;
        assertTrue("ARRANGEMENT: chunk warmup failed",
                readBool(exec("artest chunk warmup " + dim + " " + ((bx - 2) >> 4) + " " + ((bz - 2) >> 4)
                        + " " + ((bx + 7) >> 4) + " " + ((bz + 7) >> 4)), "ok"));

        String fixture = exec("artest fixture rocket " + dim + " " + bx + " " + by + " " + bz
                + " with-pilot-seat");
        assertTrue("ARRANGEMENT: with-pilot-seat fixture failed: " + fixture, readBool(fixture, "ok"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("ARRANGEMENT: fixture missing builderPos: " + fixture, bp.find());
        String assembled = exec("artest rocket assemble " + dim
                + " " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
        assertTrue("ARRANGEMENT: a with-pilot-seat build must route to a ship: " + assembled,
                assembled.contains("\"rocketCount\":0"));
        assertTrue("ARRANGEMENT: the ship never assembled/loaded in the cell (dim " + dim + ")",
                waitForLoadedShip(dim) >= 1);

        String seat = exec("artest vs find-seat " + dim
                + " " + (bx + 3) + " " + (by + 3) + " " + (bz + 3));
        assertTrue("ARRANGEMENT: the pilot seat must be found (else the test is vacuous): " + seat,
                readBool(seat, "seatFound"));
        int seatX = readInt(seat, "seatX"), seatY = readInt(seat, "seatY"), seatZ = readInt(seat, "seatZ");
        int sx = (int) Math.round(readDouble(seat, Pattern.compile("\"shipWorldX\":(-?[0-9.E\\-]+)")));
        int sy = (int) Math.round(readDouble(seat, Pattern.compile("\"shipWorldY\":(-?[0-9.E\\-]+)")));
        int sz = (int) Math.round(readDouble(seat, Pattern.compile("\"shipWorldZ\":(-?[0-9.E\\-]+)")));

        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        assertTrue("ARRANGEMENT: the bot must enter the cell",
                readBool(exec("artest space enter " + botName + " " + dim
                        + " " + sx + " " + sy + " " + sz), "ok"));
        bot().waitTicks(20);
        assertEquals("ARRANGEMENT: the client must have followed into the cell",
                dim, bot().reportWeather().get("dim").getAsInt());

        // The subject is a mount on a MOVING SHIP whose data-watcher is quiet - a passenger seat,
        // not the pilot's. This is not a detail: a mount bound to a LINKED pilot seat republishes six
        // flight-telemetry floats, and each write re-pins its own tracking anchor, which is vanilla's
        // third refresh disjunct. Measured on that subject: at 4.075 blocks/tick the anchor never fell
        // more than 3.34 blocks behind a 16-block range - the mechanism cannot be put under load at
        // any speed VS will allow. A mount one block off the pilot seat resolves no flight computer,
        // publishes nothing, and is carried by the same ship - which is exactly a passenger's chair.
        int mountX = seatX + 1, mountY = seatY, mountZ = seatZ;
        String mount = "";
        boolean mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + mountX + " " + mountY + " " + mountZ);
            assertTrue("ARRANGEMENT: seat-mount-at must spawn the seat dummy: " + mountAt,
                    readBool(mountAt, "ok"));
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("ARRANGEMENT: the bot must mount the pilot-seat dummy: " + mount, mounted);
        bot().waitTicks(10);
        assertTrue("ARRANGEMENT: the client must report the bot seated before anything else",
                riding());

        // The instrument's own proof: it must be able to say FALSE. Without this leg, the cruise
        // assertion below is green on a reporter that is simply stuck on true.
        exec("artest player dismount");
        bot().waitTicks(10);
        assertFalse("CONTROL: the client must be able to report NOT riding - otherwise the cruise"
                        + " leg cannot fail", riding());

        mounted = false;
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + mountX + " " + mountY + " " + mountZ);
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            mounted = mount.contains("\"mounted\":true");
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        assertTrue("ARRANGEMENT: the bot must be re-seated after the control: " + mount, mounted);
        bot().waitTicks(10);
        assertTrue("ARRANGEMENT: seated again before the cruise", riding());

        // ---- STIMULUS: a COASTING horizontal cruise. Three properties, each load-bearing.
        //
        // HORIZONTAL, because vanilla's visibility test reads X and Z and ignores Y entirely: a
        // climb cannot exhibit this at any speed. (That is also why the reported flight over a
        // planet - a vertical take-off - was symptom-free while the same speed in a cell was not.)
        //
        // COASTING, because the mount republishes six flight-telemetry floats and every write
        // dirties its DataWatcher, which is the third disjunct of vanilla's anchor-refresh gate. A
        // pilot who is actively working the controls, or a ship whose speed is still changing,
        // re-pins the tracking anchor every tick and can never be evicted at any speed. The held
        // throttle is therefore set ONCE, to a constant, and the leg then waits for the speed to
        // stop changing. In the report's own log the unseating bursts fall exactly in the seconds
        // where the pilot-input trace is silent - this is that state.
        //
        // NOT at the cell's pose band: Y is irrelevant to the mechanism, and a ship teleported to
        // Y ~ 2,000,000 froze the client outright (measured; a separate defect, ledgered).
        //
        // SUBSTITUTION, named here because a shortcut nobody wrote down is a hole nobody weighs at
        // the go/no-go: a human reaches cruise on his own
        // throttle. This fixture cannot - measured, `with-pilot-seat` tops out at ~0.15 blocks/tick
        // under its own power and no higher-thrust variant exists - so the SPEED is commanded while
        // the throttle stays held. What that replaces is only how the ship gets moving; the mount,
        // the rider, the seat binding and the tracking are all the production objects.
        // The throttle is deliberately NOT held here: measured, a held throttle makes the flight
        // computer fight the commanded velocity and the ship falls to ~0.11 blocks/tick, well under
        // the speed this leg needs. A coasting ship at a commanded constant is both faster and
        // quieter, which is the state the report was flown in.
        String commanded = exec("artest vs force-vel " + dim + " " + sx + " " + sy + " " + sz
                + " " + COMMANDED_SPEED_BLOCKS_PER_SECOND + " 0 0");
        assertTrue("ARRANGEMENT: force-vel must find the loaded ship: " + commanded,
                readBool(commanded, "commanded"));

        double steady = Double.NaN, prev = Double.NaN;
        for (int attempt = 0; attempt < 60 && Double.isNaN(steady); attempt++) {
            String s0 = exec("artest vs ship-info " + dim + " " + sx + " " + sy + " " + sz);
            double ax = readDouble(s0, POS_X), az = readDouble(s0, POS_Z);
            bot().waitTicks(SETTLE_SAMPLE_TICKS);
            String s1 = exec("artest vs ship-info " + dim + " " + sx + " " + sy + " " + sz);
            double speed = Math.hypot(readDouble(s1, POS_X) - ax, readDouble(s1, POS_Z) - az)
                    / SETTLE_SAMPLE_TICKS;
            if (speed > EVICTION_THRESHOLD_BLOCKS_PER_TICK
                    && !Double.isNaN(prev) && Math.abs(speed - prev) < STEADY_EPSILON) {
                steady = speed;
            }
            prev = speed;
        }
        assertTrue("ARRANGEMENT: the ship must reach a STEADY cruise above "
                + EVICTION_THRESHOLD_BLOCKS_PER_TICK + " blocks/tick - while it is still accelerating"
                + " the mount re-pins its own tracking anchor every tick and nothing can be evicted,"
                + " so an unsettled ship makes this leg unfalsifiable. Last speed sample: " + prev,
                !Double.isNaN(steady));

        String before = exec("artest vs ship-info " + dim + " " + sx + " " + sy + " " + sz);
        double x0 = readDouble(before, POS_X), z0 = readDouble(before, POS_Z);
        double px0 = bot().reportState().get("playerX").getAsDouble();
        double pz0 = bot().reportState().get("playerZ").getAsDouble();

        // A transient wants cumulative counters plus a bounded history, never a first/last snapshot:
        // the mount is expected to come and go several times per second. The client's own view of
        // the mount ENTITY is sampled next to the riding flag, because the entity is what the server
        // evicts - if that count never drops, the eviction never happened and the riding flag has
        // nothing to report.
        int notRiding = 0, samples = 0, mountMissing = 0, riderUntracked = 0;
        double maxAnchorLag = 0.0;
        StringBuilder trace = new StringBuilder();
        for (int t = 0; t < OBSERVE_TICKS; t += POLL_EVERY_TICKS) {
            bot().waitTicks(POLL_EVERY_TICKS);
            samples++;
            boolean seated = riding();
            int mounts = bot().reportEntities("EntityDummy", 64.0).get("count").getAsInt();
            // Server-side CAUSE, sampled next to the client-side consequence: if the rider stops
            // being tracked, or the mount's published anchor drifts past the tracking range, the
            // client is about to lose the mount. Without this the run can only say THAT the seat
            // held, never why - and a green would not distinguish "the guard worked" from "the
            // arrangement never put the mechanism under load".
            String track = exec("artest vs mount-tracking " + dim);
            if (readBool(track, "seatedRider") && !readBool(track, "riderTracks")) {
                riderUntracked++;
            }
            Matcher lx = Pattern.compile("\"anchorLagX\":([0-9.E\\-]+)").matcher(track);
            Matcher lz = Pattern.compile("\"anchorLagZ\":([0-9.E\\-]+)").matcher(track);
            if (lx.find()) {
                maxAnchorLag = Math.max(maxAnchorLag, Double.parseDouble(lx.group(1)));
            }
            if (lz.find()) {
                maxAnchorLag = Math.max(maxAnchorLag, Double.parseDouble(lz.group(1)));
            }
            if (!seated) {
                notRiding++;
            }
            if (mounts == 0) {
                mountMissing++;
            }
            trace.append(seated ? (mounts == 0 ? 'M' : '.') : (mounts == 0 ? 'X' : 'x'));
        }
        System.out.println("[#163] seated/mount trace ('.' seated+mount present, 'x' unseated,"
                + " 'M' mount gone, 'X' both): " + trace);

        String after = exec("artest vs ship-info " + dim + " " + sx + " " + sy + " " + sz);
        double shipDX = readDouble(after, POS_X) - x0, shipDZ = readDouble(after, POS_Z) - z0;
        double shipTravel = Math.hypot(shipDX, shipDZ);
        double perTickX = Math.abs(shipDX) / OBSERVE_TICKS;
        double perTickZ = Math.abs(shipDZ) / OBSERVE_TICKS;
        double fastestAxis = Math.max(perTickX, perTickZ);
        double playerTravel = Math.hypot(
                bot().reportState().get("playerX").getAsDouble() - px0,
                bot().reportState().get("playerZ").getAsDouble() - pz0);

        String measured = " [ship " + shipTravel + " blocks over " + OBSERVE_TICKS + " ticks ="
                + fastestAxis + "/tick on its fastest axis (x=" + perTickX + ", z=" + perTickZ + ");"
                + " client player travelled " + playerTravel + " blocks; " + samples + " samples,"
                + " " + notRiding + " unseated, " + mountMissing + " with the mount gone,"
                + " " + riderUntracked + " with the rider NOT in the mount's tracking set,"
                + " worst anchor lag " + maxAnchorLag + " blocks vs a 16-block range]";
        System.out.println("[#163] measured:" + measured);

        // Both checked BEFORE the verdict. A ship that did not cruise, or a mount that did not
        // carry the player with it, makes the poll below unfalsifiable - and that is an
        // arrangement failure, not a passing build.
        assertTrue("ARRANGEMENT: the ship must cruise faster than the mount's own tracking headroom"
                + " (" + EVICTION_THRESHOLD_BLOCKS_PER_TICK + " blocks/tick) or this leg cannot"
                + " exhibit the fault." + measured,
                fastestAxis > EVICTION_THRESHOLD_BLOCKS_PER_TICK);
        assertTrue("ARRANGEMENT: the seated player must TRAVEL WITH the ship - a mount that stays"
                + " put keeps its tracking anchor fresh, so nothing here could ever be evicted and"
                + " the leg would pass on any build." + measured,
                playerTravel > shipTravel * 0.5);

        assertEquals("the client threw the rider off his mount " + notRiding + " of " + samples
                + " samples while the ship cruised:"
                + " a rider must never lose the vehicle he is riding." + measured,
                0, notRiding);
    }
}
