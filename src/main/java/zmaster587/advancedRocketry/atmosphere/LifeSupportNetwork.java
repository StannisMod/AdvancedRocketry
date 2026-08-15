package zmaster587.advancedRocketry.atmosphere;

import zmaster587.advancedRocketry.subsystem.network.SubsystemNetworkDomain;

/**
 * The ventilation domain: a central regeneration plant, the zones it serves, and the ducts between
 * them, over the shared subsystem-network primitive.
 * <p>
 * <b>The commodity is REGENERATION WORK, and its unit is µatm·blocks per tick</b> — an absolute
 * amount of carbon dioxide converted, not a partial pressure. The distinction is the whole reason
 * this tier exists: a partial pressure is a statement about one room, so a plant quoting one could
 * not say what it is worth to a ship of rooms, and a large cabin would be scrubbed as fast as a
 * cupboard on the same number. Multiplying by the zone's volume makes the quantity comparable
 * across rooms, which is what lets a duct's capacity mean "supports this much crew" the way D127-5
 * intends.
 * <p>
 * Nothing here re-implements a network. The graph, the max-flow solve, the priority tiers and the
 * statistics are the shared primitive's; this class is an identity and a unit.
 */
public final class LifeSupportNetwork {

    /**
     * The domain handle. Ventilation nodes register under it, so a duct and a shield cable laid
     * through the same wall never join one graph.
     */
    public static final SubsystemNetworkDomain DOMAIN = new SubsystemNetworkDomain("LifeSupport") {
    };

    /** The network solves every tick; the config states rates per second, as the rest of the tier does. */
    public static final int TICKS_PER_SECOND = 20;

    private LifeSupportNetwork() {
    }

    /** A per-second rate as the per-tick amount the solver deals in. */
    public static int perTick(int ratePerSecond) {
        return Math.max(0, ratePerSecond / TICKS_PER_SECOND);
    }

    /**
     * Absolute regeneration work for a partial pressure in a zone of this size.
     * Clamped to int: a zone big enough to overflow this is not a room, it is a bug.
     */
    public static int absolute(int partialPressure, int zoneVolume) {
        long work = (long) Math.max(0, partialPressure) * Math.max(1, zoneVolume);
        return (int) Math.min(Integer.MAX_VALUE, work);
    }

    /** The inverse: what this much work amounts to as a partial pressure in a zone of this size. */
    public static int partialPressure(int absoluteWork, int zoneVolume) {
        return Math.max(0, absoluteWork) / Math.max(1, zoneVolume);
    }
}
