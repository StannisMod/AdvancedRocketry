package zmaster587.advancedRocketry.weapon;

import org.apache.logging.log4j.Logger;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.subsystem.network.ISubsystemNetworkController;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;
import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkState;

import java.util.List;

/**
 * The weapons network: energy shared between guns, and one place to point them all.
 *
 * <h3>The network is a convenience, and nothing depends on it</h3>
 * <p>Every gun works alone. It holds its own energy buffer, picks its own target and fires with no
 * cable, console or network attached — a battery of one is a supported build, not a degraded one.
 * What joining a network buys is what a player would otherwise do by hand: one console aiming a
 * dozen guns at the same thing, and a shared supply that fills the guns that matter first under a
 * deficit. Losing the network loses those conveniences and nothing else, which is why no code path
 * below asks whether a state exists before deciding whether a gun may fire.</p>
 *
 * <h3>The commodity is Forge Energy</h3>
 * <p>Guns are sinks, generators and capacitor banks are sources, and the unit is FE per tick — the
 * same unit the rest of the mod's power is in, so a player wiring a gun into a ship's supply is not
 * learning a second kind of energy.</p>
 */
public final class WeaponNetworkDomain extends SubsystemNetworkDomain {

    public static final WeaponNetworkDomain INSTANCE = new WeaponNetworkDomain();

    private WeaponNetworkDomain() {
        super("Weapon");
    }

    @Override
    public SubsystemNetworkState newState() {
        return new WeaponNetworkState();
    }

    @Override
    public void onComponentRebuilt(SubsystemNetworkState state, List<ISubsystemNetworkController> controllers) {
        if (!(state instanceof WeaponNetworkState)) {
            return;
        }
        // A network with no console left commands nothing. Keeping the last console's target would
        // leave a battery firing at a point nobody can retract, which is the one failure mode a
        // player cannot fix by breaking something.
        if (controllers.isEmpty()) {
            ((WeaponNetworkState) state).clearTarget();
        }
    }

    @Override
    public Logger getLogger() {
        return AdvancedRocketry.logger;
    }
}
