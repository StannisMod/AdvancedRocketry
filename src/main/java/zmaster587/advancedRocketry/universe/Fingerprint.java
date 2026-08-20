package zmaster587.advancedRocketry.universe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * How this layer turns a set of numbers into a short identity a save can carry.
 *
 * <p>One place, because two digests of the same kind computed two ways are two things to keep in step,
 * and a stamp that disagrees with itself between builds is worse than no stamp.
 *
 * <p><b>Stable across JVMs and versions by construction.</b> No {@link Object#hashCode()} anywhere
 * (identity hashes and even {@code String.hashCode} are not promised across implementations), doubles
 * rendered through {@link Double#doubleToLongBits} rather than formatted (no locale, no rounding, and
 * the last bit is visible), and every caller renders its lists in a declared order — order is part of
 * an identity whenever a weighted table is walked by it.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    /** A double as its exact bits — the only rendering that neither rounds nor asks about a locale. */
    static String bits(double v) {
        return Long.toHexString(Double.doubleToLongBits(v));
    }

    /** 16 lowercase hex of SHA-256 — short enough to read out of a log, long enough not to collide. */
    static String hex16(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", out[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform. If it is genuinely absent the stamp cannot be
            // computed, and a silent fallback would be a value that compares equal against everything.
            throw new IllegalStateException("SHA-256 unavailable, cannot fingerprint the universe", e);
        }
    }
}
