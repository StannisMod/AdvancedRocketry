package zmaster587.advancedRocketry.tile.atmosphere;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereRegister;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.atmosphere.AtmosphereType;
import zmaster587.advancedRocketry.block.BlockRedstoneEmitter;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nullable;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class TileAtmosphereDetector extends TileEntity implements ITickable, IModularInventory, IButtonInventory, INetworkMachine {

    private IAtmosphere atmosphereToDetect;

    private static final int BUTTON_COLOR_NORMAL = 0xFF22FF22;
    private static final int BUTTON_COLOR_SELECTED = 0xFFFFFF55;
    private static final int BUTTON_BG_NORMAL = 0xFFFFFFFF;
    private static final int BUTTON_BG_SELECTED = 0xFF444444;

    public TileAtmosphereDetector() {
        atmosphereToDetect = AtmosphereType.AIR;
    }


    @Override
    public void update() {
        if (!world.isRemote && world.getWorldTime() % 10 == 0) {
            IBlockState state = world.getBlockState(pos);
            boolean detectedAtm = false;

            //TODO: Galacticcraft support
            AtmosphereHandler atmhandler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
            if (atmhandler == null) {
                detectedAtm = atmosphereToDetect == AtmosphereType.AIR;
            } else {
                for (EnumFacing direction : EnumFacing.values()) {
                    detectedAtm = (!world.getBlockState(pos.offset(direction)).isOpaqueCube() && atmosphereToDetect == atmhandler.getAtmosphereType(pos.offset(direction)));
                    if (detectedAtm) break;
                }
            }

            if (((BlockRedstoneEmitter) state.getBlock()).getState(world, state, pos) != detectedAtm) {
                ((BlockRedstoneEmitter) state.getBlock()).setState(world, state, pos, detectedAtm);
            }
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos,
                                 IBlockState oldState, IBlockState newSate) {
        return (oldState.getBlock() != newSate.getBlock());
    }


    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();
        List<ModuleBase> btns = new LinkedList<>();

        List<IAtmosphere> atmospheres = AtmosphereRegister.getInstance().getAtmosphereList();

        for (int i = 0; i < atmospheres.size(); i++) {
            IAtmosphere atm = atmospheres.get(i);
            String label = getLocalizedAtmosphereName(atm);

            btns.add(AdvancedRocketry.proxy.createAtmosphereDetectorButton(
                    60,
                    4 + i * 24,
                    i,
                    atm,
                    label,
                    this,
                    zmaster587.libVulpes.inventory.TextureResources.buttonBuild
            ));
        }

        ModuleContainerPan panningContainer = new ModuleContainerPan(
                5, 20, btns, new LinkedList<>(),
                zmaster587.libVulpes.inventory.TextureResources.starryBG,
                160, 100, 0, 500
        );
        modules.add(panningContainer);
        return modules;
    }


    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockOxygenDetection.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(@Nullable EntityPlayer entity) {
        return true;
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        List<IAtmosphere> atmospheres = AtmosphereRegister.getInstance().getAtmosphereList();

        if (buttonId < 0 || buttonId >= atmospheres.size()) {
            return;
        }

        IAtmosphere oldAtmosphere = atmosphereToDetect;
        atmosphereToDetect = atmospheres.get(buttonId);

        if (world == null || world.isRemote) {
            String atmosphereName = getLocalizedAtmosphereName(atmosphereToDetect);

            if (isSameAtmosphere(oldAtmosphere, atmosphereToDetect)) {
                AdvancedRocketry.proxy.sendClientStatusMessage(
                        "msg.advancedrocketry.atmosphereDetector.alreadySelected",
                        atmosphereName
                );
            }
            else {
                AdvancedRocketry.proxy.sendClientStatusMessage(
                        "msg.advancedrocketry.atmosphereDetector.selected",
                        atmosphereName
                );
            }

            PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
        }
    }
    public boolean isAtmosphereSelected(IAtmosphere atmosphere) {
        return isSameAtmosphere(atmosphereToDetect, atmosphere);
    }

    public static String getLocalizedAtmosphereName(IAtmosphere atmosphere) {
        if (atmosphere == null) {
            return "";
        }

        String key = "msg.atmosphere." + atmosphere.getUnlocalizedName().toLowerCase(Locale.ROOT);
        String label = LibVulpes.proxy.getLocalizedString(key);

        if (label.equals(key)) {
            return atmosphere.getUnlocalizedName();
        }

        return label;
    }

    private static boolean isSameAtmosphere(IAtmosphere first, IAtmosphere second) {
        if (first == second) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        return first.getUnlocalizedName().equals(second.getUnlocalizedName());
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        //Send the unlocalized name over the net to reduce chances of foulup due to client/server inconsistencies
        if (id == 0) {
            PacketBuffer buf = new PacketBuffer(out);
            buf.writeShort(atmosphereToDetect.getUnlocalizedName().length());
            buf.writeString(atmosphereToDetect.getUnlocalizedName());
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        if (packetId == 0) {
            PacketBuffer buf = new PacketBuffer(in);
            nbt.setString("uName", buf.readString(buf.readShort()));
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == 0) {
            String name = nbt.getString("uName");
            atmosphereToDetect = AtmosphereRegister.getInstance().getAtmosphere(name);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        nbt.setString("atmName", atmosphereToDetect.getUnlocalizedName());
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        atmosphereToDetect = AtmosphereRegister.getInstance().getAtmosphere(nbt.getString("atmName"));
    }
}
