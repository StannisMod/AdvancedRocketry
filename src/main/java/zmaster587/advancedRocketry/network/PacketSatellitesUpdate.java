package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.libVulpes.network.BasePacket;

public class PacketSatellitesUpdate extends BasePacket {

    private int dimNumber;
    private DimensionProperties dimProperties;

    public PacketSatellitesUpdate() {
    }

    public PacketSatellitesUpdate(int dimNumber, DimensionProperties dimProperties) {
        this.dimProperties = dimProperties;
        this.dimNumber = dimNumber;
    }

    @Override
    public void write(final ByteBuf byteBuf) {
        byteBuf.writeInt(this.dimNumber);

        NBTTagCompound compound = new NBTTagCompound();
        for (SatelliteBase satellite : this.dimProperties.getTickingSatellites()) {
            NBTTagCompound satTag = new NBTTagCompound();
            satellite.writeToNBT(satTag);
            compound.setTag(String.valueOf(satellite.getId()), satTag);
        }
        ByteBufUtils.writeTag(byteBuf, compound);
    }

    @Override
    public void readClient(final ByteBuf byteBuf) {

        if (FMLCommonHandler.instance().getEffectiveSide().isServer()) {
            return;
        }

        int dimNumber = byteBuf.readInt();
        NBTTagCompound compound = ByteBufUtils.readTag(byteBuf);

        if (compound == null) {
            return;
        }

        DimensionProperties prop = DimensionManager.getInstance().getDimensionProperties(dimNumber);

        if (prop == null) {
            AdvancedRocketry.logger.warn("Received satellite update for unknown dim {}", dimNumber);
            return;
        }

        for (String key : compound.getKeySet()) {
            long satelliteId;

            try {
                satelliteId = Long.parseLong(key);
            } catch (NumberFormatException e) {
                continue;
            }

            NBTTagCompound satTag = compound.getCompoundTag(key);
            SatelliteBase satellite = prop.getSatellite(satelliteId);

            if (satellite == null) {
                satellite = SatelliteRegistry.createFromNBT(satTag);

                if (satellite == null) {
                    AdvancedRocketry.logger.warn(
                            "Could not create satellite {} in dim {} from update packet",
                            satelliteId,
                            dimNumber
                    );
                    continue;
                }

                prop.addSatellite(satellite);
            } else {
                satellite.readFromNBT(satTag);
            }
        }
    }

    @Override
    public void read(final ByteBuf byteBuf) {

    }

    @Override
    public void executeClient(final EntityPlayer entityPlayer) {

    }

    @Override
    public void executeServer(final EntityPlayerMP entityPlayerMP) {

    }
}
