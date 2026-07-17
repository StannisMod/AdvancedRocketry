package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * APPEARANCE (where/how BoundarySky draws it) stays a maintainer playtest, per {@code BoundarySky}.</p>
 */
public class SystemBodiesClientSyncE2ETest extends AbstractClientE2ETest {

    private static final Pattern FIRST_DIM = Pattern.compile("\"dims\":\\[(-?\\d+)");
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
        int slotDim = Integer.parseInt(dimM.group(1));

        // A descend-target PLANET at cell (0,5000,0), local (1000,500,-300). sy=5000 dodges the fallback
        // stars (all at sy=sz=0), so bodiesAt returns ONLY this POI.
        String poi = exec("artest space add-poi 0 5000 0 1000 500 -300 PLANET 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                poi.contains("\"ok\":true") && poi.contains("\"descendTarget\":true"));

        // A settled ship at that cell's CENTRE bound to the slot dim: the producer maps slotDim -> [the
        // planet], carried as the ship->body direction (planet.local - ship.local = 1000,500,-300).
        String settle = exec("artest space ledger-settle 0 5000 0 " + slotDim);
        assertTrue("ledger-settle must succeed: " + settle, settle.contains("\"ok\":true"));

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
