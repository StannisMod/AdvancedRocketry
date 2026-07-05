package zmaster587.advancedRocketry.test.server;

import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Tier-2 gate — the no-Valkyrien-Skies fallback contract.
 *
 * <p>An Advanced Flight Computer in an assembled structure routes the build to a
 * movable ship ONLY when Valkyrien Skies is installed. On a plain AR install the
 * block is inert: the assembler must still produce an ordinary {@code EntityRocket},
 * with the computer captured into the rocket like any other block. This pins that
 * soft-dependency safety contract — AR without VS behaves exactly as before, and
 * the tier-2 block never breaks or reroutes a normal build.</p>
 *
 * <p>The VS-present path (a ship is assembled <em>instead</em> of a rocket) is only
 * exercisable when VS is on the server classpath; it is not verifiable in the
 * default headless suite (VS is a heavy, threaded coremod the default testServer
 * does not load). A sibling test gated on {@code vs available == true} covers it
 * when the suite is run with VS wired in.</p>
 */
public class AdvancedFlightComputerTierGateTest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern ROCKET_LIST_ID = Pattern.compile("\"id\":(-?\\d+)");

    @Test
    public void flightComputerWithoutVsBuildsInertRocket() throws Exception {
        // The contract under test is the no-VS branch. If the server actually has
        // VS, the tier-2 ship path runs instead — skip rather than assert wrongly.
        Assume.assumeFalse("server has Valkyrien Skies — that is the VS ship path, not the fallback",
                serverHasVs());

        int entityId = buildAndAssemble(1200, 64, 1200, "with-advanced-flight-computer");
        String info = String.join("\n", client().execute("artest rocket info " + entityId));

        // A real rocket was built (fallback taken, StorageChunk cut) ...
        assertTrue("expected a normal rocket with a storage chunk: " + info,
                info.contains("\"hasStorage\":true"));
        // ... and the Advanced Flight Computer rode along inside it, proving the
        // block was present yet did NOT reroute the build away from the rocket path.
        assertTrue("advanced flight computer should be captured in the built rocket: " + info,
                info.contains("\"advancedFlightComputerPresent\":true"));
    }

    private boolean serverHasVs() throws Exception {
        String out = String.join("\n", client().execute("artest vs available"));
        return out.contains("\"available\":true");
    }

    /**
     * Build the given fixture on a pad and run scan+assemble, returning the id of
     * the resulting rocket. Mirrors {@code RocketAssemblySmokeTest#buildAndAssemble}
     * (kept local to avoid coupling the two test classes); see that class for the
     * chunk-warmup / pre-clear rationale.
     */
    private int buildAndAssemble(int baseX, int baseY, int baseZ, String variant) throws Exception {
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
        int bx = Integer.parseInt(bp.group(1)),
                by = Integer.parseInt(bp.group(2)),
                bz = Integer.parseInt(bp.group(3));

        String assemble = String.join("\n", client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble (" + variant + ") failed: " + assemble,
                assemble.contains("\"ok\":true"));

        String rocketList = String.join("\n", client().execute("artest rocket list 0"));
        Matcher rim = ROCKET_LIST_ID.matcher(rocketList);
        int lastId = -1;
        while (rim.find()) {
            lastId = Integer.parseInt(rim.group(1));
        }
        assertTrue("rocket list yielded no ids after assemble: " + rocketList, lastId >= 0);
        return lastId;
    }
}
