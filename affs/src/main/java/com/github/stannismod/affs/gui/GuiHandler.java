package com.github.stannismod.affs.gui;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import com.github.stannismod.affs.item.ItemCodeDevice;
import com.github.stannismod.affs.te.*;
import com.github.stannismod.affs.util.CodeUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import javax.annotation.Nullable;

public class GuiHandler implements IGuiHandler {

    @Nullable
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == AdvancedForceFieldSystem.GUI_FIELD_GENERATOR) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityFieldGenerator) {
                return new ContainerFieldGenerator(player, (TileEntityFieldGenerator) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_ADMIN_ENERGY_SOURCE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityAdminEnergySource) {
                return new ContainerAdminEnergySource(player, (TileEntityAdminEnergySource) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_GENERATOR) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldGenerator) {
                return new ContainerShieldGenerator(player, (TileEntityShieldGenerator) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_NETWORK) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldCable) {
                return new ContainerShieldNetwork(player, (TileEntityShieldCable) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_CONSOLE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldConsole) {
                return new ContainerShieldConsole(player, (TileEntityShieldConsole) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_NETWORK_MAP) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldConsole) {
                return new ContainerShieldConsole(player, (TileEntityShieldConsole) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_CODE_DEVICE) {
            EnumHand hand = EnumHand.values()[Math.max(0, Math.min(EnumHand.values().length - 1, x))];
            ItemStack stack = player.getHeldItem(hand);
            if (stack.getItem() instanceof ItemCodeDevice) {
                return new ContainerCodeDevice(hand, CodeUtils.getCode(stack));
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == AdvancedForceFieldSystem.GUI_FIELD_GENERATOR) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityFieldGenerator) {
                return new GuiFieldGenerator(new ContainerFieldGenerator(player, (TileEntityFieldGenerator) te), (TileEntityFieldGenerator) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_ADMIN_ENERGY_SOURCE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityAdminEnergySource) {
                return new GuiAdminEnergySource(new ContainerAdminEnergySource(player, (TileEntityAdminEnergySource) te), (TileEntityAdminEnergySource) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_GENERATOR) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldGenerator) {
                return new GuiShieldGenerator(new ContainerShieldGenerator(player, (TileEntityShieldGenerator) te), (TileEntityShieldGenerator) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_NETWORK) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldCable) {
                return new GuiShieldNetwork(new ContainerShieldNetwork(player, (TileEntityShieldCable) te), (TileEntityShieldCable) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_SHIELD_CONSOLE) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldConsole) {
                return new GuiShieldConsole(new ContainerShieldConsole(player, (TileEntityShieldConsole) te), (TileEntityShieldConsole) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_NETWORK_MAP) {
            TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
            if (te instanceof TileEntityShieldConsole) {
                return new GuiNetworkMap(new ContainerShieldConsole(player, (TileEntityShieldConsole) te), (TileEntityShieldConsole) te, (TileEntityShieldConsole) te);
            }
        } else if (ID == AdvancedForceFieldSystem.GUI_CODE_DEVICE) {
            EnumHand hand = EnumHand.values()[Math.max(0, Math.min(EnumHand.values().length - 1, x))];
            ItemStack stack = player.getHeldItem(hand);
            if (stack.getItem() instanceof ItemCodeDevice) {
                return new GuiCodeDevice(new ContainerCodeDevice(hand, CodeUtils.getCode(stack)));
            }
        }
        return null;
    }
}
