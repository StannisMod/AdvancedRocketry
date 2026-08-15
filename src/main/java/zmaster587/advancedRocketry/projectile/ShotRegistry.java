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
     * Why recently ended shots ended. Not world state and not saved — the oldest is dropped once the
     * map is full, so a caller that waits too long is told nothing rather than told a guess.
     */
    private final Map<Long, ShotEndReason> endings = new LinkedHashMap<Long, ShotEndReason>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ShotEndReason> eldest) {
            return size() > ENDINGS_REMEMBERED;
        }
    };

    private long nextId = 1L;

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
    void end(long id, ShotEndReason reason) {
        remove(id);
        endings.put(id, reason);
    }

    /**
     * Why the shot with this id ended, or null if it is still up or was forgotten. Deliberately NOT
     * persisted: it is an answer to a question asked seconds later, not world state.
     */
    public ShotEndReason endReasonOf(long id) {
        return endings.get(id);
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

    /** The shot nearest a world point, or null when nothing is in flight. Diagnostics and probes. */
    public Shot nearest(Vec3d point) {
        Shot best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (Shot shot : shots.values()) {
            double sq = shot.getPosition().squareDistanceTo(point);
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
        NBTTagList list = new NBTTagList();
        for (Shot shot : shots.values()) {
            list.appendTag(shot.writeToNBT());
        }
        nbt.setTag("shots", list);
        return nbt;
    }
}
