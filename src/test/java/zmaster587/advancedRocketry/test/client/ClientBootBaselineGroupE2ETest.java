package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Everything that is true of a booted client before any feature is exercised: the bridge answers,
 * the mod list agrees with itself, and the harness client is silent.
 *
 * <h2>Why these three share one harness</h2>
 *
 * <p>Each was a single-method class, and each spent better than 95 % of its wall clock booting a
 * server and a client in order to ask one question of them. Measured 2026-08-07 on the maintainer's
 * box at 8 forks, from the result XML: {@code ClientConnectSmokeTest} 115.2 s,
 * {@code ModCountParityE2ETest} 107.9 s, {@code TestClientSoundMutedE2ETest} 76.1 s — three boots,
 * three client JVMs, ~5 minutes of machine time for three assertions that between them take
 * seconds.</p>
 *
 * <p>They also share a property none of the other groups has: <b>not one of them mutates anything.</b>
 * No block is placed, no item given, no dimension entered, no global flipped. That makes this the
 * safest possible group and the natural seed for a smoke lane — if these three are red, nothing else
 * in the tier can mean anything, and the rest of the run is wasted machine time.</p>
 *
 * <p>Source classes, merged verbatim (method names preserved so CI history greps):
 * {@code ClientConnectSmokeTest}, {@code ModCountParityE2ETest},
 * {@code TestClientSoundMutedE2ETest}.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClientBootBaselineGroupE2ETest extends AbstractSharedClientE2ETest {

    private static final String CLIENT_PROXY = "zmaster587.advancedRocketry.client.ClientProxy";

    @Override
    protected String subsystem() {
        return "client-boot";
    }

    /** From {@code ClientConnectSmokeTest}: the client bridge handshake round-trips a player view. */
    @Test
    public void clientReportsStateOverBridge() throws Exception {
        scenario().asserting("the client answers report_state over the bridge");
        bot().waitForWorld();
        JsonObject state = bot().reportState();
        assertNotNull("client reportState returned null", state);
        assertTrue("client reportState missing 'ok' key: " + state, state.has("ok"));
    }

    /**
     * From {@code ModCountParityE2ETest}: e2e regression guard for the dummy-mod-container removal
     * (dercodeKoenig/AdvancedRocketry#71).
     *
     * <p>The ASM coremod used to register a {@code DummyModContainer}
     * ({@code advancedrocketrycore}) with empty lifecycle handlers. Its single observable effect was
     * the vanilla main-menu line "N mods loaded, M mods active" disagreeing by one: the phantom
     * container counted as loaded but never became active. The {@code report_mods} probe reads the
     * exact two lists that menu line renders ({@code FMLCommonHandler.getBrandings} &rarr;
     * {@code Loader.getModList()} / {@code getActiveModList()}), on the real client — so this is the
     * player-visible layer of the report.</p>
     */
    @Test
    public void everyLoadedModIsActiveAndTheDummyContainerIsGone() throws Exception {
        scenario().asserting("the client's own mod lists agree, and carry no phantom container");
        bot().waitForWorld();

        JsonObject mods = bot().reportMods();
        int loaded = mods.get("loadedCount").getAsInt();
        int active = mods.get("activeCount").getAsInt();
        JsonArray ids = mods.getAsJsonArray("loadedModIds");
        scenario().record("loadedCount", loaded).record("activeCount", active);

        StringBuilder idList = new StringBuilder();
        boolean hasAr = false;
        boolean hasDummy = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i).getAsString();
            idList.append(id).append(' ');
            hasAr |= "advancedrocketry".equals(id);
            hasDummy |= "advancedrocketrycore".equals(id);
        }

        assertTrue("advancedrocketry must be among loaded mods: " + idList, hasAr);
        assertFalse("the vestigial dummy container advancedrocketrycore must be gone "
                + "(issue dercodeKoenig/AdvancedRocketry#71): " + idList, hasDummy);
        // The actual user-visible symptom: the title-screen counts must agree.
        // A loaded-but-never-active container makes loadedCount = activeCount + 1.
        assertEquals("every loaded mod must be active (title-screen 'loaded' vs 'active' "
                + "mismatch — phantom container?): " + idList, loaded, active);
    }

    /**
     * From {@code TestClientSoundMutedE2ETest}: a harness-spawned test client must run SILENT.
     *
     * <p>Automated client e2e boots a real client with real audio on the dev box;
     * {@code ClientProxy.muteTestClientSound} zeroes the master sound level on the first client tick
     * where the sound handler is up, gated on the {@code -Dforge.test.client=true} marker that every
     * {@code RealClientHarness} client carries (and a manual {@code runClient} does not).</p>
     *
     * <p>This observes the REAL client state: the proxy publishes the master level it read back from
     * {@code GameSettings} after muting, and this asserts that value is 0 — so it fails if the mute
     * is removed, mis-gated, or clamped, not merely if the code path is skipped.</p>
     */
    @Test
    public void harnessTestClientHasMasterSoundMuted() throws Exception {
        scenario().asserting("the harness client's master sound level is 0");
        bot().waitForWorld();

        // The mute lands on the first client tick with the sound handler up; poll until the
        // proxy has published the applied master level (NaN until then).
        String raw = "NaN";
        for (int i = 0; i < 40 && "NaN".equalsIgnoreCase(raw); i++) {
            bot().waitTicks(5);
            raw = bot().readStaticField(CLIENT_PROXY, "testClientMasterVolume")
                    .get("value").getAsString();
        }
        scenario().record("testClientMasterVolume", raw);

        assertNotNull("proxy must publish the applied master volume", raw);
        float master = Float.parseFloat(raw);
        assertEquals("a harness test client must have master sound muted to 0",
                0.0f, master, 1e-6f);
    }
}
