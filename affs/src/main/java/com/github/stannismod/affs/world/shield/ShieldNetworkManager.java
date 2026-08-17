package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.AdvancedForceFieldSystem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.apache.logging.log4j.Logger;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkNode;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkManager;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

import java.util.List;

/**
 * The shield domain. Everything structural — the connected-component graph, the max-flow solve, the
 * priority tiers, the per-tick statistics — lives in the shared subsystem-network primitive; what is
 * genuinely shield-specific is the resistance bias a console sets, and that is all this adds.
 */
public final class ShieldNetworkManager {

    /** The domain handle. Shield nodes register under it; nothing else joins these graphs. */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("Shield") {
        @Override
        public SubsystemNetworkState newState() {
            return new ShieldNetworkState();
        }

        @Override
        public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers,
                                       List<ISubsystemNetworkNode> members) {
            if (!(state instanceof ShieldNetworkState) || controllers == null || controllers.isEmpty()) {
                return;
            }
            for (ISubsystemNetworkController controller : controllers) {
                if (controller instanceof IShieldNetworkController) {
                    ((ShieldNetworkState) state).setShieldEnergyResistanceBias(
                            ((IShieldNetworkController) controller).getShieldEnergyResistanceBias());
                    return;
                }
            }
        }

        @Override
        public Logger getLogger() {
            return AdvancedForceFieldSystem.LOG;
        }
    };

    private ShieldNetworkManager() {
    }

    /**
     * The shield network at this position, as the shield's own state type.
     * <p>
     * Kept where a plain domain-supplying forwarder was not: this one narrows the shared state to
     * the subclass that carries the resistance bias, so every caller does not repeat the same cast.
     * Callers that only need to name the domain — marking the topology dirty, registering a node —
     * say {@link #DOMAIN} at the call site instead, exactly as the ventilation domain does.
     */
    public static ShieldNetworkState getState(World world, BlockPos pos) {
        SubsystemNetworkState state = SubsystemNetworkManager.getState(DOMAIN, world, pos);
        return state instanceof ShieldNetworkState ? (ShieldNetworkState) state : null;
    }

    public static void setShieldEnergyResistanceBias(World world, BlockPos pos, double bias) {
        if (world == null || world.isRemote) {
            return;
        }
        ShieldNetworkState state = getState(world, pos);
        if (state != null) {
            state.setShieldEnergyResistanceBias(bias);
        }
    }
}
