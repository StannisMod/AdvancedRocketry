package zmaster587.advancedRocketry.api.event;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * The moment a tier-2 craft becomes — or stops being — a ship the physics engine has named.
 *
 * <h2>Why an event when a predicate already exists</h2>
 *
 * <p>Asking "is this craft named right now" is the right question for a machine deciding whether to
 * do its work this tick, and it is enough for every such gate. It is not enough for anything that has
 * to happen <b>once, at the moment naming happens</b>: an authoritative recompute of derived state, a
 * registration built at first contact and resolved by id thereafter, a durable record minted at
 * birth. Finding an edge by watching a level means polling, and a subsystem that polls ends up with
 * its own private idea of which tick the craft started existing on. There are enough of those
 * subsystems that each private answer would be a future disagreement about what the craft was when it
 * was born.</p>
 *
 * <h2>What "named" means here</h2>
 *
 * <p>A craft's blocks and its ship object do not arrive together. The blocks are loaded and their
 * tiles tick before the physics engine has a ship for them, and in that window every coordinate they
 * hold is a shipyard address rather than anywhere a player can stand. <b>Named</b> is the later
 * moment: the engine holds a live ship object for this identity, so the craft can be resolved by id,
 * asked for its pose, and written to. That is exactly the level the position-gate answers, and this
 * event is its edge.</p>
 *
 * <h2>The contract — read this before writing a handler</h2>
 *
 * <ul>
 *   <li><b>Server only.</b> Posted from the server's ship manager; nothing posts these on the client,
 *       so a client-side subscriber will simply never fire. A client learns about ships from the
 *       engine's own index packets.</li>
 *   <li><b>The server game thread, inside the world tick.</b> Never the physics thread. A handler may
 *       therefore touch the world, read tiles and send packets, exactly as in any other world-tick
 *       callback. Work that belongs on the physics thread must be handed to it explicitly, the same
 *       way anything else crossing that boundary is.</li>
 *   <li><b>After the fact, and not cancellable.</b> By the time a handler runs, the ship is fully
 *       registered (or fully gone). There is nothing to veto.</li>
 *   <li><b>A handler must not block, and must not throw.</b> It runs inside the tick that every other
 *       ship in the world is waiting on, and Forge's bus re-throws whatever a handler throws — so an
 *       exception here leaves the world tick with nothing between it and the server loop. That is not
 *       special to this event; it is how every Forge event behaves. It is written down because this
 *       one fires from inside the ship manager, where the blast radius is the whole server rather
 *       than one feature, so a handler that can fail catches its own failure and logs it.</li>
 *   <li><b>A handler may queue ship spawns, loads and unloads.</b> These events are published after
 *       the manager has finished draining its queues for the tick, so a queue touched by a handler is
 *       acted on next tick rather than corrupting the drain in progress. This is the reason the
 *       publication is deferred to the end of the pass instead of being posted at each site.</li>
 *   <li><b>Ordering within a tick is the order the transitions happened</b> — deregistrations from the
 *       destroy pass, then spawns, then loads, then unloads. A craft that is re-registered in one tick
 *       (a crossing that lands where its own blockless remnant still sat) therefore produces the
 *       {@link Cause#DESTROYED} before the {@link Cause#PASTED}, which is the truth of what
 *       happened.</li>
 * </ul>
 *
 * <h2>Named is not the same as simulated</h2>
 *
 * <p>A ship can be named and still not be integrated: the engine only steps a body whose physics has
 * been switched on, and a craft nobody has flown has not switched it on. A handler that arms on this
 * event and then waits for the craft to MOVE may wait forever, and that is not a fault in the
 * event.</p>
 */
public class ShipLifecycleEvent extends Event {

    /**
     * Which transition this is. Carried as a field rather than as a separate event type per case
     * because most consumers want several of them and would otherwise subscribe several times, while
     * the consumers that want exactly one (a durable record minted only for a genuinely new build)
     * compare one field.
     */
    public enum Cause {
        /** A new craft, built here and assembled for the first time. Nothing of it existed before. */
        ASSEMBLED,
        /**
         * A craft that already existed, re-registered around blocks that were cut out somewhere else
         * and pasted here — a crossing, a transit, a reposition. The vessel is the same one; only its
         * registration is new.
         */
        PASTED,
        /**
         * A craft that was already registered and is now loaded again — the world came back, or a
         * player came close enough for the engine to want it live. Nothing about the craft changed.
         */
        LOADED,
        /**
         * The ship object is gone but the craft still exists: it is registered, its blocks are on
         * disk, and it will be back. Live state keyed on it must be dropped; anything durable must
         * not.
         */
        UNLOADED,
        /**
         * The craft ceased to exist as a ship — deconstructed back into the world, or a blockless
         * registration collected. Durable records keyed on it are now about nothing.
         */
        DESTROYED
    }

    /** The world the ship belongs to. Always a server world. */
    public final World world;

    /** The physics engine's identity for this ship. Stable while the registration lives. */
    public final UUID shipUuid;

    /**
     * Advanced Rocketry's DURABLE id for this craft, or {@code null} for a craft AR does not own (and
     * for a genuinely new build that has not yet been given one by its flight computer).
     *
     * <p>Unlike {@link #shipUuid} this one survives a re-assembly, so it is what names the same vessel
     * across a crossing, a restart and a re-registration. A consumer holding state that must follow
     * the VESSEL rather than the registration keys on this.</p>
     */
    @Nullable
    public final UUID durableId;

    /** Which transition this is; see {@link Cause}. */
    public final Cause cause;

    protected ShipLifecycleEvent(World world, UUID shipUuid, @Nullable UUID durableId, Cause cause) {
        this.world = world;
        this.shipUuid = shipUuid;
        this.durableId = durableId;
        this.cause = cause;
    }

    /**
     * A ship exists and can be resolved by id from now on. {@link #cause} is one of
     * {@link Cause#ASSEMBLED}, {@link Cause#PASTED} or {@link Cause#LOADED}.
     */
    public static class ShipNamed extends ShipLifecycleEvent {
        public ShipNamed(World world, UUID shipUuid, @Nullable UUID durableId, Cause cause) {
            super(world, shipUuid, durableId, cause);
        }
    }

    /**
     * A ship can no longer be resolved by id. {@link #cause} is {@link Cause#UNLOADED} (it will be
     * back) or {@link Cause#DESTROYED} (it will not).
     *
     * <p>Designed with its counterpart rather than after it, because every consumer that arms on the
     * naming edge holds something it then has to let go of, and the two halves must agree on what a
     * craft's identity is.</p>
     */
    public static class ShipUnnamed extends ShipLifecycleEvent {
        public ShipUnnamed(World world, UUID shipUuid, @Nullable UUID durableId, Cause cause) {
            super(world, shipUuid, durableId, cause);
        }
    }
}
