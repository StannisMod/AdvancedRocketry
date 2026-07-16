package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * A deterministic, addon-default {@link IGalaxyGenerator} producing a CLUSTERED procedural galaxy
 * (universe-model.md &sect;3): dense galaxies separated by inter-galaxy void, so a bounded scan/reach range is
 * a natural horizon.
 *
 * <p>Every answer is a pure function of {@code (seed, cell)} — no state, no RNG — so a scan and a later jump
 * agree and a re-materialised cell regenerates identically. The scheme, all O(1) per query:</p>
 * <ol>
 *   <li>partition space into {@link GalaxyGenConfig#minSpacing}-cube <i>super-cells</i> — at most one system
 *       each (the minimum-spacing guarantee);</li>
 *   <li>a coarse <i>blob</i> field grouped {@link GalaxyGenConfig#clusterScale} super-cells wide masks
 *       galaxy from void ({@link GalaxyGenConfig#voidFraction});</li>
 *   <li>inside a galaxy, a super-cell hosts a system with probability {@link GalaxyGenConfig#density}, seated
 *       at a hash-chosen cell within the super-cell.</li>
 * </ol>
 *
 * <p>A procedural system is a bare star (type/size sampled by weight from the seed) with a <b>synthetic
 * negative id</b> — it is never in the catalogue and never a dimension, so the id cannot collide with a real
 * star-id ({@code 0..N}) or a dim id. Planet CONTENT is a separate concern; this generator places stars only.</p>
 */
public final class ClusteredGalaxyGenerator implements IGalaxyGenerator {

    // Distinct salts so the independent hash draws (blob mask, occupancy, per-axis offset, star type/size/id)
    // never correlate with each other.
    private static final long SALT_BLOB = 0x1L;
    private static final long SALT_OCC = 0x2L;
    private static final long SALT_OX = 0x3L;
    private static final long SALT_OY = 0x4L;
    private static final long SALT_OZ = 0x5L;
    private static final long SALT_TYPE = 0x6L;
    private static final long SALT_SIZE = 0x7L;
    private static final long SALT_ID = 0x8L;

    private static final long SYNTHETIC_ID_RANGE = 2_000_000_000L; // ids in [-2_000_000_000, -1]

    // Procedural in-system content (bodiesFor). All tunable. Per amendment A#1a each body gets its OWN cell
    // at a sector offset from the anchor (snapped to that cell's centre); the neighbourhood radius is bounded
    // by the super-cell partition (minSpacing/2 - margin) so two systems' neighbourhoods never interleave.
    private static final long SALT_BODYCOUNT = 0x11L;
    private static final long SALT_BODYANG = 0x12L;
    private static final long SALT_BODYRAD = 0x13L;
    private static final long SALT_BODYY = 0x14L;
    private static final long SALT_BELT = 0x15L;
    private static final int MAX_PROC_PLANETS = 6;
    /** Neighbourhood margin (cells) kept clear of the super-cell boundary. */
    private static final int NEIGHBOURHOOD_MARGIN_CELLS = 2;
    /** Thin-disk half-thickness as a fraction of the orbit radius (bodies keep honest 3D Y — A#1a e1). */
    private static final double PROC_DISK_FRACTION = 0.1d;

    private final GalaxyGenConfig config;
    private final long totalStarWeight;

    public ClusteredGalaxyGenerator(GalaxyGenConfig config) {
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        long w = 0L; // accumulate in long so a few near-Integer.MAX weights cannot overflow the sum
        for (GalaxyGenConfig.StarType t : this.config.starTypes) {
            w += t.weight;
        }
        this.totalStarWeight = Math.max(1L, w);
    }

    public GalaxyGenConfig config() {
        return config;
    }

    @Override
    public Optional<StarSystem> systemAt(long seed, GalacticCoord coord) {
        long sx = coord.sectorX();
        long sy = coord.sectorY();
        long sz = coord.sectorZ();
        long s = config.minSpacing;
        Optional<Generated> g = systemForSuperCell(seed,
                Math.floorDiv(sx, s), Math.floorDiv(sy, s), Math.floorDiv(sz, s));
        if (g.isPresent() && g.get().cell.sameCell(coord)) {
            return Optional.of(g.get().system);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cost is O(super-cell volume of the box). Callers MUST pass a bounded region — a telescope scan is
     * range-limited by config — not a galactic-scale box.</p>
     */
    @Override
    public Map<GalacticCoord, StarSystem> systemsInRegion(long seed, GalacticCoord min, GalacticCoord max) {
        long s = config.minSpacing;
        long loX = Math.min(min.sectorX(), max.sectorX());
        long hiX = Math.max(min.sectorX(), max.sectorX());
        long loY = Math.min(min.sectorY(), max.sectorY());
        long hiY = Math.max(min.sectorY(), max.sectorY());
        long loZ = Math.min(min.sectorZ(), max.sectorZ());
        long hiZ = Math.max(min.sectorZ(), max.sectorZ());

        Map<GalacticCoord, StarSystem> out = new HashMap<>();
        for (long supX = Math.floorDiv(loX, s); supX <= Math.floorDiv(hiX, s); supX++) {
            for (long supY = Math.floorDiv(loY, s); supY <= Math.floorDiv(hiY, s); supY++) {
                for (long supZ = Math.floorDiv(loZ, s); supZ <= Math.floorDiv(hiZ, s); supZ++) {
                    Optional<Generated> g = systemForSuperCell(seed, supX, supY, supZ);
                    if (!g.isPresent()) {
                        continue;
                    }
                    GalacticCoord c = g.get().cell;
                    if (c.sectorX() >= loX && c.sectorX() <= hiX
                            && c.sectorY() >= loY && c.sectorY() <= hiY
                            && c.sectorZ() >= loZ && c.sectorZ() <= hiZ) {
                        out.put(c, g.get().system);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public List<SystemBody> bodiesFor(long seed, GalacticCoord systemCoord) {
        // Accept any member cell: resolve the owning anchor first (A#1a).
        Optional<GalacticCoord> anchorOpt = anchorAt(seed, systemCoord);
        if (!anchorOpt.isPresent()) {
            return Collections.emptyList();
        }
        GalacticCoord cell = anchorOpt.get();
        Optional<StarSystem> sys = systemAt(seed, cell);
        if (!sys.isPresent()) {
            return Collections.emptyList();
        }
        int starId = sys.get().starId();
        List<SystemBody> bodies = new ArrayList<>();
        // The star sits at the anchor cell's centre.
        bodies.add(new SystemBody(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, starId));

        // Bodies orbit at cell-scale radii: min 1 cell out (never the anchor cell), max = the bounded
        // neighbourhood radius. The anchor sits in the middle band of its super-cell (>= 3s/8 from every
        // face), so a radius <= 3s/8 - margin keeps every body inside the anchor's super-cell — member-cell
        // attribution by floorDiv stays exact. (The per-body box clamp below covers the tiny-spacing floor.)
        long s = config.minSpacing;
        long maxRadiusCells = Math.max(1L, 3L * s / 8L - NEIGHBOURHOOD_MARGIN_CELLS);
        double maxRadiusBlocks = (double) maxRadiusCells * GalacticCoord.CELL;
        double minRadiusBlocks = GalacticCoord.CELL;

        int count = 1 + (int) Math.floorMod(
                hash(seed, cell.sectorX(), cell.sectorY(), cell.sectorZ(), SALT_BODYCOUNT), MAX_PROC_PLANETS);
        for (int i = 0; i < count; i++) {
            double angle = norm(hashBody(seed, cell, i, SALT_BODYANG)) * 2d * Math.PI;
            double radius = minRadiusBlocks
                    + norm(hashBody(seed, cell, i, SALT_BODYRAD)) * Math.max(0d, maxRadiusBlocks - minRadiusBlocks);
            long lx = (long) (radius * Math.cos(angle));
            long lz = (long) (radius * Math.sin(angle));
            long ly = (long) ((norm(hashBody(seed, cell, i, SALT_BODYY)) - 0.5d) * radius * PROC_DISK_FRACTION);
            // The body's address is its OWN cell's centre (zone content sits near the cell centre — A#1a),
            // box-clamped into the anchor's super-cell so member attribution stays exact at ANY minSpacing
            // (at tiny spacings the floor above can otherwise push a body across the super-cell face).
            GalacticCoord addr = clampIntoSuperCell(cell.plusLocal(lx, ly, lz).cellCentre(), cell, s);
            // Roughly a third of systems' outermost body is an asteroid belt rather than a planet.
            SystemBodyKind kind = (i == count - 1 && norm(hashBody(seed, cell, i, SALT_BELT)) < 0.3d)
                    ? SystemBodyKind.ASTEROID_BELT
                    : SystemBodyKind.PLANET;
            // Procedural bodies have no realized dimension yet — a descent (Layer 2) realizes one.
            bodies.add(new SystemBody(addr, kind, Constants.INVALID_PLANET, starId));
        }
        return bodies;
    }

    @Override
    public Optional<GalacticCoord> anchorAt(long seed, GalacticCoord cell) {
        long s = config.minSpacing;
        Optional<Generated> g = systemForSuperCell(seed,
                Math.floorDiv(cell.sectorX(), s), Math.floorDiv(cell.sectorY(), s),
                Math.floorDiv(cell.sectorZ(), s));
        return g.isPresent() ? Optional.of(g.get().cell) : Optional.<GalacticCoord>empty();
    }

    @Override
    public int minSpacingCells() {
        return config.minSpacing;
    }

    /** Per-axis clamp of a body's cell into its anchor's super-cell box (margin when the box allows it). */
    private static GalacticCoord clampIntoSuperCell(GalacticCoord bodyCell, GalacticCoord anchor, long s) {
        long margin = (s > 2L * NEIGHBOURHOOD_MARGIN_CELLS) ? NEIGHBOURHOOD_MARGIN_CELLS : 0L;
        long cx = clampAxis(bodyCell.sectorX(), anchor.sectorX(), s, margin);
        long cy = clampAxis(bodyCell.sectorY(), anchor.sectorY(), s, margin);
        long cz = clampAxis(bodyCell.sectorZ(), anchor.sectorZ(), s, margin);
        if (cx == bodyCell.sectorX() && cy == bodyCell.sectorY() && cz == bodyCell.sectorZ()) {
            return bodyCell;
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    private static long clampAxis(long sector, long anchorSector, long s, long margin) {
        long sup = Math.floorDiv(anchorSector, s);
        long lo = sup * s + margin;
        long hi = sup * s + s - 1L - margin;
        if (sector < lo) {
            return lo;
        }
        return sector > hi ? hi : sector;
    }

    private static long hashBody(long seed, GalacticCoord cell, int i, long field) {
        // XOR the body index in with a DIFFERENT multiplier than hash() uses for the field salt, so the two
        // don't merge into (i + field)*G and correlate neighbouring bodies' draws.
        return hash(seed ^ (i * 0xD1B54A32D192ED03L), cell.sectorX(), cell.sectorY(), cell.sectorZ(), field);
    }

    /** The single system a super-cell hosts (its cell coordinate + fabricated system), or empty. */
    private Optional<Generated> systemForSuperCell(long seed, long supX, long supY, long supZ) {
        long cs = config.clusterScale;
        // Void mask: a super-cell whose blob is below the void fraction hosts nothing.
        double blob = norm(hash(seed, Math.floorDiv(supX, cs), Math.floorDiv(supY, cs), Math.floorDiv(supZ, cs),
                SALT_BLOB));
        if (blob < config.voidFraction) {
            return Optional.empty();
        }
        if (norm(hash(seed, supX, supY, supZ, SALT_OCC)) >= config.density) {
            return Optional.empty();
        }
        long s = config.minSpacing;
        // Seat the anchor in the middle band of the super-cell ([3s/8, 5s/8)): every face stays >= 3s/8
        // cells away, so a body neighbourhood of radius <= 3s/8 - margin can never cross into the
        // neighbouring super-cell (A#1a attribution guarantee).
        long band = Math.max(1L, s / 4L);
        long base = 3L * s / 8L;
        long ox = base + Math.floorMod(hash(seed, supX, supY, supZ, SALT_OX), band);
        long oy = base + Math.floorMod(hash(seed, supX, supY, supZ, SALT_OY), band);
        long oz = base + Math.floorMod(hash(seed, supX, supY, supZ, SALT_OZ), band);
        GalacticCoord cell = GalacticCoord.ofSectorLocal(supX * s + ox, supY * s + oy, supZ * s + oz,
                0L, 0L, 0L);
        return Optional.of(new Generated(cell, fabricate(seed, supX, supY, supZ)));
    }

    private StarSystem fabricate(long seed, long supX, long supY, long supZ) {
        GalaxyGenConfig.StarType type = pickType(hash(seed, supX, supY, supZ, SALT_TYPE));
        double sizeFrac = norm(hash(seed, supX, supY, supZ, SALT_SIZE));

        StellarBody star = new StellarBody();
        star.setTemperature(type.temperature);
        star.setSize((float) (type.minSize + sizeFrac * (type.maxSize - type.minSize)));
        star.setId(syntheticId(seed, supX, supY, supZ));
        star.setName("PGS-" + supX + "." + supY + "." + supZ); // procedurally-generated system
        return new StarSystem(star);
    }

    private GalaxyGenConfig.StarType pickType(long h) {
        long r = Math.floorMod(h, totalStarWeight); // long arithmetic — overflow-safe over the weight sum
        GalaxyGenConfig.StarType last = null;
        for (GalaxyGenConfig.StarType t : config.starTypes) {
            last = t;
            if (r < t.weight) {
                return t;
            }
            r -= t.weight;
        }
        return last; // config.starTypes is never empty
    }

    private static int syntheticId(long seed, long supX, long supY, long supZ) {
        return -(1 + (int) Math.floorMod(hash(seed, supX, supY, supZ, SALT_ID), SYNTHETIC_ID_RANGE));
    }

    /** A splitmix-style mix of the seed, an integer coordinate triple, and a salt. Uniform over 64 bits. */
    private static long hash(long seed, long a, long b, long c, long salt) {
        long h = seed + salt * 0x9E3779B97F4A7C15L;
        h ^= a;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h ^= b;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        h ^= c;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /** Map a 64-bit hash to a double in {@code [0, 1)}. */
    private static double norm(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static final class Generated {
        final GalacticCoord cell;
        final StarSystem system;

        Generated(GalacticCoord cell, StarSystem system) {
            this.cell = cell;
            this.system = system;
        }
    }
}
