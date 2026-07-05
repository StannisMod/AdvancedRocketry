package zmaster587.advancedRocketry.tile;

import java.util.LinkedList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;

/**
 * Advanced Flight Computer — the block that marks an assembled craft as a
 * <em>tier-2 movable ship</em> rather than a tier-1 rocket.
 *
 * <p>The launch-pad assembler decides a craft's tier from its content: a build
 * that contains an Advanced Flight Computer is assembled into a walk-on, physics
 * driven ship; without one it stays an ordinary rocket. Naming is deliberately
 * distinct from the Guidance Computer and other on-board computers so the two are
 * never confused in code or GUI.</p>
 *
 * <p>This is the pure skeleton: it registers, renders and opens an (empty) modular
 * GUI, but carries no flight logic yet. Pilot-input reading and the flight-control
 * wiring land in a later phase; the ship-assembly bridge to a rigid-body physics
 * mod lives entirely in the optional integration module and is reached only when
 * that mod is installed, so this class never depends on it.</p>
 */
public class TileAdvancedFlightComputer extends TileEntity implements IModularInventory {

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        // Placeholder: flight-control modules are added here in a later phase.
        return new LinkedList<>();
    }

    @Override
    public String getModularInventoryName() {
        return AdvancedRocketryBlocks.blockAdvancedFlightComputer.getLocalizedName();
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }
}
