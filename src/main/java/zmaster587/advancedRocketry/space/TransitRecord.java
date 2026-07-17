package zmaster587.advancedRocketry.space;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The durable snapshot of one in-flight tier-2 ship, so a hyperspace jump survives a restart (the
 * hyperspace WORLD is ephemeral — wiped on restart — so the ship must be reconstructed from this record).
 *
 * <p>Carries the LOGICAL flight state ({@code position} reached so far, {@code target},
 * {@code arrivalTick}/{@code lastTicked} on the persist-safe world-time clock, {@code speed}), the aboard
 * {@code crew} UUIDs (for the {@code crew-online} offline-progress gate + reseat), and the packed ship
 * {@code snapshot} — an {@link StorageChunk} NBT re-cut from the parked hyperspace blocks at save points.
 * A restored record advances logically; its snapshot is unpacked into hyperspace only when an aboard
 * player logs in mid-transit, or pasted into the target cell on completion.</p>
 *
 * <p>The {@code shipId} is the AR ship UUID string (the {@link ShipTransitManager} transit key). Immutable
 * value; {@code snapshot} may be {@code null} when no block snapshot has been cut yet.</p>
 */
public final class TransitRecord {

    public final String shipId;
    public final GalacticCoord position;
    public final GalacticCoord target;
    public final long arrivalTick;
    public final long lastTicked;
    public final long speed;
    public final List<UUID> crew;
    /** The packed ship as {@link StorageChunk} NBT, or {@code null} if none has been cut. */
    public final NBTTagCompound snapshot;

    public TransitRecord(String shipId, GalacticCoord position, GalacticCoord target, long arrivalTick,
                         long lastTicked, long speed, List<UUID> crew, NBTTagCompound snapshot) {
        this.shipId = shipId;
        this.position = position;
        this.target = target;
        this.arrivalTick = arrivalTick;
        this.lastTicked = lastTicked;
        this.speed = speed;
        this.crew = crew == null ? new ArrayList<UUID>() : new ArrayList<>(crew);
        this.snapshot = snapshot;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("shipId", shipId);
        nbt.setLong("arrivalTick", arrivalTick);
        nbt.setLong("lastTicked", lastTicked);
        nbt.setLong("speed", speed);

        NBTTagCompound posC = new NBTTagCompound();
        position.writeToNBT(posC);
        nbt.setTag("pos", posC);

        NBTTagCompound tgtC = new NBTTagCompound();
        target.writeToNBT(tgtC);
        nbt.setTag("tgt", tgtC);

        NBTTagList crewList = new NBTTagList();
        for (UUID id : crew) {
            crewList.appendTag(new NBTTagString(id.toString()));
        }
        nbt.setTag("crew", crewList);

        if (snapshot != null) {
            nbt.setTag("snapshot", snapshot);
        }
        return nbt;
    }

    public static TransitRecord readFromNBT(NBTTagCompound nbt) {
        List<UUID> crew = new ArrayList<>();
        NBTTagList crewList = nbt.getTagList("crew", 8); // 8 = NBTTagString
        for (int i = 0; i < crewList.tagCount(); i++) {
            try {
                crew.add(UUID.fromString(crewList.getStringTagAt(i)));
            } catch (IllegalArgumentException bad) {
                // drop a corrupt crew id rather than fail the whole record
            }
        }
        NBTTagCompound snapshot = nbt.hasKey("snapshot") ? nbt.getCompoundTag("snapshot") : null;
        return new TransitRecord(
                nbt.getString("shipId"),
                GalacticCoord.readFromNBT(nbt.getCompoundTag("pos")),
                GalacticCoord.readFromNBT(nbt.getCompoundTag("tgt")),
                nbt.getLong("arrivalTick"),
                nbt.getLong("lastTicked"),
                nbt.getLong("speed"),
                crew,
                snapshot);
    }
}
