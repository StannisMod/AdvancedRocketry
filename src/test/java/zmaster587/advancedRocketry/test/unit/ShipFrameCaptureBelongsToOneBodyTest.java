package zmaster587.advancedRocketry.test.unit;

import net.minecraft.entity.Entity;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A ship-frame capture belongs to ONE body, identified by object identity - never to whichever
 * body happens to carry the same network id.
 *
 * <p><b>Why this contract has teeth.</b> Vanilla {@code Entity} declares equality by network id
 * alone ({@code equals} compares {@code entityId}, {@code hashCode} returns it), and in an
 * integrated server - singleplayer, i.e. how this mod is actually played - the client's
 * {@code EntityPlayerSP} and the server's {@code EntityPlayerMP} for one player are two distinct
 * objects in ONE JVM carrying the SAME id. Any capture store that matches keys by {@code equals}
 * therefore merges the two sides into a single slot: whichever side writes last wins, and either
 * side's release deletes the other side's capture. Measured in a 2026-08-03 playtest as a
 * ~5&nbsp;Hz capture/release war in which the server's own per-tick release of a SEATED pilot kept
 * deleting the capture his client had just installed - 142 cross-side deletions in 80 seconds, with
 * the two sides' install/release counts conserving only when summed ACROSS sides.</p>
 *
 * <p>The two sides run on different threads and own different halves of a body's movement; they are
 * not supposed to see each other's resolution state at all. This test pins that separation at the
 * one place it can be observed cheaply and deterministically.</p>
 *
 * <p><b>On the arrangement.</b> Installing a capture needs the physics mod, which is absent at this
 * tier, so the capture is placed directly into the store by reflection. That is scaffolding only:
 * every assertion below runs the real production predicates ({@link ShipFrameTravel#isResolving},
 * {@link ShipFrameTravel#aboardShipId}), and the first of them is a CONTROL - it proves the
 * arrangement installed something, without which "the other body sees nothing" would pass just as
 * well on an empty store.</p>
 */
public class ShipFrameCaptureBelongsToOneBodyTest {

    @BeforeClass
    public static void bootstrap() {
        Bootstrap.register();
    }

    /** A minimal world-less body: all this contract needs of an entity is its identity and its id. */
    private static final class Body extends Entity {
        Body(int networkId) {
            super(null);
            setEntityId(networkId);
        }

        @Override
        protected void entityInit() {
        }

        @Override
        protected void readEntityFromNBT(NBTTagCompound compound) {
        }

        @Override
        protected void writeEntityToNBT(NBTTagCompound compound) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void installCaptureFor(Entity body, String shipId) throws Exception {
        Field state = ShipFrameTravel.class.getDeclaredField("STATE");
        state.setAccessible(true);
        Class<?> stateClass = Class.forName(
                "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel$ShipFrameState");
        Constructor<?> ctor = stateClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object capture = ctor.newInstance();
        Field ship = stateClass.getDeclaredField("shipId");
        ship.setAccessible(true);
        ship.set(capture, shipId);
        ((Map<Entity, Object>) state.get(null)).put(body, capture);
    }

    @Test
    public void twoBodiesSharingANetworkIdDoNotShareACapture() throws Exception {
        Body clientSide = new Body(322);
        Body serverSide = new Body(322);

        // The premise, pinned so a future reader does not have to take it on trust: these are two
        // objects, and vanilla nevertheless calls them equal. That is exactly what a store keyed by
        // equals collapses.
        assertNotSame("the two sides hold DIFFERENT objects", clientSide, serverSide);
        assertEquals("vanilla equality is by network id alone", clientSide, serverSide);
        assertEquals("vanilla hashes by network id alone",
                clientSide.hashCode(), serverSide.hashCode());

        installCaptureFor(clientSide, "ship-under-test");

        assertTrue("CONTROL: the body the capture was installed for must read as resolving -"
                        + " without this the assertion below cannot fail",
                ShipFrameTravel.isResolving(clientSide));

        assertFalse("a capture belongs to ONE body: the other side's copy of the same network id"
                        + " must not read as resolving",
                ShipFrameTravel.isResolving(serverSide));
        assertNull("nor may it inherit the anchor ship",
                ShipFrameTravel.aboardShipId(serverSide));
    }

    @Test
    public void aBodyWithADifferentNetworkIdIsUnaffectedEitherWay() throws Exception {
        Body captured = new Body(4711);
        Body bystander = new Body(4712);

        installCaptureFor(captured, "ship-under-test");

        assertTrue("CONTROL: the captured body must read as resolving",
                ShipFrameTravel.isResolving(captured));
        assertFalse("an unrelated body must never inherit a capture",
                ShipFrameTravel.isResolving(bystander));
    }
}
