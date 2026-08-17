package zmaster587.advancedRocketry.tile.heat;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;

/**
 * A run of coolant pipe: how a reactor that cannot be air-cooled reaches the rest of the ship's
 * thermal system.
 * <p>
 * A pipe is a small amount of thermal mass and a transport limit, and that is the whole block. The
 * mass is not incidental — a long enough run is a heat sink in its own right, which is what lets a
 * ship survive a burst it could never radiate away in the moment.
 */
public class TileHeatPipe extends TileHeatLoopBlock {

    @Override
    public int getHeatCapacity() {
        if (!HeatNetwork.enabled())
            return 0;
        return Math.max(0, ARConfiguration.getCurrentConfig().shipHeatPipeCapacity);
    }
}
