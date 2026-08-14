package zmaster587.advancedRocketry.universe;

import java.util.Optional;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Where an AUTHORED system is declared to be: a {@link GalaxyKey} plus a cell offset from that
 * galaxy's centre.
 *
 * <p>This is deliberately not a position. It is resolved into an absolute cell name ONCE, at
 * {@code t = 0} — the reference angle — after which the system is named by that cell exactly like
 * every other, and rotates with its galaxy exactly like a procedural one. So nothing downstream gains
 * a second kind of address, and a coordinate a player wrote down keeps meaning what it meant.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class GalacticAnchor {

    private final GalaxyKey galaxy;
    private final GalacticCoord local;

    private GalacticAnchor(GalaxyKey galaxy, GalacticCoord local) {
        this.galaxy = galaxy;
        this.local = local;
    }

    public static GalacticAnchor of(GalaxyKey galaxy, GalacticCoord local) {
        return new GalacticAnchor(galaxy == null ? GalaxyKey.HOME : galaxy,
                local == null ? GalacticCoord.ORIGIN : local);
    }

    /** An anchor in the home galaxy — what an unqualified declaration means. */
    public static GalacticAnchor inHome(GalacticCoord local) {
        return of(GalaxyKey.HOME, local);
    }

    public GalaxyKey galaxy() {
        return galaxy;
    }

    /** The offset from the galaxy's centre, as a cell triple. */
    public GalacticCoord local() {
        return local;
    }

    /**
     * The absolute cell this anchor denotes, given where its galaxy's centre actually is.
     *
     * <p>{@code centre} empty means the running generator has no galaxies at all — an authored-only
     * universe. There the declaration IS the absolute cell, which is both the old behaviour and the
     * only reading that can be right: with nothing to be local to, local and absolute coincide.</p>
     */
    public GalacticCoord resolve(Optional<GalacticCoord> centre) {
        if (!centre.isPresent()) {
            return local;
        }
        GalacticCoord c = centre.get();
        return GalacticCoord.ofSectorLocal(c.sectorX() + local.sectorX(),
                c.sectorY() + local.sectorY(), c.sectorZ() + local.sectorZ(), 0L, 0L, 0L);
    }

    /**
     * How far out this anchor sits from its galaxy's centre, in light years — what the guaranteed
     * minimum radius is checked against.
     */
    public double reachLy() {
        double x = UniverseScale.lightYearsForCells(local.sectorX());
        double y = UniverseScale.lightYearsForCells(local.sectorY());
        double z = UniverseScale.lightYearsForCells(local.sectorZ());
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Override
    public String toString() {
        return "GalacticAnchor[" + galaxy + " + " + local.cellKey() + "]";
    }
}
