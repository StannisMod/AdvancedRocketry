package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MED batch pack 4 — C072 reproduction + regression guard.
 *
 * <p>Contract under test: {@code /advancedrocketry planet generate <planet> moon …}
 * must fail with a clean {@code CommandException} — not an unguarded
 * {@link NullPointerException} — when the parent planet's star id resolves to no
 * star. The non-moon path guards {@code getStar(id) == null}
 * ({@code PlanetGenerateCommand} else-if), but the moon path re-derives the star
 * id from the parent planet and skips that guard, then feeds it to
 * {@code generateRandom} (which dereferences {@code getStar}) — an op-only command
 * crash.</p>
 *
 * <p>The probe drives the REAL command's {@code execute} against a planet whose
 * star has been temporarily orphaned, and reports the thrown type. Pre-fix it is
 * a {@code NullPointerException}; post-fix a star-existence guard on the moon
 * branch throws {@code CommandException} before any generation, and no dimension
 * is registered in either case.</p>
 */
public class PlanetGenerateMoonNullStarTest extends AbstractSharedServerTest {

    private static final Pattern AR_DIMS = Pattern.compile("\"arDimensions\":\\[([^\\]]*)]");
    private static final Pattern THROWN = Pattern.compile("\"thrown\":\"([^\"]*)\"");
    private static final Pattern DIMS_BEFORE = Pattern.compile("\"dimsBefore\":(-?\\d+)");
    private static final Pattern DIMS_AFTER = Pattern.compile("\"dimsAfter\":(-?\\d+)");

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    /** Pick a registered AR planet dimension (positive id, non-overworld) to
     *  serve as the parent planet for the moon-generate. */
    private int anyArPlanetDim() throws Exception {
        String list = ok(client().execute("artest dim list"));
        Matcher m = AR_DIMS.matcher(list);
        assertTrue("dim list missing arDimensions: " + list, m.find());
        String[] ids = m.group(1).split(",");
        for (String id : ids) {
            String s = id.trim();
            if (s.isEmpty()) continue;
            int d = Integer.parseInt(s);
            if (d > 0) return d;
        }
        throw new IllegalStateException("no positive AR planet dim in: " + list);
    }

    @Test
    public void moonGenerateWithOrphanStarThrowsCleanlyNotNpe() throws Exception {
        int planetDim = anyArPlanetDim();

        String resp = ok(client().execute("artest planet moon-generate-catch " + planetDim));
        assertTrue("moon-generate-catch failed: " + resp, resp.contains("\"ok\":true"));

        Matcher tm = THROWN.matcher(resp);
        assertTrue("thrown field missing: " + resp, tm.find());
        String thrown = tm.group(1);
        assertFalse("generating a moon for a planet whose star resolves to no star "
                        + "must not NPE (C072); got " + thrown + ": " + resp,
                "NullPointerException".equals(thrown));
        assertTrue("the moon-generate must fail with a clean CommandException, "
                        + "got " + thrown + ": " + resp,
                "CommandException".equals(thrown));

        // The guard must fire before any generation — no dimension registered.
        Matcher bm = DIMS_BEFORE.matcher(resp);
        Matcher am = DIMS_AFTER.matcher(resp);
        assertTrue("dimsBefore missing: " + resp, bm.find());
        assertTrue("dimsAfter missing: " + resp, am.find());
        assertTrue("no dimension may be registered when the guard rejects the "
                        + "command: " + resp,
                Integer.parseInt(bm.group(1)) == Integer.parseInt(am.group(1)));
    }
}
