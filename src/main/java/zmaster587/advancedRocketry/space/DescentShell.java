package zmaster587.advancedRocketry.space;

import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;
import zmaster587.advancedRocketry.universe.SystemBody;

/**
 * Where a body's atmosphere ends — the surface a ship crosses to enter it.
 *
 * <p><b>This is the one place that decides the size of that shell.</b> Crossing it IS entering the
 * atmosphere, so it is a physical surface and not a UI threshold: it is what the descent trigger
 * fires on, what an arrival must be placed outside of, and what every distance shown to a pilot
 * counts down to. Those three had drifted into three separate readings of one constant, which is
 * why they now go through here.</p>
 *
 * <p><b>Everything about a body is measured from this surface, never from its centre.</b> A centre
 * measurement is indistinguishable from a surface measurement only while a body has no size, and
 * that is exactly the assumption that is about to stop holding — see the TODO below. A standoff
 * derived from a centre would place an arrival INSIDE a body big enough, and a range shown to a
 * pilot counts down to a point he can never reach.</p>
 *
 * <p>Pure arithmetic on a body: no world, no client state, no tick. Callable from either side.</p>
 */
public final class DescentShell {

    private DescentShell() {
    }

    /**
     * The radius, in blocks from {@code body}'s address, at which its atmosphere begins.
     *
     * <p>TODO: bodies in the space layer are currently dimensionless points — nothing on
     * {@link SystemBody} carries a physical radius — so every body answers with the same shell.
     * Once the galaxy generator gives a body its radius, this method reads it FROM THE BODY and
     * adds the atmosphere's own depth; nothing else has to change, which is the whole reason this
     * method exists rather than the constant being read at each call site.</p>
     */
    /**
     * How high above {@code body}'s centre its atmosphere ends — the surface a descent triggers at.
     *
     * <p><b>It is the body's own radius plus an atmosphere, and that is a change of kind.</b> This
     * used to ignore its argument and return a flat {@code DESCENT_RADIUS_BLOCKS} = 512, chosen when a
     * body had no size at all. Once bodies got a real radius that constant became 1/50 of an Earth
     * (25 513 blocks) and 1/548 of a Jupiter (280 643): the boundary a descent fires at lay deep INSIDE
     * the world it belongs to, so a pilot flew through the whole bulk before anything happened and
     * {@link #distanceToShell} — the number an approach read-out is built on — described a sphere
     * nowhere near where the world ends.</p>
     *
     * <p><b>The atmosphere fraction is measured, not chosen</b>: the Kármán line stands at 100 km over
     * an Earth radius of 6 371 km, i.e. 1.57 % above the surface, and that ratio is what
     * {@link #ATMOSPHERE_FRACTION} states. A world twice the size gets a shell twice as far out,
     * which is the property the flat constant could not have.</p>
     *
     * <p><b>A body with no radius keeps the flat radius</b>, and that is not a fallback but the right
     * answer: a belt or a station slot is not a sphere, has no surface to stand above, and the constant
     * is then a proximity radius rather than an atmosphere.</p>
     */
    public static long radiusAround(SystemBody body) {
        double radiusEarths = (body == null) ? 0d : body.radiusEarths();
        if (!(radiusEarths > 0d)) {
            return ShipEntryController.DESCENT_RADIUS_BLOCKS;
        }
        double surfaceBlocks = radiusEarths * AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS;
        long shell = Math.round(surfaceBlocks * (1d + ATMOSPHERE_FRACTION));
        // Never below the flat radius: a body small enough that its atmosphere is thinner than the old
        // proximity sphere still has to be approachable at the scale a ship manoeuvres in.
        return Math.max(ShipEntryController.DESCENT_RADIUS_BLOCKS, shell);
    }

    /**
     * How far a world's atmosphere reaches above its surface, as a fraction of its radius — the Kármán
     * line, 100 km over Earth's 6 371 km.
     */
    public static final double ATMOSPHERE_FRACTION = 100d / 6371d;

    /**
     * How far a ship at {@code distanceToCentre} blocks still has to travel before it crosses
     * {@code body}'s atmosphere — clamped at zero, because inside the shell there is nothing left
     * to cover.
     *
     * <p>This is the number a pilot on approach wants, and it is NOT the distance to the body: the
     * two differ by exactly the shell radius, so a readout of the latter tells a pilot flying at a
     * planet to cover a distance he does not have to cover, and reads non-zero at the very instant
     * he crosses.</p>
     */
    public static double distanceToShell(double distanceToCentre, SystemBody body) {
        return Math.max(0d, distanceToCentre - radiusAround(body));
    }

    /**
     * The same arithmetic for a caller that already holds the shell radius rather than the body —
     * the client, which receives the radius per body over the render channel instead of resolving
     * the universe registry it has no access to.
     */
    public static double distanceToShell(double distanceToCentre, long shellRadiusBlocks) {
        return Math.max(0d, distanceToCentre - shellRadiusBlocks);
    }

    /**
     * The half-angle, in radians, that a shell of {@code shellRadiusBlocks} subtends when seen from
     * {@code distanceToCentre} blocks away — how wide the boundary is drawn across the sky.
     *
     * <p>{@code asin(R/d)}, which is what makes the drawn boundary behave like a place rather than
     * a decoration: it OPENS as the ship closes, and reaches a right angle at the crossing, where
     * the boundary genuinely IS all around the viewer. The ratio is clamped at 1 so that being at
     * or inside the shell yields that right angle rather than a NaN — the honest limit of the same
     * curve, not a special case.</p>
     *
     * <p>Kept here beside {@link #distanceToShell} on purpose: the range a pilot reads and the
     * circle he sees are two views of ONE surface, and letting them derive its size independently
     * is how they would come to disagree.</p>
     */
    public static double boundaryHalfAngle(double distanceToCentre, long shellRadiusBlocks) {
        if (shellRadiusBlocks <= 0L || !(distanceToCentre > 0d)) {
            return 0d;
        }
        return Math.asin(Math.min(1d, shellRadiusBlocks / distanceToCentre));
    }
}
