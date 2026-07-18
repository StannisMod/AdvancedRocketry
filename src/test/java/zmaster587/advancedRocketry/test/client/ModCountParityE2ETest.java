package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * E2e regression guard for the dummy-mod-container removal
 * (dercodeKoenig/AdvancedRocketry#71).
 *
 * <p>The ASM coremod used to register a {@code DummyModContainer}
 * ({@code advancedrocketrycore}) with empty lifecycle handlers. Its single
 * observable effect was the vanilla main-menu line "N mods loaded, M mods
 * active" disagreeing by one: the phantom container counted as loaded but
 * never became active. The {@code report_mods} probe reads the exact two
 * lists that menu line renders ({@code FMLCommonHandler.getBrandings} &rarr;
 * {@code Loader.getModList()} / {@code getActiveModList()}), on the real
 * client — so this is the player-visible layer of the report.</p>
 */
public class ModCountParityE2ETest extends AbstractClientE2ETest {

    @Test
    public void everyLoadedModIsActiveAndTheDummyContainerIsGone() throws Exception {
        bot().waitForWorld();

        JsonObject mods = bot().reportMods();
        int loaded = mods.get("loadedCount").getAsInt();
        int active = mods.get("activeCount").getAsInt();
        JsonArray ids = mods.getAsJsonArray("loadedModIds");

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
}
