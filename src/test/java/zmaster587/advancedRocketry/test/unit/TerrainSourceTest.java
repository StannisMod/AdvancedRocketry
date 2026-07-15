package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.dimension.TerrainSource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Contract tests for {@link TerrainSource#byName} — the tolerant, save-schema-facing parser used by both
 * the NBT and XML read paths. Pure JUnit, no MC bootstrap (TerrainSource logs through a self-contained
 * logger, mirroring {@code UniverseRegistry}).
 *
 * <p>The contract: every constant round-trips case-insensitively; anything unrecognised (null, blank, or
 * garbage) degrades to {@link TerrainSource#NATIVE} and never throws, so a mis-authored planetDefs entry
 * cannot crash world load.</p>
 */
public class TerrainSourceTest {

    @Test
    public void byNameRoundTripsEveryConstantCaseInsensitively() {
        for (TerrainSource ts : TerrainSource.values()) {
            assertSame(ts, TerrainSource.byName(ts.name()));
            assertSame("lower-case must resolve", ts, TerrainSource.byName(ts.name().toLowerCase()));
            assertSame("surrounding whitespace must be tolerated", ts, TerrainSource.byName("  " + ts.name() + "  "));
        }
    }

    @Test
    public void byNameDefaultsToNativeForNullBlankOrUnknown() {
        assertSame(TerrainSource.NATIVE, TerrainSource.byName(null));
        assertSame(TerrainSource.NATIVE, TerrainSource.byName(""));
        assertSame(TerrainSource.NATIVE, TerrainSource.byName("   "));
        assertSame(TerrainSource.NATIVE, TerrainSource.byName("not_a_mode"));
        assertSame(TerrainSource.NATIVE, TerrainSource.byName("123"));
    }

    @Test
    public void persistedFormIsTheEnumName() {
        // The write side stores TerrainSource.name(); this pins the exact tokens the save/XML schema uses.
        assertEquals("NATIVE", TerrainSource.NATIVE.name());
        assertEquals("MOD_WORLDTYPE", TerrainSource.MOD_WORLDTYPE.name());
        assertEquals("TEMPLATE", TerrainSource.TEMPLATE.name());
    }
}
