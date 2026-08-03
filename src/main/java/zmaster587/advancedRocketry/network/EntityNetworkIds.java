package zmaster587.advancedRocketry.network;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.EntityRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The one owner of this jar's entity spawn-network-id space.
 *
 * <p>A modded entity does not reach a client by registry name. The server writes
 * {@code (modId, per-mod network id)} into the spawn message and the client resolves that pair back
 * to a class by taking the FIRST registration under that mod carrying the id -- an insertion-ordered
 * scan with no uniqueness check anywhere in Forge. The id space is therefore scoped to the MOD
 * CONTAINER, and this jar hosts three code bases inside one container: this mod, the vendored shield
 * mod and the vendored physics engine. Two of them picking the same number is not rejected: the
 * loser is built as the winner's class and then fed the sender's data-watcher entries by slot index,
 * and the first read of a wrongly-typed slot takes the client down -- in a different class, on a
 * later tick, with nothing pointing back at the registration that caused it.</p>
 *
 * <p><b>Why a declared space and not a counter.</b> A counter is not what makes this safe. The
 * physics engine's registration already used one and restarted it at zero in a space it did not own;
 * that is exactly what collided, and because no number appeared at the call site there was nothing
 * to check the host's list against. Worse, a call-order counter makes the id depend on execution
 * order, so the day one registration becomes conditional -- side-only, config-gated, gated on another
 * mod -- the two sides allocate differently and every packet decodes as the wrong entity, just as
 * silently. Here the space is DECLARED: the index of a name in {@link #SPACE} is its id, no call
 * site carries a number, and an entity that is not declared cannot be registered at all.</p>
 *
 * <p>Adding an entity: append its name to the end of {@link #SPACE}. Never reorder, never insert in
 * the middle, never reuse a removed slot -- a retired entry stays where it is (its number stays
 * burned) so no id ever changes meaning. The ids are runtime-only: NBT and the registry use names,
 * and both sides of a connection are this same jar, so appending is wire-safe.</p>
 */
public final class EntityNetworkIds {

    /**
     * The space, in id order: index 0 is network id 0. Names are compared case-insensitively, so
     * declare them however the registration reads and let {@link #index} normalise.
     */
    private static final String[] SPACE = {
            // this mod
            "advancedrocketry:mountDummy",
            "advancedrocketry:rocket",
            "advancedrocketry:laserNode",
            "advancedrocketry:deployedRocket",
            "advancedrocketry:ARAbductedItem",
            "advancedrocketry:ARPlanetUIItem",
            "advancedrocketry:ARPlanetUIButton",
            "advancedrocketry:ARStarUIButton",
            "advancedrocketry:ARSpaceElevatorCapsule",
            "advancedrocketry:ARHoverCraft",
            // the vendored shield mod
            "affs:laser_bolt",
            // the vendored physics engine
            "valkyrienskies:entity_mountable",
            "valkyrienskies:entity_mountable_chair",
    };

    private static final Map<String, Integer> IDS = index(SPACE);

    private EntityNetworkIds() {
    }

    /**
     * Registers a mod entity on its declared network id. Registering THROUGH the space's owner is
     * what keeps the numbering unforgeable: a call site never names an id, and an entity nobody
     * declared cannot reach {@code EntityRegistry} at all.
     *
     * <p>Arguments after the name are Forge's, unchanged: the tracking name, the owning mod
     * instance, the tracking range in blocks, the tracker update frequency in ticks, and whether the
     * tracker sends velocity.</p>
     */
    public static void register(ResourceLocation name, Class<? extends Entity> entity,
                               String trackingName, Object mod, int trackingRange,
                               int updateFrequency, boolean sendsVelocityUpdates) {
        EntityRegistry.registerModEntity(name, entity, trackingName, of(name), mod, trackingRange,
                updateFrequency, sendsVelocityUpdates);
    }

    /**
     * The network id declared for an entity registry name.
     *
     * @throws IllegalStateException if the name is not declared -- a registration that would
     *                               otherwise have silently taken a number belonging to something
     *                               else fails at load instead.
     */
    public static int of(ResourceLocation name) {
        Integer id = IDS.get(key(name.toString()));
        if (id == null) {
            throw new IllegalStateException("entity " + name + " has no declared network id."
                    + " Every entity registered under this mod container shares ONE id space, so it"
                    + " must be declared in EntityNetworkIds before it can be registered. Append it"
                    + " to the end of the space; never reuse or renumber an existing entry.");
        }
        return id;
    }

    /** The declared space as name->id, lower-cased names, unmodifiable. */
    public static Map<String, Integer> declared() {
        return IDS;
    }

    /**
     * Builds the name->id index, rejecting a name declared twice. Exposed rather than private so the
     * rule "a duplicate declaration does not load" can be exercised on a hand-built space, without
     * having to corrupt the live one.
     */
    public static Map<String, Integer> index(String[] space) {
        Map<String, Integer> ids = new HashMap<>();
        for (int id = 0; id < space.length; id++) {
            Integer clash = ids.put(key(space[id]), id);
            if (clash != null) {
                throw new IllegalStateException("entity " + space[id] + " is declared twice in the"
                        + " network id space, as " + clash + " and " + id + "; two entities sharing"
                        + " an id are indistinguishable on the wire and a client builds the first"
                        + " one for both");
            }
        }
        return Collections.unmodifiableMap(ids);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
