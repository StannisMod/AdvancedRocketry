package zmaster587.advancedRocketry.tile.multiblock;

import net.minecraft.block.state.IBlockState;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.libVulpes.api.LibVulpesBlocks;
import zmaster587.libVulpes.block.BlockMeta;
import zmaster587.libVulpes.tile.multiblock.TileMultiBlock;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;

public class TileWarpCore extends TileMultiBlock implements ITickable {
    public static final Object[][][] structure = {
            {{"blockWarpCoreRim", "blockWarpCoreRim", "blockWarpCoreRim"},
                    {"blockWarpCoreRim", 'I', "blockWarpCoreRim"},
                    {"blockWarpCoreRim", "blockWarpCoreRim", "blockWarpCoreRim"}},

            {{null, new BlockMeta(LibVulpesBlocks.blockStructureBlock), null},
                    {new BlockMeta(LibVulpesBlocks.blockStructureBlock), "blockWarpCoreCore", new BlockMeta(LibVulpesBlocks.blockStructureBlock)},
                    {null, new BlockMeta(LibVulpesBlocks.blockStructureBlock), null}},

            {{"blockWarpCoreRim", 'c', "blockWarpCoreRim"},
                    {"blockWarpCoreRim", "blockWarpCoreCore", "blockWarpCoreRim"},
                    {"blockWarpCoreRim", "blockWarpCoreRim", "blockWarpCoreRim"}},

    };

    private SpaceStationObject station;
    private boolean inventoryDirty;

    private SpaceStationObject getSpaceObject() {
        if (station == null && world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId) {
            ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(pos);
            if (spaceObject instanceof SpaceStationObject)
                station = (SpaceStationObject) spaceObject;
        }
        return station;
    }

    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return pos.compareTo(this.pos) == 0;
    }

    @Override
    public void onInventoryUpdated() {
        if (world == null || world.isRemote)
            return;

        inventoryDirty = true;
    }

    @Override
    public void update() {
        if (world == null || world.isRemote || !inventoryDirty)
            return;

        inventoryDirty = false;

        if (itemInPorts.isEmpty()) {
            attemptCompleteStructure(world.getBlockState(pos));
        }

        SpaceStationObject obj = getSpaceObject();
        if (obj == null)
            return;

        int perDilithium = ARConfiguration.getCurrentConfig().fuelPointsPerDilithium;
        if ((obj.getMaxFuelAmount() - obj.getFuelAmount()) < perDilithium)
            return;

        for (IInventory inv : itemInPorts) {
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (stack.isEmpty())
                    continue;

                ItemStack test = stack.copy();
                test.setCount(1);

                if (!ZUtils.isItemInOreDict(test, "gemDilithium"))
                    continue;

                while (!inv.getStackInSlot(i).isEmpty()
                        && (obj.getMaxFuelAmount() - obj.getFuelAmount()) >= perDilithium) {

                    int amt = obj.addFuel(perDilithium);
                    int toConsume = amt / perDilithium;

                    if (toConsume <= 0)
                        return;

                    inv.decrStackSize(i, toConsume);
                    inv.markDirty();
                }

                if ((obj.getMaxFuelAmount() - obj.getFuelAmount()) < perDilithium)
                    return;
            }
        }
    }

    @Override
    public String getMachineName() {
        return AdvancedRocketryBlocks.blockWarpCore.getLocalizedName();
    }

    @Override
    @Nonnull
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(pos.add(-2, -2, -2), pos.add(2, 2, 2));
    }
}