package zmaster587.advancedRocketry.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.satellite.SatelliteOreMapping;
import zmaster587.libVulpes.inventory.modules.IModularInventory;

public class GuiHandler implements IGuiHandler {

    // Stateless dispatcher: every gui id that isn't AR's own OreMappingSatellite
    // is forwarded to the libVulpes handler (see the delegation below). One
    // shared instance — it holds no state.
    private static final zmaster587.libVulpes.inventory.GuiHandler LIBVULPES =
            new zmaster587.libVulpes.inventory.GuiHandler();

    //X coord is entity ID num if entity
    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world,
                                      int x, int y, int z) {

        Object tile;

        if (x == -1 && y < -1) {
            ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);

            //If there is latency or some desync odd things can happen so check for that
            if (stack.isEmpty() || !(stack.getItem() instanceof IModularInventory)) {
                return null;
            }
        }

        if (ID == guiId.OreMappingSatellite.ordinal()) {
            SatelliteBase satellite = DimensionManager.getInstance().getSatellite(y);

            if (!(satellite instanceof SatelliteOreMapping) || satellite.getDimensionId() != world.provider.getDimension())
                satellite = null;

            return new ContainerOreMappingSatellite((SatelliteOreMapping) satellite, player.inventory);
        }
        // Delegate every non-AR gui id to the libVulpes handler. Both handlers
        // were registered on AdvancedRocketry.instance and Forge keeps only the
        // last one (this AR handler), so without this delegation the libVulpes
        // gui ids opened on AdvancedRocketry.instance — e.g. the ItemStationChip
        // button re-open (MODULARFULLSCREEN) — resolve to null and the GUI never
        // opens. NB: this only covers libVulpes' own enum (MODULAR..MODULARFULLSCREEN,
        // ordinals 0-3); an out-of-range id like SatelliteOreMapping.java:69's
        // hardcoded 100 still maps to nothing (separate, pre-existing no-op). See C010.
        return LIBVULPES.getServerGuiElement(ID, player, world, x, y, z);
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world,
                                      int x, int y, int z) {

        if (x == -1 && y < -1) {
            ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);

            //If there is latency or some desync odd things can happen so check for that
            if (stack.isEmpty() || !(stack.getItem() instanceof IModularInventory)) {
                return null;
            }
        }

        if (ID == guiId.OreMappingSatellite.ordinal()) {

            SatelliteBase satellite = DimensionManager.getInstance().getSatellite(y);

            if (!(satellite instanceof SatelliteOreMapping) || satellite.getDimensionId() != world.provider.getDimension())
                satellite = null;

            return new GuiOreMappingSatellite((SatelliteOreMapping) satellite, player);
        }
        // Delegate every non-AR gui id to the libVulpes handler (see the server
        // side above for the caveat about ids outside libVulpes' 0-3 enum).
        // Fixes the ItemStationChip button re-open. See C010.
        return LIBVULPES.getClientGuiElement(ID, player, world, x, y, z);
    }

    public enum guiId {
        RocketBuilder,
        BlastFurnace,
        OreMappingSatellite,
        StationChip
    }
}