package zmaster587.advancedRocketry.universe;

/**
 * The name of one galaxy: its lattice index, or the reserved word {@code home}.
 *
 * <p>Authored content is declared against a key rather than at an absolute coordinate, and the reason
 * is arithmetic: a galaxy fills about three thousandths of a percent of its own lattice cell, so a
 * hand-picked absolute coordinate lands in intergalactic space with probability 99.997 %. Declaring
 * {@code (galaxy, position within it)} is what makes an authored system land in a galaxy on every
 * seed — and what lets it then rotate with that galaxy exactly like a procedural one, which an
 * absolute declaration could never do.</p>
 *
 * <p><b>A declared key FORCES its cell to hold a galaxy.</b> A galaxy is otherwise a hash draw and may
 * simply not be there under another seed, while authored content must exist under every seed. The
 * key's parameters — type, radius, orientation, arms — stay hash-drawn, so only EXISTENCE is
 * guaranteed and every world's galaxies are still its own.</p>
 *
 * <p>Immutable value type.</p>
 */
public final class GalaxyKey {

    /** The word a pack writes for the galaxy authored content lives in by default. */
    public static final String HOME_NAME = "home";

    /** The reserved home galaxy: lattice cell (0,0,0), centred on the universe origin. */
    public static final GalaxyKey HOME = new GalaxyKey(0L, 0L, 0L);

    private final long gx;
    private final long gy;
    private final long gz;

    private GalaxyKey(long gx, long gy, long gz) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
    }

    public static GalaxyKey of(long gx, long gy, long gz) {
        return new GalaxyKey(gx, gy, gz);
    }

    /**
     * Parse {@code "home"} or {@code "gx,gy,gz"}. Returns {@code null} for anything else — a malformed
     * key is a thing the caller must report, not a thing this type may guess at.
     */
    public static GalaxyKey parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || HOME_NAME.equalsIgnoreCase(trimmed)) {
            return HOME;
        }
        String[] parts = trimmed.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new GalaxyKey(Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()),
                    Long.parseLong(parts[2].trim()));
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    public long gx() {
        return gx;
    }

    public long gy() {
        return gy;
    }

    public long gz() {
        return gz;
    }

    /** Whether this is the home galaxy — the one centred on the origin. */
    public boolean isHome() {
        return gx == 0L && gy == 0L && gz == 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GalaxyKey)) {
            return false;
        }
        GalaxyKey other = (GalaxyKey) o;
        return gx == other.gx && gy == other.gy && gz == other.gz;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(gx);
        result = 31 * result + Long.hashCode(gy);
        return 31 * result + Long.hashCode(gz);
    }

    @Override
    public String toString() {
        return isHome() ? HOME_NAME : (gx + "," + gy + "," + gz);
    }
}
