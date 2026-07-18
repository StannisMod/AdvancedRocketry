package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Repro (bug-report-workflow Step 1) for finding C172 / FIND-151 (MED) — the
 * player-visible client side.
 *
 * <p>{@code TileBiomeScanner.getModules} runs inside {@code if (world.isRemote)}
 * and dereferences
 * {@code SpaceObjectManager.getSpaceStationFromBlockCoords(pos).getOrbitingPlanetId()}
 * with no null guard (TileBiomeScanner.java:78-79). When the scanner is NOT on a
 * registered space station that lookup returns null, so building the scanner's
 * modular GUI NPEs on the client — a client crash for the player opening it. The
 * bug only fires when {@code suitable} is also true (the column below is all air).</p>
 *
 * <p><b>Why {@code getModules} is driven directly:</b> the GUI-open path
 * ({@code BlockMultiblockMachine.onBlockActivated}) only opens the GUI once the
 * multiblock {@code isComplete()}, and the scanner structure requires a
 * {@code blockAluminum}-oredict block that only an external mod provides — so the
 * scanner cannot be assembled in the bare test environment. The bug is in
 * {@code getModules}, a public method independent of assembly, so the test drives
 * that exact client-side production method on a real client world/tile via the
 * {@code tile_modules_throws} bridge command, off-station.</p>
 *
 * <p><b>Corrected contract, pinned here (C172 fix, Path B — null-guard)</b>:
 * building the scanner GUI off-station does not throw.</p>
 */
public class BiomeScannerGetModulesOffStationE2ETest extends AbstractClientE2ETest {

    private static final int X = 8, Y = 64, Z = 8;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void buildingScannerGuiOffStationDoesNotThrowOnClient() throws Exception {
        bot().waitForWorld();

        // Overworld (dim 0) has no space stations, so getSpaceStationFromBlockCoords
        // is null there — the off-station case.
        String place = exec("artest place 0 " + X + " " + Y + " " + Z + " advancedrocketry:biomeScanner");
        assertTrue("scanner must place: " + place, place.contains("\"placed\":true"));

        // Clear the column below the scanner so getModules' `suitable` gate is true;
        // that is the branch that reaches the null deref.
        exec("fill " + X + " 1 " + Z + " " + X + " " + (Y - 1) + " " + Z + " minecraft:air");
        // Stand the player on the scanner so its chunk is client-tracked.
        exec("tp @a " + (X + 0.5) + " " + (Y + 1) + " " + (Z + 0.5) + " 0 60");
        bot().waitTicks(30);

        JsonObject res = bot().tileModulesThrows(X, Y, Z);
        assertFalse("building the biome-scanner GUI off-station must not throw on the "
                        + "client (getModules must null-guard the absent space station): " + res,
                res.get("threw").getAsBoolean());
    }
}
