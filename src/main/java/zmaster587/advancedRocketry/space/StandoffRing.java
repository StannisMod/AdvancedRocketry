package zmaster587.advancedRocketry.space;

import java.util.List;

/**
 * Where a craft is put down NEAR something rather than on top of it.
 *
 * <p>Three placements want the same rule and used to reason about it apart: a ship entering space
 * beside its launch body, a ship arriving from a jump beside its destination, and anything else that
 * must not land inside a proximity trigger. The rule is one sentence &mdash; stand a fixed distance
 * off, spread the bearing so simultaneous placements do not stack, and never leave the anchor's own
 * cell.</p>
 *
 * <p><b>The cell bound is not a detail.</b> An offset that carries into a neighbouring sector puts
 * the craft in a cell nobody materialized, nobody bound to a slot world, and nobody told the ledger
 * about, so {@link GalacticCoord#plusLocalSaturating} is used rather than
 * {@link GalacticCoord#plusLocal}: a placement near a cell face ends slightly closer than asked,
 * which is harmless, instead of somewhere else entirely, which is not.</p>
 *
 * <p>Pure geometry &mdash; no world, no registry, no clock. Radii are {@code tunable}.</p>
 */
public final class StandoffRing {

    /** Evenly-spaced candidate bearings tried before the best-clearance one wins. {@code tunable}. */
    public static final int RING_CANDIDATES = 8;

    private StandoffRing() {
    }

    /**
     * One point {@code ringBlocks} from {@code anchor} on its own XZ plane, the bearing derived from
     * {@code seed} so two craft placed at one body in the same tick do not share a point.
     *
     * <p>The result is never FURTHER from the anchor than {@code ringBlocks}: a caller that also uses
     * the radius as a threshold &mdash; the survey tier is exactly the entry ring today &mdash; would
     * otherwise flip side depending on the bearing, because rounding two components can overshoot.</p>
     */
    public static GalacticCoord pointAround(GalacticCoord anchor, long ringBlocks, int seed) {
        if (anchor == null || ringBlocks <= 0L) {
            return anchor;
        }
        return atBearing(anchor, bearingOf(seed), ringBlocks);
    }

    /**
     * A point {@code ringBlocks} from {@code aim} that clears every coordinate in {@code occupied} by
     * at least {@code clearanceBlocks}.
     *
     * <p>Returns {@code aim} UNCHANGED when there is nothing to stand off from &mdash; a cell holding
     * no body, or a coordinate the pilot typed by hand. Displacing those would move a destination
     * that was CHOSEN rather than derived, and no proximity trigger can fire where no body is.</p>
     *
     * <p>When no bearing clears every occupant, the candidate with the largest minimum clearance
     * wins. A placement is a best effort and never a refusal: a craft that cannot be placed well is
     * still placed.</p>
     */
    public static GalacticCoord standoffFrom(GalacticCoord aim, List<GalacticCoord> occupied,
                                             long ringBlocks, long clearanceBlocks, int seed) {
        if (aim == null || occupied == null || occupied.isEmpty() || ringBlocks <= 0L) {
            return aim;
        }
        GalacticCoord best = null;
        double bestClearance = -1.0;
        double base = bearingOf(seed);
        for (int i = 0; i < RING_CANDIDATES; i++) {
            GalacticCoord candidate = atBearing(aim,
                    base + (i * 2.0 * Math.PI / RING_CANDIDATES), ringBlocks);
            double clearance = minimumDistanceTo(candidate, occupied);
            if (clearance >= clearanceBlocks) {
                return candidate;
            }
            if (clearance > bestClearance) {
                bestClearance = clearance;
                best = candidate;
            }
        }
        return best == null ? aim : best;
    }

    /** The bearing a seed selects, in radians — one of 256 evenly spaced directions. */
    private static double bearingOf(int seed) {
        return ((seed & 0xFF) / 256.0) * Math.PI * 2.0;
    }

    /**
     * {@code anchor} offset by {@code ringBlocks} at {@code bearing} in the XZ plane, held inside the
     * anchor's cell. The length is trimmed back after rounding so the result is never further than
     * asked; the direction survives the trim.
     */
    private static GalacticCoord atBearing(GalacticCoord anchor, double bearing, long ringBlocks) {
        long dx = Math.round(Math.cos(bearing) * ringBlocks);
        long dz = Math.round(Math.sin(bearing) * ringBlocks);
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (length > ringBlocks && length > 0.0) {
            double scale = ringBlocks / length;
            dx = (long) (dx * scale);
            dz = (long) (dz * scale);
        }
        return anchor.plusLocalSaturating(dx, 0L, dz);
    }

    /** The distance from {@code point} to the nearest occupant, or {@link Double#MAX_VALUE} if none. */
    private static double minimumDistanceTo(GalacticCoord point, List<GalacticCoord> occupied) {
        double nearest = Double.MAX_VALUE;
        for (GalacticCoord other : occupied) {
            if (other == null) {
                continue;
            }
            double distance = point.staticFrameDistanceTo(other);
            if (distance < nearest) {
                nearest = distance;
            }
        }
        return nearest;
    }
}
