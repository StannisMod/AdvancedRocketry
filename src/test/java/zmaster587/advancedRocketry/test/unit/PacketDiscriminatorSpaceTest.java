package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.network.PacketDimInfo;
import zmaster587.advancedRocketry.network.PacketRegistry;
import zmaster587.advancedRocketry.network.PacketSatellite;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The packet discriminator space is declared in one list, and a list that cannot produce a usable
 * wire mapping does not reach the channel.
 *
 * <p>This test fails if production breaks the contract that <b>every packet this mod sends has its
 * own wire discriminator</b>. A packet travels as a number: the channel's codec keeps id->class and
 * class->id side by side and validates neither, so two classes on one id leave decode handing every
 * one of them back as whichever registered last, and the receiver reads a foreign packet's fields as
 * its own. Registration is validated as a whole before any of it happens, so a broken space fails at
 * load rather than on the first packet that happens to cross.</p>
 *
 * <p>The discriminator VALUES are not pinned -- the channel allocates them in registration order and
 * appending a packet legitimately renumbers nothing before it, while pinning the numbers would only
 * forbid appending.</p>
 */
public class PacketDiscriminatorSpaceTest {

    /**
     * The floor below which a clean result is not evidence: an empty list validates perfectly and
     * would read as a pass. This is a floor, not a pin -- appending packets keeps it true.
     */
    private static final int MIN_DECLARED = 22;

    @Test
    public void theDeclaredSpaceIsUsable() {
        // Throws if the shipped list has a duplicate or a hole; the absence of a throw is the check.
        int declared = PacketRegistry.verifyDeclared();
        assertTrue("a validation that examined fewer than this mod's own packets (saw " + declared
                + ", expected >= " + MIN_DECLARED + ") examined an empty or truncated space, and its"
                + " clean result means nothing", declared >= MIN_DECLARED);
    }

    @Test
    public void aDuplicatePacketDoesNotLoad() {
        try {
            PacketRegistry.verify(new Class<?>[]{PacketDimInfo.class, PacketSatellite.class,
                    PacketDimInfo.class});
            fail("a packet class declared twice must not register: the codec keeps the last"
                    + " registration for the shared id and decodes the others as it");
        } catch (IllegalStateException expected) {
            assertTrue("the failure must name the offending packet: " + expected.getMessage(),
                    expected.getMessage().contains(PacketDimInfo.class.getName()));
        }
    }

    @Test
    public void aHoleInTheSpaceDoesNotLoad() {
        try {
            PacketRegistry.verify(new Class<?>[]{PacketDimInfo.class, null, PacketSatellite.class});
            fail("a hole in the declared space must not register: every entry allocates a wire id,"
                    + " so a missing one shifts every packet after it");
        } catch (IllegalStateException expected) {
            // A shifted tail decodes as the wrong class on the far side -- same failure, wider blast radius.
        }
    }
}
