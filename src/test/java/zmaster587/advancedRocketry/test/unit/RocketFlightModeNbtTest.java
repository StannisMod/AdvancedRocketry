package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;
import zmaster587.advancedRocketry.api.RocketFlightMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Contract for {@link RocketFlightMode} NBT round-trip and back-compat.
 *
 * Verified contracts:
 *  1. Missing NBT key → default (CLASSIC_LAUNCH).
 *  2. Round-trip: writeToNBT then readFromNBT recovers the original.
 *  3. Forward-compat: unknown name in NBT degrades to default (does NOT throw).
 *  4. Null arguments are tolerated and produce default behaviour.
 */
public class RocketFlightModeNbtTest {

    @Test
    public void defaultIsClassicLaunch() {
        assertSame(RocketFlightMode.CLASSIC_LAUNCH, RocketFlightMode.DEFAULT);
    }

    @Test
    public void missingNbtKeyReadsDefault() {
        NBTTagCompound nbt = new NBTTagCompound();
        assertSame(RocketFlightMode.DEFAULT, RocketFlightMode.readFromNBT(nbt));
    }

    @Test
    public void nullNbtReadsDefault() {
        assertSame(RocketFlightMode.DEFAULT, RocketFlightMode.readFromNBT(null));
    }

    @Test
    public void emptyStringKeyReadsDefault() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString(RocketFlightMode.NBT_KEY, "");
        assertSame(RocketFlightMode.DEFAULT, RocketFlightMode.readFromNBT(nbt));
    }

    @Test
    public void roundTripClassicLaunch() {
        NBTTagCompound nbt = new NBTTagCompound();
        RocketFlightMode.writeToNBT(nbt, RocketFlightMode.CLASSIC_LAUNCH);
        assertEquals("CLASSIC_LAUNCH", nbt.getString(RocketFlightMode.NBT_KEY));
        assertSame(RocketFlightMode.CLASSIC_LAUNCH, RocketFlightMode.readFromNBT(nbt));
    }

    @Test
    public void roundTripFreeFlight() {
        NBTTagCompound nbt = new NBTTagCompound();
        RocketFlightMode.writeToNBT(nbt, RocketFlightMode.FREE_FLIGHT);
        assertEquals("FREE_FLIGHT", nbt.getString(RocketFlightMode.NBT_KEY));
        assertSame(RocketFlightMode.FREE_FLIGHT, RocketFlightMode.readFromNBT(nbt));
    }

    @Test
    public void unknownNameDegradesToDefault() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString(RocketFlightMode.NBT_KEY, "SOME_FUTURE_MODE_FROM_NEWER_BUILD");
        assertSame(RocketFlightMode.DEFAULT, RocketFlightMode.readFromNBT(nbt));
    }

    @Test
    public void writeNullModeWritesDefault() {
        NBTTagCompound nbt = new NBTTagCompound();
        RocketFlightMode.writeToNBT(nbt, null);
        assertEquals(RocketFlightMode.DEFAULT.name(), nbt.getString(RocketFlightMode.NBT_KEY));
    }

    @Test
    public void writeNullNbtIsNoOp() {
        // Should not throw.
        RocketFlightMode.writeToNBT(null, RocketFlightMode.FREE_FLIGHT);
    }

    @Test
    public void enumOrderingStableForWireFormat() {
        // CRITICAL: SET_FLIGHT_MODE / FREE_FLIGHT_INPUT packets serialise the
        // enum ordinal as a single byte. Append-only ordering is the wire contract.
        assertEquals(0, RocketFlightMode.CLASSIC_LAUNCH.ordinal());
        assertEquals(1, RocketFlightMode.FREE_FLIGHT.ordinal());
    }
}
