package zmaster587.advancedRocketry.universe;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * One directed telescope observation: which region of the galaxy is being looked at, and the tick its
 * light finishes arriving.
 *
 * <p>A scan is a <b>deadline, never a counter</b>. It stores the tick it began and the tick it
 * completes; progress is computed on read. An observatory whose chunk unloads mid-scan therefore
 * finishes exactly on time when it comes back — nothing has to keep ticking for the sky to keep
 * moving, and no replay is owed on load.</p>
 *
 * <p>The region is bounded twice, and both bounds are the mechanic rather than a safety net. The
 * distance is clamped to a maximum RANGE — you cannot see past your own cluster — and the box itself
 * is clamped to a maximum number of sectors, so no single observation can enumerate an unbounded
 * stretch of a procedurally endless universe.</p>
 *
 * <p>Immutable. The NBT shape is a same-version save contract: a scan outlives the chunk it started
 * in.</p>
 */
public final class RegionScan {

    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";
    private static final String KEY_DISTANCE = "dist";
    private static final String KEY_START = "start";
    private static final String KEY_DEADLINE = "deadline";

    private final GalacticCoord min;
    private final GalacticCoord max;
    private final int distanceSectors;
    private final long startTick;
    private final long deadlineTick;

    private RegionScan(GalacticCoord min, GalacticCoord max, int distanceSectors,
                       long startTick, long deadlineTick) {
        this.min = min;
        this.max = max;
        this.distanceSectors = distanceSectors;
        this.startTick = startTick;
        this.deadlineTick = deadlineTick;
    }

    /**
     * Aim a scan from {@code origin} along a direction, {@code distanceSectors} sectors out.
     *
     * <p>The direction is taken as a sign per axis, so any vector pointing the same way aims the same
     * scan. The distance is clamped into {@code [1, maxRange]} rather than refused: an operator who
     * asks for more than the instrument can reach gets the instrument's reach, which is what the range
     * limit means.</p>
     *
     * @throws IllegalArgumentException if there is no origin, or the direction is the zero vector —
     *                                  a scan with no direction does not name a region.
     */
    public static RegionScan directed(GalacticCoord origin, int dirX, int dirY, int dirZ,
                                      int distanceSectors, long startTick, Tuning tuning) {
        if (origin == null) {
            throw new IllegalArgumentException("a region scan needs an origin to aim from");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("a region scan needs its bounds");
        }
        int dx = Integer.signum(dirX);
        int dy = Integer.signum(dirY);
        int dz = Integer.signum(dirZ);
        if (dx == 0 && dy == 0 && dz == 0) {
            throw new IllegalArgumentException("a scan with no direction does not name a region");
        }

        int distance = Math.max(1, Math.min(distanceSectors, tuning.maxRangeSectors()));
        int half = tuning.effectiveHalfWidthSectors();

        long cx = origin.sectorX() + (long) dx * distance;
        long cy = origin.sectorY() + (long) dy * distance;
        long cz = origin.sectorZ() + (long) dz * distance;

        GalacticCoord lo = GalacticCoord.ofSectorLocal(cx - half, cy - half, cz - half, 0L, 0L, 0L);
        GalacticCoord hi = GalacticCoord.ofSectorLocal(cx + half, cy + half, cz + half, 0L, 0L, 0L);

        long duration = Math.max(1L, tuning.baseTicks() + (long) tuning.ticksPerSector() * distance);
        return new RegionScan(lo, hi, distance, startTick, startTick + duration);
    }

    /** The inclusive low corner of the scanned sector box. */
    public GalacticCoord min() {
        return min;
    }

    /** The inclusive high corner of the scanned sector box. */
    public GalacticCoord max() {
        return max;
    }

    /** How far out the scan was aimed, after the range clamp. */
    public int distanceSectors() {
        return distanceSectors;
    }

    public long startTick() {
        return startTick;
    }

    /** The tick at which the observation is finished. */
    public long deadlineTick() {
        return deadlineTick;
    }

    /** How long the whole observation takes, in ticks. Farther is longer. */
    public long durationTicks() {
        return deadlineTick - startTick;
    }

    /** {@code true} once {@code now} has reached the deadline. Pure read — nothing advances here. */
    public boolean isComplete(long now) {
        return now >= deadlineTick;
    }

    /** How much of the observation is done, in {@code [0,1]}, computed from the clock alone. */
    public float progress(long now) {
        long span = durationTicks();
        if (span <= 0L) {
            return 1f;
        }
        long done = now - startTick;
        if (done <= 0L) {
            return 0f;
        }
        if (done >= span) {
            return 1f;
        }
        return done / (float) span;
    }

    /** How many sectors this scan covers. Bounded at construction; never unbounded. */
    public long sectorCount() {
        long sx = max.sectorX() - min.sectorX() + 1L;
        long sy = max.sectorY() - min.sectorY() + 1L;
        long sz = max.sectorZ() - min.sectorZ() + 1L;
        return sx * sy * sz;
    }

    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagCompound lo = new NBTTagCompound();
        min.writeToNBT(lo);
        nbt.setTag(KEY_MIN, lo);

        NBTTagCompound hi = new NBTTagCompound();
        max.writeToNBT(hi);
        nbt.setTag(KEY_MAX, hi);

        nbt.setInteger(KEY_DISTANCE, distanceSectors);
        nbt.setLong(KEY_START, startTick);
        nbt.setLong(KEY_DEADLINE, deadlineTick);
    }

    /** The scan stored in {@code nbt}, or {@code null} when nothing was stored. */
    public static RegionScan readFromNBT(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(KEY_MIN) || !nbt.hasKey(KEY_MAX)) {
            return null;
        }
        return new RegionScan(
                GalacticCoord.readFromNBT(nbt.getCompoundTag(KEY_MIN)),
                GalacticCoord.readFromNBT(nbt.getCompoundTag(KEY_MAX)),
                nbt.getInteger(KEY_DISTANCE),
                nbt.getLong(KEY_START),
                nbt.getLong(KEY_DEADLINE));
    }

    @Override
    public String toString() {
        return "RegionScan[" + min.cellKey() + " .. " + max.cellKey()
                + ", distance=" + distanceSectors + ", done@" + deadlineTick + "]";
    }

    /**
     * What bounds a scan and what it costs in time. Every number here is balance, not contract: the
     * reach, the width of what one look covers, and how fast the light gets here are all tunable.
     */
    public static final class Tuning {

        private final int maxRangeSectors;
        private final int halfWidthSectors;
        private final int maxSectors;
        private final int baseTicks;
        private final int ticksPerSector;

        public Tuning(int maxRangeSectors, int halfWidthSectors, int maxSectors,
                      int baseTicks, int ticksPerSector) {
            this.maxRangeSectors = Math.max(1, maxRangeSectors);
            this.halfWidthSectors = Math.max(0, halfWidthSectors);
            this.maxSectors = Math.max(1, maxSectors);
            this.baseTicks = Math.max(0, baseTicks);
            this.ticksPerSector = Math.max(0, ticksPerSector);
        }

        /** The tuning the running game is configured with. */
        public static Tuning fromConfig() {
            ARConfiguration config = ARConfiguration.getCurrentConfig();
            return new Tuning(
                    config.telescopeScanRangeSectors,
                    config.telescopeScanHalfWidthSectors,
                    config.telescopeScanMaxSectors,
                    config.telescopeScanBaseTicks,
                    config.telescopeScanTicksPerSector);
        }

        public int maxRangeSectors() {
            return maxRangeSectors;
        }

        public int baseTicks() {
            return baseTicks;
        }

        public int ticksPerSector() {
            return ticksPerSector;
        }

        /**
         * The half-width a scan actually gets: the configured one, narrowed until the box fits inside
         * the sector budget. The budget wins over the width, because the budget is what keeps an
         * endless universe from being enumerated in one look.
         */
        public int effectiveHalfWidthSectors() {
            int half = halfWidthSectors;
            while (half > 0 && volumeOf(half) > maxSectors) {
                half--;
            }
            return half;
        }

        private static long volumeOf(int half) {
            long side = 2L * half + 1L;
            return side * side * side;
        }
    }
}
