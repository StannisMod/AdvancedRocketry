package zmaster587.advancedRocketry.tile.infrastructure;

import micdoodle8.mods.galacticraft.core.network.PacketEntityUpdate;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import java.util.List;

import javax.annotation.Nullable;

public class TileRocketFluidUnloader extends TileRocketFluidLoader implements IInfrastructure, ITickable, IButtonInventory, INetworkMachine {

    public TileRocketFluidUnloader() {
        super();
        this.setOutputOnly(true);
    }

    public TileRocketFluidUnloader(int size) {
        super(size);
    }

    @Override
    public String getModularInventoryName() {
        return "tile.loader.4.name";
    }

    @Nullable
    private static IFluidHandler getBestDrainHandler(TileEntity te, int probeAmount) {
        // Try null side first
        IFluidHandler h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        if (h != null) {
            FluidStack probe = h.drain(probeAmount, false);
            if (probe != null && probe.amount > 0) return h;
        }

        // Then try all faces
        for (EnumFacing f : EnumFacing.VALUES) {
            h = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, f);
            if (h == null) continue;

            FluidStack probe = h.drain(probeAmount, false);
            if (probe != null && probe.amount > 0) return h;
        }

        return null;
    }

    @Override
    public void update() {
        if (world.isRemote || rocket == null) return;

        if (transferCooldown > 0) {
            transferCooldown--;
            return;
        }
        transferCooldown = TRANSFER_INTERVAL_TICKS;

        boolean isAllowToOperate = (inputstate == RedstoneState.OFF
                || isStateActive(inputstate, getStrongPowerForSides(world, getPos())));

        List<TileEntity> tiles = rocket.storage.getFluidTiles();

        boolean rocketHasDrainableFluidSomewhere = false;
        boolean doupdate = false;

        for (TileEntity tile : tiles) {
            if (tile == null || tile.isInvalid()) continue;

            IFluidHandler drainHandler = getBestDrainHandler(tile, 1);
            if (drainHandler == null) continue;

            // redstone probe: does rocket have any drainable fluid?
            FluidStack probe = drainHandler.drain(1, false);
            if (probe != null && probe.amount > 0) {
                rocketHasDrainableFluidSomewhere = true;
            }

            if (!isAllowToOperate) continue;

            int space = getFluidTank().getCapacity() - getFluidTank().getFluidAmount();
            if (space <= 0) continue;

            FluidStack simulated = drainHandler.drain(space, false);
            if (simulated == null || simulated.amount <= 0) continue;

            int accepted = getFluidTank().fill(simulated, false);
            if (accepted <= 0) continue;

            FluidStack drained = drainHandler.drain(accepted, true);
            if (drained != null && drained.amount > 0) {
                getFluidTank().fill(drained, true);
                doupdate = true;
                break; // one transfer per <TRANSFER_INTERVAL_TICKS> ticks
            }
        }

        if (doupdate) {
            PacketHandler.sendToNearby(new PacketEntity(rocket, (byte) 9987),
                    world.provider.getDimension(), getPos(), 128);
            markDirty();
        }

        setRedstoneState(!rocketHasDrainableFluidSomewhere);
    }
}
