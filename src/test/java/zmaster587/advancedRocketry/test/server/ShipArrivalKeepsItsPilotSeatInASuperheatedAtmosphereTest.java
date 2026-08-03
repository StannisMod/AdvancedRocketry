package zmaster587.advancedRocketry.test.server;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A ship that ARRIVES on a planet hot enough to set things alight must arrive WHOLE — with the
 * pilot seat its crew is about to sit back down on.
 *
 * <p>The contract is the player's: he lands somewhere hostile and his craft is still his craft.
 * What made it fail was that a structure arrives one block at a time, while the destination's
 * atmosphere judges each block the instant it is written. A pilot seat is cloth; the hottest
 * atmosphere band converts cloth to fire on contact; and mid-paste the seat that will end up
 * deep inside a sealed hull is standing alone in the open. So the seat burned before its tile
 * was ever restored, the arriving ship had no seat at all, and the crew was left standing in
 * space while their ship sat parked on the planet. It reproduced on roughly one landing in
 * thirteen — the share of generated planets that are that hot — which is why it read as a
 * flake for days.</p>
 *
 * <p>The test authors the hot planet instead of waiting for the generator to roll one, so it is
 * deterministic. Its POSITIVE CONTROL is the load-bearing part: a bare pilot seat placed into the
 * same atmosphere, at the same moment, MUST burn. Without that, "the ship kept its seat" would
 * also pass on a build where nothing ever burns anything, which is exactly the shape of test that
 * cannot fail for the reason it was written.</p>
 *
 * <p>Gated on the server's real VS presence (run with {@code -PwithVS}); skips cleanly otherwise.</p>
 */
public class ShipArrivalKeepsItsPilotSeatInASuperheatedAtmosphereTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    /** Where the ship is built, and the clear sky it crosses into. Well clear of other fixtures. */
    private static final int SRC_X = 5300, SRC_Y = 80, SRC_Z = 5300;
    private static final int DST_X = 5364, DST_Y = 150, DST_Z = 5300;

    /** Two lone blocks away from the ship: the instrument check, then the control proper. */
    private static final int CTRL_X = SRC_X + 18, CTRL_Y = SRC_Y + 8, CTRL_Z = SRC_Z + 18;
    private static final int CTRL2_X = SRC_X + 18, CTRL2_Y = SRC_Y + 8, CTRL2_Z = SRC_Z + 16;

    /** Above the 900 K band edge that {@code DimensionProperties.getAtmosphere} calls superheated. */
    private static final int SUPERHEATED_KELVIN = 1290;

    private static final String PILOT_SEAT = "advancedrocketry:pilotseat";

    private int originalTemperature = Integer.MIN_VALUE;

    @Test
    public void aShipCrossingIntoASuperheatedAtmosphereKeepsItsPilotSeat() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)", serverHasVs());

        // A headless server has nobody near a ship to hold it loaded; pin ships so the observations
        // below are of the ship and not of VS's unload policy. Reset in @After.
        exec("artest vs permaload true");

        // The craft is BUILT while the world is still temperate — a player builds at home and lands
        // elsewhere, and building in the fire is a different story than arriving in it.
        clearArea(SRC_X, SRC_Z);
        clearArea(DST_X, DST_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z);
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the source VS ship never loaded", waitForLoadedShip() >= 1);

        String pre = exec("artest vs seat-input 0 0 0 0 0 0 0");
        assertTrue("before the crossing the ship must have a pilot seat to lose: " + pre,
                pre.contains("\"seatFound\":true"));

        // INSTRUMENT CHECK, while the world is still temperate: placing a lone pilot seat this way
        // leaves a pilot seat. Without this leg, "the seat is gone" after the heat could just as
        // well mean the placement never worked.
        String placedCold = placeLoneSeat(CTRL_X, CTRL_Y, CTRL_Z);
        assertEquals("instrument check: a pilot seat placed in a temperate atmosphere must simply be "
                        + "there. It is not, so neither control leg below can be read: " + placedCold,
                PILOT_SEAT, blockOf(placedCold));
        // ...and take it straight back out. The seat probe below answers "where is the pilot seat",
        // and a loose control seat lying in the world is one no ship owns: leaving it there makes the
        // subject assertion read a seat that never crossed anything. (Measured: it did exactly that.)
        clearPos(CTRL_X, CTRL_Y, CTRL_Z);

        // ARRANGEMENT: make the destination atmosphere the one that converts blocks on contact.
        // Asserted, not assumed — a test whose arrangement silently failed to arrange is a test that
        // measures nothing.
        originalTemperature = extractInt(exec("artest planet info 0"), "averageTemperature");
        String heated = exec("artest planet set-temp 0 " + SUPERHEATED_KELVIN);
        assertTrue("could not author a superheated atmosphere: " + heated,
                heated.toLowerCase().contains("superheated"));

        // POSITIVE CONTROL: the same placement into that atmosphere does NOT leave a pilot seat.
        // (What it leaves is not pinned: the conversion writes fire, and fire with nothing to burn
        // goes out on its own next tick, so both fire and air are the rule doing its job — what
        // matters is that the seat did not survive.) This is what makes the subject's survival
        // below a measurement rather than a tautology.
        String placedHot = placeLoneSeat(CTRL2_X, CTRL2_Y, CTRL2_Z);
        assertTrue("control leg: a lone pilot seat placed into this atmosphere must NOT survive it. "
                        + "It did, so this run cannot exhibit the bug at all and the subject assertion "
                        + "below would pass for the wrong reason: " + placedHot,
                !PILOT_SEAT.equals(blockOf(placedHot)));
        clearPos(CTRL2_X, CTRL2_Y, CTRL2_Z);
        String control = placedHot;

        // SUBJECT: the same atmosphere, but the seat arrives as part of a crossing structure.
        String srcInfo = exec("artest vs ship-info 0 " + SRC_X + " " + SRC_Y + " " + SRC_Z);
        assertTrue("source ship not managed by VS before the crossing: " + srcInfo,
                srcInfo.contains("\"managed\":true"));
        String cross = exec("artest vs ship-repack 0 "
                + (int) extractDouble(srcInfo, "posX") + " " + (int) extractDouble(srcInfo, "posY")
                + " " + (int) extractDouble(srcInfo, "posZ")
                + " " + DST_X + " " + DST_Y + " " + DST_Z);
        assertTrue("the crossing itself failed, so the seat question was never asked: " + cross,
                cross.contains("\"ok\":true"));
        assertTrue("the crossed ship never re-loaded at the destination: " + cross,
                waitForLoadedShip() >= 1);

        // The crew's own question: is there a seat on the arrived ship, still linked to its computer?
        String post = exec("artest vs seat-input 0 0 0 0 0 0 0");
        assertTrue("the arrived ship has NO pilot seat - it burned on the way in, and its crew has "
                        + "nowhere to sit. control=" + control + " post=" + post,
                post.contains("\"seatFound\":true"));
        assertTrue("the arrived ship's seat no longer resolves its flight computer: " + post,
                post.contains("\"afcResolved\":true"));

        // And the block itself is a seat, not the fire that replaced it.
        String seatBlock = exec("artest space get-block 0 " + extractInt(post, "seatX")
                + " " + extractInt(post, "seatY") + " " + extractInt(post, "seatZ"));
        assertEquals("the block at the arrived ship's seat position is not a pilot seat: " + seatBlock,
                PILOT_SEAT, blockOf(seatBlock));
    }

    @After
    public void restoreSharedServerState() throws Exception {
        // Shared-harness contract: a superheated overworld left behind would burn the next test's
        // fixtures, and a pinned ship set would hide the next test's unload behaviour.
        if (!serverHasVs()) {
            return;
        }
        if (originalTemperature != Integer.MIN_VALUE) {
            exec("artest planet set-temp 0 " + originalTemperature);
        }
        exec("artest vs permaload false");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    /** Poll for a loaded VS ship (assembly is asynchronous). Bounded ~10 s. Returns the loaded count. */
    private int waitForLoadedShip() throws Exception {
        for (int i = 0; i < 40; i++) {
            if (extractInt(exec("artest vs ship-count-all 0"), "count") >= 1) {
                exec("artest vs load-ships 0");
                int loaded = extractInt(exec("artest vs ship-count 0"), "count");
                if (loaded >= 1) {
                    return loaded;
                }
            }
            Thread.sleep(250);
        }
        return 0;
    }

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air").contains("\"ok\":true"));
    }

    private void clearPos(int x, int y, int z) throws Exception {
        exec("artest fill 0 " + x + " " + y + " " + z + " " + x + " " + y + " " + z + " minecraft:air");
    }

    /** Clear a position, place a lone pilot seat there the ordinary way, and read the result back. */
    private String placeLoneSeat(int x, int y, int z) throws Exception {
        String box = x + " " + y + " " + z + " " + x + " " + y + " " + z;
        assertTrue("could not clear the position at " + box,
                exec("artest fill 0 " + box + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not place a pilot seat at " + box,
                exec("artest fill 0 " + box + " " + PILOT_SEAT).contains("\"ok\":true"));
        return exec("artest space get-block 0 " + x + " " + y + " " + z);
    }

    private String placeFixture(int baseX, int baseY, int baseZ) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " with-pilot-seat");
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static String blockOf(String json) {
        Matcher m = Pattern.compile("\"block\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "<no block field in " + json + ">";
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
