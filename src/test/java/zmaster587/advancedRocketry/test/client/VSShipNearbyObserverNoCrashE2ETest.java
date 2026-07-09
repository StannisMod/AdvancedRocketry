package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Assume;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Regression guard for the Valkyrien Skies double-load crash on tier-2 assembly.
 *
 * <p>Before {@code MixinWorldServerShipManager}, a ship assembled with a player standing at the
 * pad crashed VS: the spawn-load and the player-proximity load target the same ship UUID in one
 * physics tick, and VS's {@code loadAndUnloadShips} throws
 * {@code IllegalStateException("Tried loading a ShipData that was already loaded?")}. The sister
 * {@link VSShipClientLoadE2ETest} sidesteps this by keeping its observer FAR during spawn and
 * approaching only after the ship settles — a human building in place cannot.</p>
 *
 * <p>This test does the opposite ON PURPOSE: the observer stands AT the build site through
 * assembly and spawn — the exact double-load window. With the guard (which drops already-loaded
 * ships from the load queue) the ship still assembles, loads and stays queryable; without it the
 * server would fault in that window. Asserting the ship reaches the LOADED state with the
 * observer present through spawn is the no-crash contract. Gated on real VS — run with
 * {@code -PwithVS}.</p>
 */
public class VSShipNearbyObserverNoCrashE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");

    private static final String VARIANT = "with-advanced-flight-computer";
    private static final int BX = 2400, BY = 64, BZ = 2400;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void assemblingWithAnObserverAtThePadDoesNotCrashVs() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the classpath (run with -PwithVS)",
                serverHasVs());

        // Put the observer AT the build site and keep it there through assembly + spawn — the
        // double-load window the sister test avoids. If the guard is absent, VS faults here.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // The ship must appear in the queryable registry (async spawn did not fault) ...
        int all = 0;
        for (int i = 0; i < 40 && all < 1; i++) {
            bot().waitTicks(5);
            all = count("ship-count-all");
        }
        assertTrue("assembly with an observer present must still create a VS ship (all=" + all
                        + ") — a fault in the double-load window would prevent it", all >= 1);

        // ... and it must LOAD (the observer never left, so the proximity load runs in the same
        // window as the spawn load). Reaching LOADED with the observer present through spawn is
        // the no-crash contract: the guard turned the illegal double-load into a no-op.
        int loaded = 0;
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 60 && loaded < 1; i++) {
            bot().waitTicks(5);
            loaded = count("ship-count");
            if (i % 5 == 0) {
                trace.append(i / 5).append('=').append(loaded).append(' ');
            }
        }
        assertTrue("a VS ship assembled under a nearby observer must load without VS faulting "
                        + "(loaded trajectory: [" + trace.toString().trim() + "], all=" + all + ")",
                loaded >= 1);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        int bx = Integer.parseInt(bp.group(1)),
                by = Integer.parseInt(bp.group(2)),
                bz = Integer.parseInt(bp.group(3));
        String assemble = exec("artest rocket assemble 0 " + bx + " " + by + " " + bz);
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));
        return assemble;
    }
}
