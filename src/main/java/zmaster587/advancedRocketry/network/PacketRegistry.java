package zmaster587.advancedRocketry.network;

import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketItemModifcation;

import java.util.HashSet;
import java.util.Set;

/**
 * The one owner of this mod's packet discriminators.
 *
 * <p>A packet travels as a number, not as a class name: the channel's codec keeps id->class and
 * class->id side by side and validates neither, so registering two classes on one id leaves encode
 * writing the same number for both while decode hands every one of them back as whichever was
 * registered last. The receiver then reads a foreign packet's fields as its own -- the same silent
 * shape as a duplicate entity spawn id, on a different space.</p>
 *
 * <p>The discriminator is allocated by the channel in registration order, so the ORDER of
 * {@link #PACKETS} is the wire format. Declaring the whole space as one list keeps that order in a
 * single reviewable place, makes a duplicate impossible to miss, and keeps registration
 * unconditional -- a registration hidden behind a side or config check would allocate different
 * numbers on the two sides and mis-decode everything after it.</p>
 *
 * <p>Adding a packet: append it to the end. Never insert in the middle and never reorder -- both
 * renumber every packet after the edit, and while that is harmless when both sides are this same jar,
 * it makes the list useless as a record of what shipped.</p>
 */
public final class PacketRegistry {

    /** The space, in wire order. Appending is safe; inserting or reordering renumbers the tail. */
    private static final Class<?>[] PACKETS = {
            PacketDimInfo.class,
            PacketSatellite.class,
            PacketStellarInfo.class,
            PacketItemModifcation.class,
            PacketOxygenState.class,
            PacketStationUpdate.class,
            PacketSpaceStationInfo.class,
            PacketAtmSync.class,
            PacketBiomeIDChange.class,
            PacketStorageTileUpdate.class,
            PacketLaserGun.class,
            PacketAsteroidInfo.class,
            PacketAirParticle.class,
            PacketInvalidLocationNotify.class,
            PacketConfigSync.class,
            PacketFluidParticle.class,
            PacketSatellitesUpdate.class,
            PacketSyncKnownPlanets.class,
            PacketBackToRocketGui.class,
            PacketDeckCapture.class,
            PacketSlotDimSync.class,
            PacketSystemBodiesSync.class,
    };

    private PacketRegistry() {
    }

    /**
     * Registers every declared packet, in declaration order. Validates the whole list first, so a
     * duplicate fails before a single registration reaches the channel.
     */
    public static void registerAll() {
        verify(PACKETS);
        for (Class<?> packet : PACKETS) {
            PacketHandler.INSTANCE.addDiscriminator(packet);
        }
    }

    /**
     * Validates the live space without registering anything.
     *
     * @return how many packets are declared, so a caller checking the space can tell a clean result
     *         from an empty one.
     */
    public static int verifyDeclared() {
        verify(PACKETS);
        return PACKETS.length;
    }

    /**
     * Rejects a list that cannot produce a usable wire mapping. Exposed rather than private so the
     * rule can be exercised on a hand-built list, without having to corrupt the live space.
     */
    public static void verify(Class<?>[] packets) {
        Set<Class<?>> seen = new HashSet<>();
        for (int i = 0; i < packets.length; i++) {
            if (packets[i] == null) {
                throw new IllegalStateException("packet " + i + " in the discriminator space is null;"
                        + " every entry allocates a wire id, so a hole would shift every packet"
                        + " after it");
            }
            if (!seen.add(packets[i])) {
                throw new IllegalStateException(packets[i].getName() + " is declared twice in the"
                        + " discriminator space; the codec would keep the last registration for the"
                        + " shared id and decode the others as it");
            }
        }
    }
}
