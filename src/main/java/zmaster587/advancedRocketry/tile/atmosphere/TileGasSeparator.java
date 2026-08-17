package zmaster587.advancedRocketry.tile.atmosphere;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.AdvancedRocketryFluids;
import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.tile.TileInventoriedRFConsumerTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The gas separator: one block, two directions, and the safety governor of the whole air loop.
 * <p>
 * <b>Split</b> pulls a gas out of the room it touches and into its tank — carbon dioxide first,
 * because that is what regeneration is hungry for, and nitrogen once the CO2 is gone. <b>Combine</b>
 * pushes tank gas back into the room, and this is where the loop's control knob lives: oxygen goes
 * in only as far as the safe partial-pressure band allows, so no arrangement of pipes can enrich a
 * cabin into a fire hazard. Nitrogen, being inert, has no ceiling — it is the diluent.
 * <p>
 * One block with a mode rather than two block types, because the manual use is a utility (fill a
 * tank with pure gas, then put it back); a steady automated loop simply needs two of them, one in
 * each mode.
 */
public class TileGasSeparator extends TileInventoriedRFConsumerTank implements IModularInventory {

    private boolean combining;
    /** Its own cadence. Never a world-clock modulo: that syncs every separator in the world onto
     *  one tick and is invisible to a force-ticking harness. */
    private int ticksSinceOperation;

    public TileGasSeparator() {
        super(10000, 1, 8000);
    }

    public boolean isCombining() {
        return combining;
    }

    /** Shift-right-click flips the direction; the block class routes that here. */
    public void toggleMode() {
        combining = !combining;
        markDirty();
    }

    @Override
    @Nonnull
    public int[] getSlotsForFace(@Nullable EnumFacing side) {
        return new int[0];
    }

    @Override
    public boolean isItemValidForSlot(int slot, @Nonnull net.minecraft.item.ItemStack stack) {
        return true;
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockGasSeparator.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(net.minecraft.entity.player.EntityPlayer entity) {
        return true;
    }

    @Override
    public List<ModuleBase> getModules(int ID, net.minecraft.entity.player.EntityPlayer player) {
        List<ModuleBase> modules = new ArrayList<>(2);
        modules.add(new zmaster587.libVulpes.inventory.modules.ModulePower(18, 20, this.energy));
        modules.add(new zmaster587.libVulpes.inventory.modules.ModuleLiquidIndicator(50, 20, this));
        return modules;
    }

    @Override
    public int getPowerPerOperation() {
        return ARConfiguration.getCurrentConfig().lifeSupportSeparatorPower;
    }

    @Override
    public boolean canPerformFunction() {
        if (world.isRemote || !ARConfiguration.getCurrentConfig().lifeSupportZones)
            return false;
        if (++ticksSinceOperation < 20)
            return false;
        ticksSinceOperation = 0;
        return getZoneAir() != null;
    }

    @Override
    public void performFunction() {
        AirState air = getZoneAir();
        BlockPos cell = findServedCell();
        if (air == null || cell == null)
            return;

        if (combining)
            combine(air, cell);
        else
            split(air, cell);

        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler != null)
            handler.refreshDerivedAtmosphereAt(cell);
        markDirty();
    }

    /**
     * Room to tank. CO2 first — it is the stale component and the thing regeneration consumes —
     * then nitrogen once the air is clean, so a separator left running does not strip the oxygen
     * the crew are breathing.
     */
    private void split(@Nonnull AirState air, @Nonnull BlockPos cell) {
        int budget = pressureFor(spaceInTank(), cell);
        if (budget <= 0)
            return;
        int rate = Math.min(ARConfiguration.getCurrentConfig().lifeSupportSeparatorRate, budget);

        int taken = air.drawCarbonDioxide(rate);
        Fluid gas = AdvancedRocketryFluids.fluidCarbonDioxide;
        if (taken <= 0) {
            taken = air.drawNitrogen(rate);
            gas = AdvancedRocketryFluids.fluidNitrogen;
        }
        if (taken > 0)
            fill(new FluidStack(gas, volumeFor(taken, cell)), true);
    }

    /**
     * Tank to room. Oxygen is admitted only up to the safe band — the governor — while nitrogen,
     * inert by definition, is not capped.
     */
    private void combine(@Nonnull AirState air, @Nonnull BlockPos cell) {
        FluidStack held = getTankFluid();
        if (held == null || held.amount <= 0)
            return;

        int rate = ARConfiguration.getCurrentConfig().lifeSupportSeparatorRate;
        int available = Math.min(rate, pressureFor(held.amount, cell));
        if (available <= 0)
            return;

        // Gas out of a tank arrives at the temperature the tank has been sitting at, not at the
        // room's. Venting a bottle into a hot compartment therefore cools it a little, which is not a
        // mechanic anyone added — it is what the calorimeter rule says once the gas has to declare a
        // temperature at all.
        double fromTheTank = AirState.ambientKelvin();
        if (held.getFluid() == AdvancedRocketryFluids.fluidOxygen) {
            int admitted = Math.min(available, air.oxygenHeadroom());
            if (admitted <= 0)
                return;
            air.addOxygen(admitted, fromTheTank);
            drain(volumeFor(admitted, cell), true);
        } else if (held.getFluid() == AdvancedRocketryFluids.fluidNitrogen) {
            air.addNitrogen(available, fromTheTank);
            drain(volumeFor(available, cell), true);
        }
    }

    // ─── unit conversion ───────────────────────────────────────────────

    /** Millibuckets that a partial pressure amounts to across this zone's whole volume. */
    private int volumeFor(int partialPressure, @Nonnull BlockPos cell) {
        long mb = (long) partialPressure * zoneVolume(cell)
                * ARConfiguration.getCurrentConfig().lifeSupportFluidPerAtmBlock / AirState.ONE_ATM;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, mb));
    }

    /** The inverse: partial pressure that this many millibuckets can supply to the zone. */
    private int pressureFor(int millibuckets, @Nonnull BlockPos cell) {
        int perAtmBlock = ARConfiguration.getCurrentConfig().lifeSupportFluidPerAtmBlock;
        long denominator = (long) zoneVolume(cell) * perAtmBlock;
        if (denominator <= 0)
            return 0;
        long pressure = (long) millibuckets * AirState.ONE_ATM / denominator;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, pressure));
    }

    private int zoneVolume(@Nonnull BlockPos cell) {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        return handler == null ? 1 : Math.max(1, handler.getBlobSizeAt(cell));
    }

    // ─── zone and tank access ──────────────────────────────────────────

    /**
     * The air cell this machine serves — a neighbour, never its own solid position.
     * <p>
     * Public because "which room am I working on, if any" is the one question that separates a
     * machine refusing to act from a machine that never found a room; a diagnostic that cannot
     * tell those apart reads both as silence.
     */
    @Nullable
    public BlockPos findServedCell() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler == null)
            return null;
        for (EnumFacing dir : EnumFacing.values()) {
            BlockPos side = pos.offset(dir);
            if (handler.getAirStateAt(side) != null)
                return side;
        }
        return null;
    }

    @Nullable
    private AirState getZoneAir() {
        AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        BlockPos cell = findServedCell();
        return (handler == null || cell == null) ? null : handler.getAirStateAt(cell);
    }

    @Nullable
    private FluidStack getTankFluid() {
        return tank.getFluid();
    }

    private int spaceInTank() {
        return tank.getCapacity() - tank.getFluidAmount();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        combining = nbt.getBoolean("combining");
        ticksSinceOperation = nbt.getInteger("opTicks");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("combining", combining);
        nbt.setInteger("opTicks", ticksSinceOperation);
        return nbt;
    }
}
