package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * End-to-end contracts of the navigation computer in a real world: what a pilot can do with the block
 * he built, and what the jump gate tells him when he cannot jump.
 *
 * <p>Everything here drives production code through {@code /artest nav} — the probe only places the
 * block and stocks the crystals; the copy, the sync and the gate verdict are production's own.</p>
 */
public class NavigationComputerE2ETest extends AbstractSharedServerTest {

    /** Well away from the other fixtures' build sites, so nothing else in the shared world overlaps. */
    private static final String A = "0 2400 80 2400";
    private static final String B = "0 2410 80 2400";
    /** The flight-computer position the gate is asked about; nothing is built there. */
    private static final String AFC = "2400 82 2400";

    @Test
    public void copyingACrystalAddsToTheShipWithoutTakingFromTheSource() throws Exception {
        placeComputer(A);
        stock(A, 0, 3, 100);
        stock(A, 1, 2, 200);

        String copied = exec("artest nav copy " + A);

        assertTrue("the copy must report the three new addresses: " + copied,
                copied.contains("\"changed\":3"));
        assertTrue("the ship's crystal must hold everything it had plus everything copied: " + copied,
                copied.contains("\"ship\":5"));
        assertTrue("a copy must never take an address off the source crystal: " + copied,
                copied.contains("\"source\":3"));
    }

    @Test
    public void erasingTheSourceLeavesTheShipCrystalAlone() throws Exception {
        placeComputer(A);
        stock(A, 0, 3, 300);
        stock(A, 1, 2, 400);
        exec("artest nav copy " + A);

        String erased = exec("artest nav erase " + A);
        String status = exec("artest nav status " + A);

        assertTrue("the source must be blank after an erase: " + erased,
                erased.contains("\"source\":0"));
        assertTrue("erasing the source must not touch what the ship knows: " + status,
                status.contains("\"ship\":5"));
    }

    @Test
    public void theJumpGateRefusesAShipWithNoNavigationComputer() throws Exception {
        String verdict = exec("artest nav gate 0 500 82 500");

        assertTrue("a ship with no navigation computer cannot jump: " + verdict,
                verdict.contains("\"allowed\":false"));
        assertTrue("and must be told exactly that: " + verdict,
                verdict.contains("msg.jumpgate.nonavcomputer"));
    }

    @Test
    public void aLinkedComputerWithoutATargetStillRefuses() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);
        exec("artest nav cleartarget " + A);

        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("having a computer is not having a destination: " + verdict,
                verdict.contains("\"allowed\":false"));
        assertTrue(verdict.contains("msg.jumpgate.notarget"));
        assertTrue("the computer itself must have been found: " + verdict,
                verdict.contains("\"navComputer\":true"));
    }

    @Test
    public void aLinkedComputerWithATargetClearsTheGate() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);
        exec("artest nav target " + A + " 7 0 0");

        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("computer aboard, position known, target set - nothing refuses this: " + verdict,
                verdict.contains("\"allowed\":true"));
        assertTrue("and nothing merely advises either: " + verdict,
                verdict.contains("\"confirm\":false"));
    }

    @Test
    public void aHandTypedCoordinateIsAcceptedAsATarget() throws Exception {
        placeComputer(A);
        exec("artest nav link " + A + " " + AFC);

        // Nothing has ever surveyed sector 4242: aiming there is legal, and reckless, on purpose.
        String aimed = exec("artest nav target " + A + " 4242 0 0");
        String verdict = exec("artest nav gate 0 " + AFC);

        assertTrue("an unsurveyed coordinate is still a coordinate: " + aimed,
                aimed.contains("\"target\":\"4242_0_0\""));
        assertTrue("and the gate lets the pilot take the risk: " + verdict,
                verdict.contains("\"allowed\":true"));
    }

    @Test
    public void syncingOnAChannelLeavesBothComputersHoldingTheUnion() throws Exception {
        placeComputer(A);
        placeComputer(B);
        stock(A, 1, 3, 600);
        stock(B, 1, 2, 700);

        String synced = exec("artest nav sync " + A + " 42");
        String peerBefore = exec("artest nav status " + B);
        exec("artest nav sync " + B + " 42");
        String peer = exec("artest nav status " + B);
        String self = exec("artest nav status " + A);

        assertTrue("the sync must report moving addresses: " + synced,
                synced.contains("\"changed\":"));
        assertTrue("both computers must end up holding all five addresses; A=" + self
                        + " B=" + peer + " (B before its own sync: " + peerBefore + ")",
                self.contains("\"ship\":5") && peer.contains("\"ship\":5"));
    }

    @Test
    public void aComputerOnNoChannelSyncsWithNobody() throws Exception {
        placeComputer(A);
        placeComputer(B);
        stock(A, 1, 3, 800);
        stock(B, 1, 2, 900);

        String synced = exec("artest nav sync " + A + " 0");
        String self = exec("artest nav status " + A);

        assertTrue("channel 0 must move nothing: " + synced, synced.contains("\"changed\":0"));
        assertTrue("a computer nobody put on a channel must not pool its knowledge: " + self,
                self.contains("\"ship\":3"));
    }

    private void placeComputer(String at) throws Exception {
        String placed = exec("artest nav place " + at);
        assertTrue("the navigation computer must be placeable: " + placed, placed.contains("\"ok\":true"));
        exec("artest nav sync " + at + " 0"); // a fresh block starts on no channel
        exec("artest nav cleartarget " + at);
    }

    private void stock(String at, int slot, int addresses, int firstSector) throws Exception {
        String stocked = exec("artest nav crystal " + at + " " + slot + " " + addresses
                + " " + firstSector + " 1");
        assertTrue("the probe must stock the crystal: " + stocked, stocked.contains("\"ok\":true"));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
