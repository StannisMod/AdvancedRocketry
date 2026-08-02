package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A REAL separate-JVM client receives the server's per-slot system-body broadcast and stores it in
 * {@code PacketSystemBodiesSync.CLIENT_BODIES} &mdash; the client half of the {@code SystemBodiesProducer}
 * render feed (the data {@code BoundarySky} draws). The server produces the packet from the live
 * {@code ShipLedger} + {@code UniverseRegistry} on its throttled tick; the client's own
 * {@code executeClient} populates the static, which we read back ON THE CLIENT THREAD via
 * {@code read_static_field}.
 *
 * <p>Honest client e2e: delete the client jar and there is no client static to read, so the assertion
 * cannot pass server-side. This pins the producer&rarr;wire&rarr;client DATA path (dim key present, the
 * descend-target flag + planet dim + ship&rarr;body direction survive to the client). The billboard
 * APPEARANCE is {@code BoundarySkyRendersInSlotCellE2ETest}'s.</p>
 *
 * <p><b>The client is put INSIDE the cell's slot world before it is asked what it received.</b> A sky
 * is per-dimension and a player renders exactly one world, so the server sends each player only the
 * dimension he is in; broadcasting every live cell's sky to everybody was waste that grew with the
 * pool, and grew again when a cell's entry became its whole SYSTEM (C14 CON-C14-14). Standing the
 * subject where the bodies are is therefore not a workaround &mdash; it is the arrangement a real
 * pilot is in, and the control leg below is what says so.</p>
 */
public class SystemBodiesClientSyncE2ETest extends AbstractClientE2ETest {

    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");
    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");
    /** The slot the settle actually bound the cell to — the one place that decides it. */
    private static final Pattern BOUND_DIM = Pattern.compile("\"slotDim\":(-?\\d+)");
    private static final String CLIENT_BODIES_CLASS =
            "zmaster587.advancedRocketry.network.PacketSystemBodiesSync";

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @After
    public void tearDownStack() {
        try {
            exec("artest space entry-clear");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aRealClientReceivesTheSettledShipsCellBodies() throws Exception {
        // Install the space stack (SpaceManager + ShipLedger) so the broadcast tick runs and a ledger
        // exists; entry-setup constructs VS ops but touches no VS at load, so no -PwithVS is needed.
        String setup = exec("artest space entry-setup 1");
        Matcher dimM = FIRST_DIM.matcher(setup);
        assertTrue("entry-setup must return a slot dim: " + setup, dimM.find());

        // A descend-target PLANET at cell (0,5000,0), local (1000,500,-300). sy=5000 dodges the fallback
        // stars (all at sy=sz=0), so bodiesAt returns ONLY this POI.
        String poi = exec("artest space add-poi 0 5000 0 1000 500 -300 PLANET 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

        // A settled ship at that cell's CENTRE: the producer maps its slot dim -> [the planet], carried
        // as the ship->body direction (planet.local - ship.local = 1000,500,-300).
        //
        // The dimension under test is the one the subsystem ACTUALLY bound the cell to, read back from
        // the settle. It is not the test's to choose: slot ids are minted per boot and handed out as
        // cells come and go, so a number picked here is only a guess at the binding — and a guess that
        // happens to agree would still pass on a build that keyed the feed to the wrong world.
        String settle = exec("artest space ledger-settle 0 5000 0 " + dimM.group(1));
        assertTrue("ledger-settle must succeed: " + settle, settle.contains("\"ok\":true"));
        Matcher boundM = BOUND_DIM.matcher(settle);
        assertTrue("the settle must report which slot the cell was bound to: " + settle, boundM.find());
        int slotDim = Integer.parseInt(boundM.group(1));

        // CONTROL, and it runs FIRST, while the player is still OUTSIDE the cell: a sky he is not in
        // is a sky he is not sent. Without this leg the assertion below is satisfied just as well by
        // a build that broadcasts every live cell to everybody, which is what this one replaced.
        String outside = null;
        for (int i = 0; i < 8; i++) {
            bot().waitTicks(5);
            JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
            outside = sf.get("isNull").getAsBoolean() ? "" : sf.get("value").getAsString();
        }
        assertFalse("a player who is not in the cell's world must not be sent its sky, got: " + outside,
                outside != null && outside.contains(slotDim + "=[RenderBody{"));

        // Put the subject where a pilot in that cell would be: inside the slot world the cell is bound
        // to, through the production transfer.
        String player = exec("artest player health");
        Matcher nameM = PLAYER_NAME.matcher(player);
        assertTrue("player health must echo the player name: " + player, nameM.find());
        String enter = exec("artest space enter " + nameM.group(1) + " " + slotDim + " 0.5 200 0.5");
        assertTrue("space enter must succeed: " + enter, enter.contains("\"ok\":true"));

        // The throttled broadcast (~20 ticks) reaches the REAL client; poll its OWN CLIENT_BODIES static.
        String value = null;
        boolean got = false;
        for (int i = 0; i < 16 && !got; i++) {
            bot().waitTicks(5);
            JsonObject sf = bot().readStaticField(CLIENT_BODIES_CLASS, "CLIENT_BODIES");
            if (!sf.get("isNull").getAsBoolean()) {
                value = sf.get("value").getAsString();
                got = value.contains(slotDim + "=[") && value.contains("RenderBody{");
            }
        }
        assertTrue("client CLIENT_BODIES must carry the slot dim's bodies, got: " + value, got);

        // The body arrived intact: the descend-target flag, the planet dim, and the ship->body direction.
        assertTrue("descend-target flag survived to the client: " + value, value.contains("descend=true"));
        assertTrue("planet dim survived: " + value, value.contains("dim=0"));
        assertTrue("ship->body direction survived: " + value, value.contains("dir=1000,500,-300"));
    }
}
