package zmaster587.advancedRocketry.tile.infrastructure;


import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.tile.hatch.TileSatelliteHatch;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.util.INetworkMachine;
import zmaster587.libVulpes.util.ZUtils.RedstoneState;

import java.util.List;

public class TileRocketUnloader extends TileRocketLoader implements IInfrastructure, ITickable, IButtonInventory, INetworkMachine {


    public TileRocketUnloader() {
        super();
    }

    public TileRocketUnloader(int size) {
        super(size);
        inventory.setCanInsertSlot(0, false);
        inventory.setCanInsertSlot(1, false);
        inventory.setCanInsertSlot(2, false);
        inventory.setCanInsertSlot(3, false);
        inventory.setCanExtractSlot(0, true);
        inventory.setCanExtractSlot(1, true);
        inventory.setCanExtractSlot(2, true);
        inventory.setCanExtractSlot(3, true);
    }

    @Override
    public String getModularInventoryName() {
        return "tile.loader.2.name";
    }


    @Override
    public void update() {
        if (world.isRemote || rocket == null)
            return;

        // Throttle: only try to move items every TRANSFER_INTERVAL_TICKS
        if (transferCooldown > 0) {
            transferCooldown--;
            return;
        }

        boolean isAllowedToOperate = (inputstate == RedstoneState.OFF ||
                isStateActive(inputstate, getStrongPowerForSides(world, getPos())));

        IItemHandler ownHandler = getOwnItemHandler();
        if (ownHandler == null || ownHandler.getSlots() == 0) {
            // No destination handler: consider rocket not empty (no "done unloading" signal)
            setRedstoneState(false);
            return;
        }

        List<TileEntity> tiles = rocket.storage.getInventoryTiles();
        boolean rocketIsEmpty = true;

        outer:
        for (TileEntity tile : tiles) {
            if (tile instanceof TileGuidanceComputer || tile instanceof TileSatelliteHatch)
                continue;

            IItemHandler rocketHandler = getItemHandler(tile);
            if (rocketHandler == null || rocketHandler.getSlots() == 0)
                continue;

            int rocketSlots = rocketHandler.getSlots();

            for (int rocketSlot = 0; rocketSlot < rocketSlots; rocketSlot++) {
                ItemStack rocketStack = rocketHandler.getStackInSlot(rocketSlot);

                if (!rocketStack.isEmpty()) {
                    rocketIsEmpty = false;
                }

                if (rocketStack.isEmpty())
                    continue;

                // If we are not allowed to operate, we only care about rocketIsEmpty for redstone
                if (!isAllowedToOperate) {
                    continue;
                }

                // Limit per-operation transfer, but DO NOT assume anything about slot max size
                int maxToMove = Math.min(MAX_TRANSFER_PER_OPERATION, rocketStack.getCount());
                if (maxToMove <= 0)
                    continue;

                // Simulate extraction from rocket
                ItemStack simulatedExtract = rocketHandler.extractItem(rocketSlot, maxToMove, true);
                if (simulatedExtract.isEmpty())
                    continue;

                // Simulate insertion into our own inventory
                ItemStack simulatedRemainder = ItemHandlerHelper.insertItem(ownHandler, simulatedExtract, true);
                int accepted = simulatedExtract.getCount() - simulatedRemainder.getCount();
                if (accepted <= 0)
                    continue;

                // Actually extract exactly what will fit
                ItemStack actuallyExtracted = rocketHandler.extractItem(rocketSlot, accepted, false);
                if (actuallyExtracted.isEmpty())
                    continue;

                // Actually insert into our inventory
                ItemStack remainder = ItemHandlerHelper.insertItem(ownHandler, actuallyExtracted, false);

                // Last-resort fallback for misbehaving mods: try to put remainder back
                if (!remainder.isEmpty()) {
                    ItemHandlerHelper.insertItem(rocketHandler, remainder, false);
                    // Same note: if that still leaves items, they're from a broken handler.
                }

                transferCooldown = TRANSFER_INTERVAL_TICKS;
                markDirty();
                tile.markDirty();
                break outer; // only one transfer per operation
            }
        }

        // Redstone: ON when rocketIsEmpty (unloading done)
        setRedstoneState(rocketIsEmpty);
    }


}
