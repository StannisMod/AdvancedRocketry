package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Tier-2 gate — an Advanced Flight Computer in an assembled structure decides
 * whether the launch pad builds an ordinary rocket or a movable Valkyrien Skies
 * ship. The two @Test methods pin the two halves of that gate, each gated on the
 * server's real VS presence so exactly one runs per suite configuration:
 *
 * <ul>
 *   <li><b>no VS</b> (default suite) — the computer is inert; an AFC-bearing build
 *       still assembles a normal {@code EntityRocket}, with the computer captured
 *       inside it. This is the soft-dependency safety contract.</li>
 *   <li><b>with VS</b> (suite run with {@code -PwithVS}) — the fork routes to VS
 *       ship assembly, so NO rocket is spawned.</li>
 * </ul>
 *
 * <p>What the harness deliberately does NOT assert: that the VS ship then fully
 * materialises, is pilotable, or simulates physics. VS assembly is async on a
 * physics thread and largely not headless-verifiable; the observable contract here
 * is the routing decision (rocket vs no-rocket), which is deterministic.</p>
 */
public class AdvancedFlightComputerTierGateTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final String VARIANT = "with-advanced-flight-computer";

    @Test
    public void flightComputerWithoutVsBuildsInertRocket() throws Exception {
        // Contract is the no-VS branch; if the server actually has VS the ship path
        // runs instead, so skip rather than assert the wrong thing.
        Assume.assumeFalse("server has Valkyrien Skies — that is the VS ship path, not the fallback",
                serverHasVs());

        String assemble = assembleFixture(1200, 64, 1200, VARIANT);
        // A rocket WAS built (fallback taken) ...
        assertTrue("expected exactly one rocket from the fallback path: " + assemble,
                assemble.contains("\"rocketCount\":1"));
        int entityId = extractInt(assemble, "\"entityId\":(-?\\d+)");
        assertTrue("assemble did not report a rocket entity id: " + assemble, entityId >= 0);

        String info = String.join("\n", client().execute("artest rocket info " + entityId));
        // ... it has a storage chunk (real EntityRocket) ...
        assertTrue("expected a normal rocket with a storage chunk: " + info,
                info.contains("\"hasStorage\":true"));
        // ... and the Advanced Flight Computer rode along inside it, proving the
        // block was present yet did NOT reroute the build away from the rocket path.
        assertTrue("advanced flight computer should be captured in the built rocket: " + info,
                info.contains("\"advancedFlightComputerPresent\":true"));
    }

    @Test
    public void flightComputerWithVsAssemblesShipNotRocket() throws Exception {
        // Needs Valkyrien Skies on the server classpath (suite run with -PwithVS);
        // skips cleanly otherwise.
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        String assemble = assembleFixture(1600, 64, 1600, VARIANT);
        // The defining contract of the fork WITH VS: the AFC diverts the build to a
        // ship, so no EntityRocket is spawned on the pad.
        assertTrue("with VS, an AFC-bearing build must not spawn a rocket: " + assemble,
                assemble.contains("\"rocketCount\":0"));
    }

    @Test
    public void flightComputerAloneSatisfiesGuidanceAndAssemblesShipWithVs() throws Exception {
        // The build a player actually makes: an Advanced Flight Computer and NO guidance
        // computer. The AFC IS the tier-2 ship's flight computer, so it must satisfy the
        // "computer with instructions" requirement — the scan must not reject it as NOGUIDANCE,
        // and with VS the build routes to a ship (no rocket).
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        String assemble = assembleFixture(2000, 64, 2000, "advanced-flight-computer-only");
        assertTrue("an AFC alone must satisfy the guidance requirement and route to a ship "
                        + "(no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));
    }

    @Test
    public void flightComputerAloneWithoutVsStillRequiresGuidance() throws Exception {
        // The mirror gate: without VS the AFC is inert, the build falls back to a real rocket,
        // and a rocket still needs a guidance computer to navigate. So an AFC-only build must
        // NOT bypass the guidance requirement here — the scan must report NOGUIDANCE.
        Assume.assumeFalse("server has Valkyrien Skies — the AFC satisfies guidance there",
                serverHasVs());

        String coords = placeFixture(2000, 64, 2000, "advanced-flight-computer-only");
        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("without VS, an AFC alone must not satisfy guidance — scan must be NOGUIDANCE: "
                        + assemble,
                assemble.contains("\"status\":\"NOGUIDANCE\""));
    }

    private boolean serverHasVs() throws Exception {
        String out = String.join("\n", client().execute("artest vs available"));
        return out.contains("\"available\":true");
    }

    /**
     * Place the fixture on a pad and run scan+assemble; returns the raw
     * {@code /artest rocket assemble} JSON (carries {@code status}, {@code entityId}
     * and {@code rocketCount}). See {@code RocketAssemblySmokeTest} for the
     * chunk-warmup / pre-clear rationale.
     */
    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String coords = placeFixture(baseX, baseY, baseZ, variant);
        String assemble = String.join("\n", client().execute("artest rocket assemble 0 " + coords));
        assertTrue("assemble (" + variant + ") failed: " + assemble, assemble.contains("\"ok\":true"));
        return assemble;
    }

    /**
     * Place the fixture on a pad (warmup + pre-clear + spawn) WITHOUT assembling; returns the
     * builder position as a {@code "bx by bz"} string. Split from {@link #assembleFixture} so a
     * test that expects the scan to FAIL (e.g. NOGUIDANCE) can drive {@code rocket assemble}
     * directly, since that probe returns an error (not {@code ok:true}) on a non-SUCCESS scan.
     */
    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        String warmup = String.join("\n", client().execute(
                "artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2));
        assertTrue("chunk warmup failed: " + warmup, warmup.contains("\"ok\":true"));

        String fillAir = String.join("\n", client().execute(
                "artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7)
                        + " minecraft:air"));
        assertTrue("pre-clear failed: " + fillAir, fillAir.contains("\"ok\":true"));

        String fixture = String.join("\n", client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant));
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String haystack, String regex) {
        Matcher m = Pattern.compile(regex).matcher(haystack);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }
}
