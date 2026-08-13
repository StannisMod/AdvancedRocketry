package zmaster587.advancedRocketry.test.unit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.Test;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Save/wire compatibility for an unregistered satellite type (C002/C155).
 *
 * <p>{@link SatelliteRegistry#getNewSatellite(String)} is the dispatch point
 * for "load a satellite from NBT" (called by
 * {@link SatelliteRegistry#createFromNBT(NBTTagCompound)}). When a save/packet
 * carries a satellite whose type was registered by a companion mod that's been
 * removed from the modpack, the type can't be reconstructed.</p>
 *
 * <p><b>Corrected contract (C002/C155 fix, Path B — drop)</b>:
 * {@code getNewSatellite} returns {@code null} for an unregistered id (by
 * design — callers such as {@code ItemSatellite} and {@code TileSatelliteHatch}
 * rely on that null), and {@code createFromNBT} also returns {@code null} for an
 * unresolvable type so its callers ({@code DimensionProperties.readFromNBT},
 * {@code PacketSatellite.readClient}, {@code PacketSatellitesUpdate.readClient})
 * drop the satellite. Previously {@code createFromNBT} dereferenced the null →
 * {@code NullPointerException}, which {@code PacketSatellite.readClient} and
 * {@code EntityRocket.readEntityFromNBT} propagated as a client disconnect /
 * entity-load failure. A placeholder ({@code SatelliteDefunct}) was rejected:
 * it re-saved as {@code dataType="poo"} (getKey fallback), permanently
 * destroying the original type, and it ticked while inert.</p>
 *
 * <p>These tests pin the corrected contract. Recorded as a known defect.</p>
 */
public class SatelliteRegistryFallbackTest {

    /** Minimal SatelliteBase stand-in for the positive control. The real
     *  satellite classes (SatelliteBiomeChanger, etc.) hit
     *  {@code Biome.getBiome(0)} in their no-arg ctor which requires the
     *  Minecraft Bootstrap to have run — fine in the mod-init
     *  context, not fine in pure unit-tier. Mirrors the
     *  {@code TestSatellite} class in SatellitePropertiesTest. */
    public static class TestStandInSatellite extends SatelliteBase {
        @Override public String getInfo(World world) { return "test"; }
        @Override public String getName() { return "test_satellite"; }
        @Override public boolean performAction(EntityPlayer player, World world, BlockPos pos) { return false; }
        @Override public double failureChance() { return 0.0d; }
    }

    private static final String KNOWN_TYPE_KEY =
            "ar:gap4_known_type_for_positive_control";

    /** getNewSatellite returns null for an unregistered id — by design.
     *  Callers (ItemSatellite, TileSatelliteHatch, …) rely on the null to
     *  detect an unresolvable type. */
    @Test
    public void getNewSatelliteReturnsNullForUnknownType() {
        SatelliteBase result = SatelliteRegistry.getNewSatellite(
                "advancedrocketry:nonexistent.satellite.type.for.gap4.test");
        assertNull("getNewSatellite must return null for an unregistered id", result);
    }

    /** createFromNBT returns null for an unknown/unregistered dataType (the
     *  caller drops the satellite) instead of NPEing the load/wire path —
     *  the C002/C155 fix. No SatelliteBase.readFromNBT runs, so this needs no
     *  Bootstrap. */
    @Test
    public void createFromNBTWithUnknownTypeReturnsNull() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("dataType",
                "advancedrocketry:nonexistent.satellite.type.for.gap4.test");
        SatelliteBase result = SatelliteRegistry.createFromNBT(nbt);
        assertNull("createFromNBT must return null for an unresolvable dataType "
                + "(callers drop it) — not NPE, not a placeholder", result);
    }

    /** Positive control: a KNOWN satellite type produces a real instance —
     *  pins that the registry dispatch works for the happy path so the
     *  unknown-type tests can't pass by registry-wide breakage. Uses a
     *  unit-tier-friendly stand-in (no Bootstrap dependency). */
    @Test
    public void knownSatelliteTypeProducesNonNullInstance() {
        SatelliteRegistry.registerSatellite(KNOWN_TYPE_KEY, TestStandInSatellite.class);
        SatelliteBase result = SatelliteRegistry.getNewSatellite(KNOWN_TYPE_KEY);
        assertNotNull("registered type must resolve via SatelliteRegistry — "
                        + "if this fails the registry dispatch itself is broken",
                result);
    }
}
