package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Repro for findings C002 and
 * C155 (HIGH) — the MANDATORY player-visible client side.
 *
 * <p>{@code PacketSatellite.readClient} ({@code @SideOnly(CLIENT)}) is the
 * satellite-sync wire handler: it calls {@code SatelliteRegistry
 * .createFromNBT(nbt)} and catches only {@code IOException}
 * (PacketSatellite.java:50-55). When the packet carries a dataType the
 * client's registry does not know (a save/join with a different mod set —
 * the real cross-modpack case), {@code getNewSatellite} returns null and
 * {@code createFromNBT} NPEs; the NPE escapes {@code readClient}, propagates
 * up the netty pipeline, and disconnects/crashes the client.</p>
 *
 * <p>Stimulus is the REAL client wire path: the server broadcasts a
 * {@code PacketSatellite} with an unregistered dataType via the
 * {@code artest satellite announce-unknown} probe, and the client's own
 * {@code readClient} deserializes it. Observation is REAL client state:
 * {@code reportState().worldReady} / {@code screen} — a disconnect flips
 * {@code worldReady} to false and pushes {@code GuiDisconnected}.</p>
 *
 * <p><b>Corrected contract, pinned here (C002/C155 fix, Path B — drop)</b>:
 * the client stays in-world — {@code createFromNBT} returns null for the
 * unknown type and {@code readClient} skips it (explicit null-guard), so no
 * NPE escapes the packet handler and the connection survives. This test
 * previously pinned the disconnect (polarity flipped when the fix landed).
 * Recorded as a known defect.</p>
 */
public class SatelliteUnknownTypeClientE2ETest extends AbstractClientE2ETest {

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void unknownSatelliteTypeOnWireDoesNotCrashClient() throws Exception {
        bot().waitForWorld();

        JsonObject start = bot().reportState();
        assertTrue("client must begin in-world (worldReady=true): " + start,
                start.get("worldReady").getAsBoolean());

        // Server announces a satellite whose type is not in the registry.
        String announce = exec("artest satellite announce-unknown 0");
        assertTrue("announce-unknown probe must succeed: " + announce,
                announce.contains("\"ok\":true"));

        // Fixed: readClient's createFromNBT returns null for the unknown type and
        // readClient skips it (null-guard), so the client must NOT disconnect.
        boolean leftWorld = false;
        String screen = "";
        boolean worldReady = true;
        for (int waited = 0; waited < 6000; waited += 250) {
            bot().waitTicks(5);
            JsonObject st = bot().reportState();
            worldReady = st.get("worldReady").getAsBoolean();
            screen = st.has("screen") ? st.get("screen").getAsString() : "";
            if (!worldReady || screen.toLowerCase().contains("disconnect")) {
                leftWorld = true;
                break;
            }
        }

        assertFalse("an unknown satellite type on the wire must NOT disconnect/crash "
                + "the client after the fix — PacketSatellite.readClient's createFromNBT "
                + "returns null for the unresolved type and readClient skips it instead "
                + "of NPEing. worldReady=" + worldReady + " screen=" + screen,
                leftWorld);
    }
}
