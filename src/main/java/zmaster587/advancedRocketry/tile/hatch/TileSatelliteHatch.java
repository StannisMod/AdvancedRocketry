package zmaster587.advancedRocketry.tile.hatch;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.SatelliteRegistry;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.satellite.SatelliteProperties;
import zmaster587.advancedRocketry.item.ItemPackedStructure;
import zmaster587.advancedRocketry.item.ItemSatellite;
import zmaster587.advancedRocketry.network.PacketBackToRocketGui;
import zmaster587.advancedRocketry.util.IWeighted;
import zmaster587.advancedRocketry.util.RocketGuiNavigation;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.tile.multiblock.hatch.TileInventoryHatch;

import java.util.List;

public class TileSatelliteHatch extends TileInventoryHatch implements IWeighted, IButtonInventory {

    public TileSatelliteHatch() {
        super();
    }

    public TileSatelliteHatch(int i) {
        super(1);
        inventory.setCanInsertSlot(0, true);
        inventory.setCanExtractSlot(0, true);
    }

    @Override
    public String getModularInventoryName() {
        return "container.satellite";
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules = super.getModules(ID, player);
        RocketGuiNavigation.addBackButtonIfApplicable(modules, player, this);
        return modules;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == RocketGuiNavigation.BUTTON_BACK_TO_ROCKET) {
            PacketHandler.sendToServer(new PacketBackToRocketGui(
                    this.world.provider.getDimension(),
                    this.pos
            ));
        }
    }

    public SatelliteBase getSatellite() {

        ItemStack itemStack = inventory.getStackInSlot(0);
        if (!itemStack.isEmpty() && itemStack.getItem() instanceof ItemSatellite && SatelliteRegistry.getSatelliteProperties(itemStack) != null) {
            SatelliteProperties properties = SatelliteRegistry.getSatelliteProperties(itemStack);

            if (properties == null)
                return null;

            SatelliteBase satellite = SatelliteRegistry.getNewSatellite(properties.getSatelliteType());

            if (satellite == null)
                return null;

            satellite.setProperties(itemStack);
            return satellite;
        } else
            return null;
    }

    @Override
    public float getWeight() {
        ItemStack stack = inventory.getStackInSlot(0);
        if (stack.isEmpty()) {
            return 0.0F;
        }

        if (stack.getItem() instanceof ItemSatellite && SatelliteRegistry.getSatelliteProperties(stack) != null) {
            SatelliteProperties properties = SatelliteRegistry.getSatelliteProperties(stack);

            if (properties == null)
                return 0.0F;

            return properties.getWeight();
        }

        if (stack.getItem() instanceof ItemPackedStructure) {
            ItemPackedStructure struct = (ItemPackedStructure) stack.getItem();

            return struct.getStructure(stack).getWeight();
        }

        return 0;
    }
}