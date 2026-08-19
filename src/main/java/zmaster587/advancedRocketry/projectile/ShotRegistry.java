package zmaster587.advancedRocketry.projectile;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import zmaster587.advancedRocketry.api.projectile.ShotEndReason;
import zmaster587.advancedRocketry.api.projectile.ShotSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every shot in flight in one world, and the only thing that owns one.
 *
 * <h3>Per world, and that is the whole of the isolation</h3>
 * <p>Two shots in different worlds cannot interact, and they cannot because they are held by two
 * different objects with no reference to each other — not because anything compares dimension ids on
 * the way past. A shot fired in a space cell and a shot fired on a planet are stepped by their own
 * world's tick, tested against their own world's blocks, and stored in their own world's save.</p>
 *
 * <h3>Why a WorldSavedData</h3>
 * <p>The state needs an owner whose lifetime is the world's: attached when the world loads, written
 * when it saves, gone when it unloads. A static map keyed by dimension would be all three of the
 * things that make a static a defect — mutable, depended upon, and written by more than one place —
 * and would additionally lose every round in flight across a restart, which for a weapon with a
 * minute of flight time is a visible lie rather than a technicality.</p>
 */
public class ShotRegistry extends WorldSavedData {

    public static final String DATA_NAME = "advancedRocketryShots";

    /** How many recently ended shots keep their reason. Enough for a burst; bounded so it cannot grow. */
    private static final int ENDINGS_REMEMBERED = 64;

    private final Map<Long, Shot> shots = new LinkedHashMap<>();

    /**
     * How recently ended shots ended. Not world state and not saved — the oldest is dropped once the
     * map is full, so a caller that waits too long is told nothing rather than told a guess.
     */
    private final Map<Long, Ending> endings = new LinkedHashMap<Long, Ending>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Ending> eldest) {
            return size() > ENDINGS_REMEMBERED;
        }
    };

    private long nextId = 1L;

    /**
     * The next identity an impact declared in this world will be remembered by.
     *
     * <p>Minted HERE rather than by the shot that declares the impact, and that is the whole point:
     * an identity built out of a shot's own id and a counter has to reserve a field for each, and a
     * counter that outgrows its field walks into the neighbouring one. A round that bored for a few
     * hundred ticks then minted the identities a LATER round was going to mint, and the later round's
     * impacts were refused as duplicates of impacts it never made — it crossed a stone wall spending
     * nothing, because a refusal hands the budget back whole.</p>
     *
     * <p>One counter per world cannot do that: it is handed out once and never again. It is
     * deliberately NOT reset by {@link #clear()} — the dedup memory that reads these identities
     * outlives any one scenario, so re-minting from one would be re-creating the collision by hand.</p>
     */
    private long nextImpactId = 1L;

    public ShotRegistry() {
        super(DATA_NAME);
    }

    public ShotRegistry(String name) {
        super(name);
    }

    /** The registry of THIS world. Server side: a client has no shots because it simulates none. */
    public static ShotRegistry get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        ShotRegistry data = (ShotRegistry) storage.getOrLoadData(ShotRegistry.class, DATA_NAME);
        if (data == null) {
            data = new ShotRegistry();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /**
     * Admit a new shot and answer its id, or {@code -1} when the world is already carrying as many as
     * it is allowed. Refusing is deliberate: evicting somebody else's round to make room for this one
     * would make a burst of cheap fire a way to delete incoming fire.
     */
    public long add(ShotSpec spec, int maxShots) {
        if (shots.size() >= maxShots) {
            return -1L;
        }
        long id = nextId++;
        shots.put(id, new Shot(id, spec));
        markDirty();
        return id;
    }

    /**
     * An identity for one declared impact. Persisted with the registry, so a restart does not start
     * handing out identities the dedup memory may still be holding.
     *
     * <h3>It counts up and never cycles, and cycling is what it is avoiding</h3>
     * <p>What uniqueness is actually needed FOR is the dedup memory's window: an identity is
     * remembered for a bounded number of ticks and then forgotten, so what this must not do is repeat
     * inside that window. Reusing a number the service has already let go of is harmless. The scheme
     * this replaced failed at exactly that scale — it repeated within seconds of one round's flight —
     * which is why the answer here is a counter that never comes back round rather than a wider one
     * that comes back round later.</p>
     *
     * <p>Exhaustion is not a practical bound: at the substrate's own ceiling — every slot of
     * {@code maxShotsPerWorld} occupied, every round boring its maximum crossings, every tick — the
     * default configuration spends a {@code long} in the order of ten million years. A configuration
     * that raises the shot cap to its own maximum would need a couple of years of continuously
     * saturated fire, which is a server that has already fallen over for other reasons.</p>
     *
     * <p><b>If it ever did overflow</b> it would land in the negatives, where hand-declared probe
     * identities live (see the {@code /artest damage impact} verb). That is stated so it is a known
     * neighbour rather than a surprise; it is not guarded against, because reaching it means the
     * count above was wrong by a factor nothing here can produce.</p>
     */
    public long nextImpactId() {
        long id = nextImpactId++;
        markDirty();
        return id;
    }

    public Shot get(long id) {
        return shots.get(id);
    }

    public void remove(long id) {
        if (shots.remove(id) != null) {
            markDirty();
        }
    }

    /**
     * Take a shot out of the air and say why. The reason is kept for a while after the shot itself is
     * gone: a weapon asks about its round after the fact, and "it is not in the registry" cannot tell
     * a hit from a round that timed out half a kilometre short.
     */
    void end(long id, ShotEndReason reason, Vec3d where) {
        remove(id);
        endings.put(id, new Ending(reason, where));
    }

    /**
     * How the shot with this id ended, or null if it is still up or was forgotten. Deliberately NOT
     * persisted: it is an answer to a question asked seconds later, not world state.
     */
    public Ending endingOf(long id) {
        return endings.get(id);
    }

    /**
     * How a shot ended: the reason, and the WORLD point it ended at. The point is world-frame even
     * when the thing it hit was a ship's block, which lives millions of blocks away in a shipyard
     * subspace — a weapon showing an impact where its round actually stopped needs the place the
     * player can see, not the address the block is filed under.
     */
    public static final class Ending {
        private final ShotEndReason reason;
        private final Vec3d point;

        private Ending(ShotEndReason reason, Vec3d point) {
            this.reason = reason;
            this.point = point;
        }

        public ShotEndReason getReason() {
            return reason;
        }

        /** WORLD point, never null: a shot that ended is always somewhere. */
        public Vec3d getPoint() {
            return point;
        }
    }

    /**
     * Drop everything in flight. Not a game action — nothing in the mod calls it. It exists because a
     * shared test server hands one scenario's rounds to the next, and a suite that has to reason
     * about which of them are still up is measuring the harness.
     */
    public void clear() {
        endings.clear();
        if (!shots.isEmpty()) {
            shots.clear();
            markDirty();
        }
    }

    /**
     * A snapshot of the shots to step this tick. A copy, because stepping one can end it and a shot
     * that lands may in future spawn another; iterating the live map would then be a concurrent
     * modification in the middle of somebody's impact.
     */
    List<Shot> snapshot() {
        return new ArrayList<>(shots.values());
    }

    /** Everything currently in flight here. Read-only view for diagnostics, probes and tests. */
    public Collection<Shot> inFlight() {
        return java.util.Collections.unmodifiableCollection(shots.values());
    }

    public int count() {
        return shots.size();
    }

    /**
     * The shot nearest a WORLD point, or null when nothing is in flight. Diagnostics and probes.
     *
     * <p>The world is asked for because a shot drilling a hull is stored in that hull's own frame:
     * comparing its raw coordinates against a world point would rank it by its distance from a
     * shipyard millions of blocks away, and answer plausibly.</p>
     */
    public Shot nearest(net.minecraft.world.World world, Vec3d point) {
        Shot best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (Shot shot : shots.values()) {
            double sq = ShotFrame.worldPosition(world, shot).squareDistanceTo(point);
            if (sq < bestSq) {
                bestSq = sq;
                best = shot;
            }
        }
        return best;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        shots.clear();
        nextId = Math.max(1L, nbt.getLong("nextId"));
        nextImpactId = Math.max(1L, nbt.getLong("nextImpactId"));
        NBTTagList list = nbt.getTagList("shots", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            Shot shot = Shot.readFromNBT(list.getCompoundTagAt(i));
            shots.put(shot.getId(), shot);
            if (shot.getId() >= nextId) {
                // A save whose counter is behind its own contents would hand out an id that is
                // already in flight, and the newcomer would silently replace it in the map.
                nextId = shot.getId() + 1L;
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setLong("nextId", nextId);
        nbt.setLong("nextImpactId", nextImpactId);
        NBTTagList list = new NBTTagList();
        for (Shot shot : shots.values()) {
            list.appendTag(shot.writeToNBT());
        }
        nbt.setTag("shots", list);
        return nbt;
    }
}
