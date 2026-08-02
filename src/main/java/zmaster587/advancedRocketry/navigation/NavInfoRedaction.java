package zmaster587.advancedRocketry.navigation;

import java.util.LinkedHashMap;
import java.util.Map;

import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.InfoTier;
import zmaster587.advancedRocketry.universe.PlanetInfoField;

/**
 * What a nav computer is allowed to tell its pilot about a body.
 *
 * <p>Knowledge here is earned by <b>getting closer</b>. From across the galaxy a body is a coordinate,
 * a name and the coarse things a telescope can see; inside its system you can read its surface; only
 * from its own zone do you see what is actually on it. The crystal's recorded detail is a memory of a
 * past observation and counts too — you do not forget a planet you once orbited by flying away.</p>
 *
 * <p>This class decides the tier and drops every field above it. It is deliberately pure: the point of
 * the redaction is that the SERVER applies it before anything crosses the wire, so a client that never
 * receives a field cannot leak it, however the GUI is modified.</p>
 */
public final class NavInfoRedaction {

    /**
     * How close (blocks) a ship must be to a body to read it at {@link InfoTier#ORBIT}. Reuses the
     * entry ring so "in the body's zone" means one thing across the mod. {@code tunable}.
     */
    public static final long ORBIT_ZONE_BLOCKS =
            zmaster587.advancedRocketry.space.ShipEntryController.ENTRY_RING_BLOCKS;

    private NavInfoRedaction() {
    }

    /**
     * The tier at which {@code shipCoord} may read the body NAMED {@code bodyName}, which is
     * {@code distanceBlocks} away right now, given what the ship's crystal already {@code recorded}
     * about it (may be {@code null} for an unrecorded body).
     *
     * <p>Proximity and memory both count, and the better of the two wins: flying up to a planet reveals
     * it whether or not it was ever recorded, and a planet surveyed from orbit long ago stays surveyed
     * once you leave.</p>
     *
     * <p>The two clauses want two different things from the body, which is why they are two
     * parameters. ORBIT is a real distance and has to be measured through both cells' frames at the
     * moment of asking (C15 ADDR-9), so the caller — which has the registry — supplies it. APPROACH
     * is "we are in the same neighbourhood", and a neighbourhood is a NAME: comparing cell keys is
     * exactly right and needs no tick at all.</p>
     */
    public static InfoTier tierFor(GalacticCoord shipCoord, GalacticCoord bodyName,
                                   double distanceBlocks, InfoTier recorded) {
        InfoTier byProximity = InfoTier.TELESCOPE;
        if (shipCoord != null && bodyName != null) {
            if (distanceBlocks <= ORBIT_ZONE_BLOCKS) {
                byProximity = InfoTier.ORBIT;
            } else if (shipCoord.cellKey().equals(bodyName.cellKey())) {
                byProximity = InfoTier.APPROACH;
            }
        }
        if (recorded == null) {
            return byProximity;
        }
        return recorded.atLeast(byProximity) ? recorded : byProximity;
    }

    /**
     * Drop every field of {@code full} that a scanner at {@code tier} may not see. Order is preserved so
     * the GUI lists a body's fields the same way every time.
     */
    public static Map<PlanetInfoField, String> redact(Map<PlanetInfoField, String> full, InfoTier tier) {
        Map<PlanetInfoField, String> visible = new LinkedHashMap<>();
        if (full == null) {
            return visible;
        }
        for (Map.Entry<PlanetInfoField, String> e : full.entrySet()) {
            if (e.getKey() != null && PlanetInfoField.isVisible(e.getKey(), tier)) {
                visible.put(e.getKey(), e.getValue());
            }
        }
        return visible;
    }
}
