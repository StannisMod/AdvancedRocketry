package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Non-player-sender guard contracts for the ARCommandRoot subcommand tree.
 *
 * <p>Several {@code /advancedrocketry} (alias {@code /ar}) subcommands operate
 * on a player entity (held-item mutations, per-player gravity, teleport). When
 * invoked from the harness console (no player entity) they must refuse cleanly
 * rather than crash. The upstream merge replaced the WorldCommand monolith with
 * the ARCommandRoot tree; the player-requiring subcommands now resolve the
 * sender via vanilla {@code CommandBase.getCommandSenderAsPlayer}, which throws
 * the unified "You must specify which player you wish to perform this action on."
 * message on a console sender. This class pins that negative contract.</p>
 *
 * <p>Positive (player-equipped) variants belong in testClient e2e.</p>
 */
public class WorldCommandGuardContractTest extends AbstractSharedServerTest {

    /** Vanilla CommandBase.getCommandSenderAsPlayer message for a non-player sender. */
    private static final String NO_PLAYER =
            "You must specify which player you wish to perform this action on";

    @Test
    public void addTorchRefusesConsoleSender() throws Exception {
        String resp = exec("ar addTorch");
        assertTrue("addTorch must refuse console — got: " + resp, resp.contains(NO_PLAYER));
    }

    @Test
    public void setGravityRefusesConsoleSenderWithUsage() throws Exception {
        // setGravity resolves the sender via getCommandSenderEntity() (null on
        // console) and falls through to wrongUsage(), printing its usage line.
        String resp = exec("ar setGravity 0.5");
        assertTrue("setGravity must refuse console with usage — got: " + resp,
                resp.contains("sets your gravity"));
    }

    @Test
    public void fillDataRefusesConsoleSender() throws Exception {
        String resp = exec("ar fillData distance");
        assertTrue("fillData must refuse console — got: " + resp, resp.contains(NO_PLAYER));
    }

    @Test
    public void gotoRefusesConsoleSender() throws Exception {
        // goto is now a tree; the dimension leaf carries the player guard.
        String resp = exec("ar goto dimension 0");
        assertTrue("goto must refuse console — got: " + resp, resp.contains(NO_PLAYER));
    }

    @Test
    public void fetchRefusesConsoleSender() throws Exception {
        // fetch resolves the destination (the command sender) as a player first,
        // so a console sender is rejected before the target lookup.
        String resp = exec("ar fetch nonExistentPlayerName123");
        assertTrue("fetch must refuse console — got: " + resp, resp.contains(NO_PLAYER));
    }

    @Test
    public void giveStationWithUnknownPlayerNameEmitsNotFoundMessage() throws Exception {
        // give is now under the `station` subtree; an unknown target player is
        // reported by vanilla getPlayer's PlayerNotFoundException.
        String resp = exec("ar station give 7 nonExistentPlayerName123");
        assertTrue("station give must report player not found — got: " + resp,
                resp.toLowerCase().contains("found"));
    }

    /** An unknown subcommand must not echo a help envelope with the old
     *  "Subcommands:" header (the ARCommandRoot tree emits the vanilla
     *  invalid-subcommand key instead). */
    @Test
    public void unknownTopLevelSubcommandDoesNotEmitHelpEnvelope() throws Exception {
        String resp = exec("ar definitelyNotARealSubcommand");
        assertFalse("unknown sub must NOT echo the help header — got: " + resp,
                resp.contains("Subcommands:"));
    }
}
