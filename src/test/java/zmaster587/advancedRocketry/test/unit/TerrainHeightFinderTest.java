package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.TerrainHeightFinder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the pure descent paste-Y geometry in {@link TerrainHeightFinder}. Pins the three
 * outcomes the caller branches on — the ship clears the terrain, the build-height cap governs, or the
 * ship is too tall to fit and the descent aborts ({@code -1}) — without pinning the tunable clearance
 * or the build-height literal (both referenced symbolically). No MC boot: {@code pasteY} is a pure
 * function of integers, so the world wrapper is out of scope here.
 */
public class TerrainHeightFinderTest {

    @Test
    public void fitsAboveTerrainWhenThereIsRoom() {
        int terrainTop = 64;
        int clearance = 8;
        int shipHeight = 20;
        int y = TerrainHeightFinder.pasteY(terrainTop, clearance, shipHeight);

        assertTrue("paste sits above the terrain", y > terrainTop);
        assertEquals("uncapped descent honors the requested clearance", terrainTop + clearance, y);
        assertTrue("the whole ship stays under the build ceiling",
                y + shipHeight <= TerrainHeightFinder.MAX_BUILD_Y);
    }

    @Test
    public void capHoldsTheShipUnderBuildHeight() {
        // A clearance large enough to push the ship past the ceiling: the cap must win.
        int terrainTop = 200;
        int shipHeight = 10;
        int y = TerrainHeightFinder.pasteY(terrainTop, 100, shipHeight);

        assertTrue("still above the terrain", y > terrainTop);
        assertEquals("clamped so the ship top rests just under the build ceiling",
                TerrainHeightFinder.MAX_BUILD_Y - shipHeight, y);
    }

    @Test
    public void tooTallOverTallTerrainReturnsMinusOne() {
        // A tall ship over high terrain: no gap between terrain and build ceiling -> abort signal.
        int y = TerrainHeightFinder.pasteY(250, 8, 20);
        assertEquals("descent aborts when the ship cannot fit above the terrain", -1, y);
    }

    @Test
    public void aPasteLandingExactlyOnTerrainAlsoAborts() {
        // Boundary: the clamped result equal to the terrain top is still a bury, so it aborts too.
        int shipHeight = 20;
        int terrainTop = TerrainHeightFinder.MAX_BUILD_Y - shipHeight; // cap == terrainTop
        int y = TerrainHeightFinder.pasteY(terrainTop, 8, shipHeight);
        assertEquals("no strictly-positive gap above terrain -> abort", -1, y);
    }
}
