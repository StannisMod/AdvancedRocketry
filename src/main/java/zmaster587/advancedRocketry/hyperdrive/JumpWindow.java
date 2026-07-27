package zmaster587.advancedRocketry.hyperdrive;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.BlockPos;

/**
 * The hyperspace window a ship's drive can open, and whether the hull fits inside it.
 *
 * <p>A window is not a point effect: the ship rides inside it for the whole flight, so it has to
 * WRAP the hull. The generator holds up a small envelope on its own — enough for a starter craft —
 * and every hull emitter adds an envelope of its own around itself. The window is their union, which
 * is why emitters are an extension for a big hull rather than a prerequisite for any hull.</p>
 *
 * <p>A hull that sticks out of the window is not refused. The pilot is told, and if he goes anyway
 * the part left outside is what the jump costs him.</p>
 */
public final class JumpWindow {

    /** An axis-aligned envelope, in the same block frame as the hull it is compared against. */
    public static final class Envelope {
        final int minX, minY, minZ, maxX, maxY, maxZ;

        public Envelope(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        }

        /** The cube of half-extent {@code radius} centred on {@code centre}. */
        public static Envelope around(BlockPos centre, int radius) {
            int r = Math.max(0, radius);
            return new Envelope(centre.getX() - r, centre.getY() - r, centre.getZ() - r,
                    centre.getX() + r, centre.getY() + r, centre.getZ() + r);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** Block count, i.e. the envelope's volume. */
        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        @Override
        public String toString() {
            return "[" + minX + "," + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ + "]";
        }
    }

    /**
     * How much of a hull the window failed to enclose. Zero uncovered blocks is full coverage; the
     * count is what the warning quotes to the pilot, so he can tell "a corner sticks out" from
     * "half the ship is outside".
     */
    public static final class Coverage {
        private final long hullVolume;
        private final long uncovered;

        Coverage(long hullVolume, long uncovered) {
            this.hullVolume = hullVolume;
            this.uncovered = uncovered;
        }

        public boolean complete() {
            return uncovered == 0L;
        }

        public long uncoveredBlocks() {
            return uncovered;
        }

        public long hullVolume() {
            return hullVolume;
        }

        /** 0.0 when nothing is enclosed, 1.0 when the whole hull is. */
        public double fraction() {
            return hullVolume <= 0L ? 1.0D : (hullVolume - uncovered) / (double) hullVolume;
        }

        @Override
        public String toString() {
            return "Coverage[" + (hullVolume - uncovered) + "/" + hullVolume + "]";
        }
    }

    private final List<Envelope> envelopes = new ArrayList<>();

    /**
     * The window of a generator at {@code generatorPos} with the given hull emitters. Positions are
     * in whatever frame the hull is measured in — they only ever get compared with each other.
     */
    public static JumpWindow of(BlockPos generatorPos, List<BlockPos> emitters) {
        JumpWindow window = new JumpWindow();
        if (generatorPos != null) {
            window.envelopes.add(
                    Envelope.around(generatorPos, DriveTuning.GENERATOR_BASELINE_WINDOW_RADIUS));
        }
        if (emitters != null) {
            for (BlockPos emitter : emitters) {
                if (emitter != null) {
                    window.envelopes.add(Envelope.around(emitter, DriveTuning.EMITTER_WINDOW_RADIUS));
                }
            }
        }
        return window;
    }

    public List<Envelope> envelopes() {
        return envelopes;
    }

    public boolean contains(int x, int y, int z) {
        for (int i = 0; i < envelopes.size(); i++) {
            if (envelopes.get(i).contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How much of {@code hull} this window encloses. Measured block by block because the window is a
     * union of boxes and a union of boxes is not a box: an enclosing-box test would call a hull
     * covered that pokes straight through the gap between two emitters.
     */
    public Coverage cover(Envelope hull) {
        if (hull == null) {
            return new Coverage(0L, 0L);
        }
        long uncovered = 0L;
        for (int y = hull.minY; y <= hull.maxY; y++) {
            for (int z = hull.minZ; z <= hull.maxZ; z++) {
                for (int x = hull.minX; x <= hull.maxX; x++) {
                    if (!contains(x, y, z)) {
                        uncovered++;
                    }
                }
            }
        }
        return new Coverage(hull.volume(), uncovered);
    }
}
