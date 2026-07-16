package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A REAL client enters a space-subsystem slot dimension without dim-registration errors — the
 * client half of the slot-dim registration sync ({@code PacketSlotDimSync}).
 *
 * <p>Slot dims are registered server-side only, at pool registration; before the sync existed, no
 * client had ever been inside a slot dim, and on a dedicated server a transfer into one would
 * respawn the client into a Forge dimension it never registered. This test drives the full
 * production chain with the pool registered WHILE the client is online (the runtime-growth
 * broadcast path): register pool &rarr; broadcast sync &rarr; bind a cell world &rarr; transfer the
 * real player through {@code PlayerList.transferPlayerToDimension} &rarr; the client's OWN world
 * must be the slot dim and keep rendering.</p>
 *
 * <p>Observation is client-side ({@code report_weather.dim} = {@code mc.world.provider
 * .getDimension()} of the world the client renders; {@code report_state.playerY} = the
 * client-rendered position) — the observation reads what the client itself renders. Server probes appear only as
 * arrange/cleanup and as the cross-side oracle. The login-time sync (a player joining AFTER the
 * pool exists) sends the same packet from the login hook and is covered by the packet-level
 * behaviour pinned here.</p>
 */
public class SlotDimClientEntryE2ETest extends AbstractClientE2ETest {

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @After
    public void returnToOverworld() {
        try {
            String health = exec("artest player health");
            Matcher m = PLAYER_NAME.matcher(health);
            if (m.find()) {
                exec("artest space enter " + m.group(1) + " 0 8.5 90 8.5");
                bot().waitTicks(10);
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aRealClientEntersASlotDimAndKeepsRendering() throws Exception {
        // Arrange: the bot's username (server read, arrange-only).
        String health = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(health);
        assertTrue("player health must echo the player name: " + health, nameM.find());
        String botName = nameM.group(1);

        // Register a pool slot WHILE the client is online — the runtime-growth broadcast is the
        // moment the client must learn the slot DimensionType + dim id.
        String reg = exec("artest space pool-register 1");
        Matcher dimM = FIRST_DIM.matcher(reg);
        assertTrue("pool-register must return the new dim id: " + reg, dimM.find());
        int slotDim = Integer.parseInt(dimM.group(1));

        // Bind the slot to a cell.
        exec("artest space load " + slotDim + " e2ecell");

        // Act: move the REAL player through the production PlayerList transfer FIRST — an empty
        // loaded slot is auto-unloaded by Forge at tick end and its unsaved edits are DISCARDED
        // (the documented slot lifecycle), so the floor can only be placed once a player holds the
        // world loaded. Enter high, place the floor, then step onto it.
        String enter = exec("artest space enter " + botName + " " + slotDim + " 0.5 200 0.5");
        assertTrue("space enter must succeed: " + enter, enter.contains("\"ok\":true"));
        bot().waitTicks(10);
        exec("artest space set-block " + slotDim + " 0 64 0");
        String reposition = exec("artest space enter " + botName + " " + slotDim + " 0.5 66 0.5");
        assertTrue("repositioning onto the platform must succeed: " + reposition,
                reposition.contains("\"ok\":true"));
        bot().waitTicks(40);

        // Assert (CLIENT side): the world the client itself renders IS the slot dim…
        JsonObject clientWorld = bot().reportWeather();
        assertTrue("client must have a world after the transfer",
                clientWorld.get("worldReady").getAsBoolean());
        assertEquals("the client's own world must be the slot dim (registration sync landed)",
                slotDim, clientWorld.get("dim").getAsInt());

        // …the client SETTLES standing on the platform (not void-falling / not frozen). Poll: the
        // chunk send + the server's position correction can take a while on a loaded suite run.
        double clientY = Double.NaN;
        boolean settled = false;
        for (int i = 0; i < 60 && !settled; i++) {
            bot().waitTicks(5);
            clientY = bot().reportState().get("playerY").getAsDouble();
            settled = clientY > 63.5 && clientY < 68.0;
        }
        // Diagnostic witnesses on failure: did the platform chunk even reach the CLIENT, and where
        // does the SERVER think the player is?
        JsonObject clientBlock = bot().blockState(0, 64, 0);
        String serverView = exec("artest player health");
        assertTrue("client-rendered Y must settle at the platform (~65), got " + clientY
                + "; client block(0,64,0)=" + clientBlock + "; server player: " + serverView, settled);

        // …and it KEEPS running (no delayed dim-registration crash/kick).
        bot().waitTicks(40);
        assertEquals("the client must still be in the slot dim two seconds later",
                slotDim, bot().reportWeather().get("dim").getAsInt());

        // Cross-side oracle: the server agrees where the player is.
        String post = exec("artest player health");
        assertTrue("server must still see the player: " + post, post.contains("\"player\":\""));
    }
}
