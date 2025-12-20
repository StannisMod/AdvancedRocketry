package zmaster587.advancedRocketry.satellite;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.IUniversalEnergyTransmitter;

import javax.annotation.Nonnull;

public class SatelliteMicrowaveEnergy extends SatelliteBase implements IUniversalEnergyTransmitter {

    byte teir;

    public SatelliteMicrowaveEnergy() {
        super();
    }

    @Override
    public void setProperties(@Nonnull ItemStack satelliteProperties) {
        super.setProperties(satelliteProperties);
    }

    @Override
    public String getInfo(World world) {
        return LibVulpes.proxy.getLocalizedString("msg.itemsatellite.microwavestatus");
    }


    @Override
    public String getName() {
        return LibVulpes.proxy.getLocalizedString("item.satellite.solar");
    }

    @Override
    public boolean performAction(EntityPlayer player, World world, BlockPos pos) {
        return false;
    }

    @Override
    public double failureChance() {
        return 0;
    }

    @Override
    public void setDimensionId(World world) {
        super.setDimensionId(world);
    }

    @Override
    public int getEnergyMTU(EnumFacing side) {
        return transmitEnergy(side, true);
    }

    @Override
    public int transmitEnergy(EnumFacing dir, boolean simulate) {

        // cap by generation per tick (after upkeep)
        int genPerTick = Math.max(0, getPowerPerTick() - 1);

        int maxSend = (int)Math.round(
            ARConfiguration.getCurrentConfig().microwaveRecieverMulitplier * genPerTick
        );

        return battery.extractEnergy(maxSend, simulate);
    }



    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setByte("teir", teir);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        teir = nbt.getByte("teir");
    }
}
