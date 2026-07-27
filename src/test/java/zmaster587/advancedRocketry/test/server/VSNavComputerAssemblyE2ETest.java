package zmaster587.advancedRocketry.test.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * The assembler links a built navigation computer to the ship it is part of.
 *
 * <p>The link is what makes the computer THIS ship's — the jump gate finds a ship's navigation through
 * its flight computer, so a computer welded into the hull but never linked would leave the finished
 * ship unable to jump for no reason the player can see.</p>
 *
 * <p>Needs the physics mod: the link is set on the tier-2 assembly path, which only runs when a real
 * ship is being made.</p>
 */
public class VSNavComputerAssemblyE2ETest extends AbstractSharedServerTest {

    private static final int BASE_X = 7200, BASE_Y = 80, BASE_Z = 7200;
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)\\]");

    @Test
    public void aBuiltNavigationComputerIsLinkedToItsShipByTheAssembler() throws Exception {
        Assume.assumeTrue("needs Valkyrien Skies on the server classpath (run with -PwithVS)",
                serverHasVs());

        exec("artest vs permaload true");
        String fixture = exec("artest fixture rocket 0 " + BASE_X + " " + BASE_Y + " " + BASE_Z
                + " with-nav-computer");
        assertTrue("the with-nav-computer fixture must build: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());

        // The fixture builds its craft at (baseX+3, baseY+1, baseZ+3); the flight computer sits one
        // west and three up from that origin, and the navigation computer one further along Z.
        int navX = BASE_X + 3 - 1, navY = BASE_Y + 1 + 3, navZ = BASE_Z + 3 + 1;
        String before = exec("artest nav status 0 " + navX + " " + navY + " " + navZ);
        assertTrue("ARRANGEMENT: the fixture must actually contain a navigation computer: " + before,
                before.contains("\"ok\":true"));
        assertTrue("ARRANGEMENT (control): a freshly built computer is NOT yet linked - without this"
                        + " the test could not tell assembly apart from doing nothing: " + before,
                before.contains("\"linked\":false"));

        String asm = exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));

        // The assembly lifts the craft one block before handing it to the physics mod, so the
        // computer's world position moves up with it.
        String after = exec("artest nav status 0 " + navX + " " + (navY + 1) + " " + navZ);
        assertTrue("the assembler must link the navigation computer it found in the build: " + after
                        + " (pre-assembly state was " + before + ")",
                after.contains("\"linked\":true"));
    }

    private boolean serverHasVs() throws Exception {
        return exec("artest vs available").contains("\"available\":true");
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
