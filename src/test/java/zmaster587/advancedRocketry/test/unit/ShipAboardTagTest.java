package zmaster587.advancedRocketry.test.unit;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipAboardTag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for the durable "which ship is this player aboard" tag.
 *
 * Pins the persistence contract the login restore path depends on: a stamped record round-trips
 * across a save/load of the player's ForgeData compound; anything absent or malformed answers
 * {@code null} instead of throwing (this code runs inside the login path, where a throw is a failed
 * login and a half-read record is a player teleported somewhere he never was); a ship at the galactic
 * origin is still distinguishable from "no record"; and the shared ForgeData compound is never
 * polluted beyond the one namespaced key. Does not pin the tag's internal field names.
 */
public class ShipAboardTagTest {

    private static final UUID SHIP = UUID.fromString("f7a1c3d2-0000-4000-8000-00000000beef");
    private static final GalacticCoord COORD =
            GalacticCoord.ofSectorLocal(12L, -3L, 7L, 640L, -128L, 4096L);

    private static ShipAboardTag.Aboard sample() {
        return new ShipAboardTag.Aboard(SHIP, COORD, 0, 2, -3);
    }

    @Test
    public void nbtRoundTripPreservesTheRecord() {
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull("a stamped record must survive the ForgeData round-trip", restored);
        assertEquals(sample(), restored);
        assertEquals(sample().hashCode(), restored.hashCode());
        assertEquals(SHIP, restored.shipId);
        assertEquals(COORD, restored.coord);
    }

    @Test
    public void signedSeatOffsetsRoundTrip() {
        // The seat is identified by its flight-computer link offset, which is signed on every axis
        // (the computer can sit below/behind the seat). A magnitude-only encoding would re-seat a
        // restored player in the mirrored seat.
        ShipAboardTag.Aboard aboard =
                new ShipAboardTag.Aboard(SHIP, COORD, -5, -1, 9);
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, aboard);

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull(restored);
        assertEquals(-5, restored.afcDx);
        assertEquals(-1, restored.afcDy);
        assertEquals(9, restored.afcDz);
    }

    @Test
    public void shipAtGalacticOriginIsNotMistakenForNoRecord() {
        // The shared coordinate decoder answers ORIGIN for an absent coordinate, so "absent" and
        // "at the origin" must be told apart here or a crewless player would be restored aboard.
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData,
                new ShipAboardTag.Aboard(SHIP, GalacticCoord.ORIGIN, 0, 1, 0));

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull(restored);
        assertEquals(GalacticCoord.ORIGIN, restored.coord);
        assertNull("a compound with no record at all still reads null",
                ShipAboardTag.read(new NBTTagCompound()));
    }

    @Test
    public void absentRecordReadsAsNull() {
        assertNull(ShipAboardTag.read(new NBTTagCompound()));

        NBTTagCompound foreign = new NBTTagCompound();
        foreign.setString("someOtherModsKey", "value");
        assertNull(ShipAboardTag.read(foreign));
    }

    @Test
    public void malformedRecordReadsAsNullRatherThanThrowing() {
        // Wrong shape entirely: the key holds a string, not a compound.
        NBTTagCompound wrongType = new NBTTagCompound();
        wrongType.setString(ShipAboardTag.KEY, "junk");
        assertNull(ShipAboardTag.read(wrongType));

        // A compound with no ship id.
        NBTTagCompound noShip = new NBTTagCompound();
        NBTTagCompound sub = new NBTTagCompound();
        COORD.writeToNBT(sub);
        noShip.setTag(ShipAboardTag.KEY, sub);
        assertNull(ShipAboardTag.read(noShip));

        // A ship id that is not a UUID.
        NBTTagCompound badId = new NBTTagCompound();
        NBTTagCompound badSub = new NBTTagCompound();
        badSub.setString("shipId", "not-a-uuid");
        COORD.writeToNBT(badSub);
        badId.setTag(ShipAboardTag.KEY, badSub);
        assertNull(ShipAboardTag.read(badId));

        // A ship id with no coordinate.
        NBTTagCompound noCoord = new NBTTagCompound();
        NBTTagCompound coordless = new NBTTagCompound();
        coordless.setString("shipId", SHIP.toString());
        noCoord.setTag(ShipAboardTag.KEY, coordless);
        assertNull(ShipAboardTag.read(noCoord));
    }

    @Test
    public void clearRemovesTheRecordAndIsIdempotent() {
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());
        assertNotNull(ShipAboardTag.read(forgeData));

        ShipAboardTag.clear(forgeData);
        assertNull("a cleared player is no longer aboard", ShipAboardTag.read(forgeData));

        // Clearing again, and clearing a compound that never had a record, must both be no-ops.
        ShipAboardTag.clear(forgeData);
        ShipAboardTag.clear(new NBTTagCompound());
        assertNull(ShipAboardTag.read(forgeData));
    }

    @Test
    public void writeReplacesThePreviousRecord() {
        // A player who leaves one ship for another must not keep a second, stale binding.
        UUID otherShip = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());
        ShipAboardTag.write(forgeData,
                new ShipAboardTag.Aboard(otherShip, GalacticCoord.ORIGIN, 1, 1, 1));

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull(restored);
        assertEquals(otherShip, restored.shipId);
        assertEquals(GalacticCoord.ORIGIN, restored.coord);
    }

    @Test
    public void theSharedForgeDataCompoundIsNotPolluted() {
        // ForgeData is shared with every other mod on the pack: the record must live entirely under
        // one namespaced key and must not disturb anything else on write or on clear.
        NBTTagCompound forgeData = new NBTTagCompound();
        forgeData.setString("someOtherModsKey", "value");
        forgeData.setInteger("anotherModsCounter", 42);

        ShipAboardTag.write(forgeData, sample());
        assertTrue(forgeData.hasKey(ShipAboardTag.KEY));
        assertEquals(3, forgeData.getKeySet().size());
        assertEquals("value", forgeData.getString("someOtherModsKey"));
        assertEquals(42, forgeData.getInteger("anotherModsCounter"));

        ShipAboardTag.clear(forgeData);
        assertFalse(forgeData.hasKey(ShipAboardTag.KEY));
        assertEquals("value", forgeData.getString("someOtherModsKey"));
        assertEquals(42, forgeData.getInteger("anotherModsCounter"));
    }

    @Test
    public void nullishInputsAreToleratedRatherThanThrowing() {
        assertNull(ShipAboardTag.read(null));
        ShipAboardTag.write(null, sample());          // no target compound: nothing to do
        ShipAboardTag.clear((NBTTagCompound) null);

        // A null record clears rather than leaving a half-formed tag a reader would have to reject.
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());
        ShipAboardTag.write(forgeData, null);
        assertNull(ShipAboardTag.read(forgeData));

        // Likewise for a record whose ship or coordinate went missing upstream.
        ShipAboardTag.write(forgeData, new ShipAboardTag.Aboard(null, COORD, 0, 0, 0));
        assertNull(ShipAboardTag.read(forgeData));
        ShipAboardTag.write(forgeData, new ShipAboardTag.Aboard(SHIP, null, 0, 0, 0));
        assertNull(ShipAboardTag.read(forgeData));
    }
}
