package zmaster587.advancedRocketry.space;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The durable snapshot of one in-flight tier-2 ship, so a hyperspace jump survives a restart (the
 * hyperspace WORLD is ephemeral — wiped on restart — so the ship must be reconstructed from this record).
 *
 * <p>Carries the LOGICAL flight state — the {@code origin} and {@code target} cell names, the
 * {@code distanceBlocks} the flight was priced at and the {@code travelledBlocks} flown so far, plus
 * {@code arrivalTick}/{@code lastTicked} on the persist-safe world-time clock and {@code speed} — the aboard
 * {@code crew} UUIDs (for the {@code crew-online} offline-progress gate + reseat), and the packed ship
 * {@code snapshot} — an {@link StorageChunk} NBT re-cut from the parked hyperspace blocks at save points.
 * A mid-flight state is (origin name, target name, progress) and never a raw absolute position: an
 * absolute is only defined at a stated tick, so a persisted one silently means something else the next
 * time it is read — no stored coordinate may depend on when it was written.
 * A restored record advances logically; its snapshot is unpacked into hyperspace only when an aboard
 * player logs in mid-transit, or pasted into the target cell on completion.</p>
 *
 * <p>The {@code shipId} is the AR ship UUID string (the {@link ShipTransitManager} transit key). Immutable
 * value; {@code snapshot} may be {@code null} when no block snapshot has been cut yet.</p>
 */
public final class TransitRecord {

    public final String shipId;
    /** Where the flight started. Persisted because progress is meaningless without it. */
    public final GalacticCoord origin;
    public final GalacticCoord target;
    /** The whole flight in blocks, as priced through both frames at departure. */
    public final long distanceBlocks;
    /** Blocks flown so far. */
    public final long travelledBlocks;
    public final long arrivalTick;
    public final long lastTicked;
    public final long speed;
    public final List<UUID> crew;
    /** The packed ship as {@link StorageChunk} NBT, or {@code null} if none has been cut. */
    public final NBTTagCompound snapshot;
    /**
     * The hyperspace lane this ship is parked in, or {@code -1} for a transit that holds none.
     *
     * <p>Persisted because hyperspace outlives the server: the ship is still standing in that lane
     * after a restart, and a restore that did not reclaim the index would hand the same lane to the
     * next departure and paste a second ship into the first one.</p>
     */
    public final int laneIndex;
    /**
     * Where the ship's blocks actually landed in that lane, or {@code null} for a transit with no
     * physical ship. The lane's parking position is a pure function of its index, but the assembly
     * anchor is where the re-assembly SEEDED, which is what every later lookup about this ship is
     * keyed on.
     */
    public final BlockPos hyperAnchor;

    public TransitRecord(String shipId, GalacticCoord origin, GalacticCoord target, long distanceBlocks,
                         long travelledBlocks, long arrivalTick, long lastTicked, long speed,
                         List<UUID> crew, NBTTagCompound snapshot) {
        this(shipId, origin, target, distanceBlocks, travelledBlocks, arrivalTick, lastTicked, speed,
                crew, snapshot, -1, null);
    }

    public TransitRecord(String shipId, GalacticCoord origin, GalacticCoord target, long distanceBlocks,
                         long travelledBlocks, long arrivalTick, long lastTicked, long speed,
                         List<UUID> crew, NBTTagCompound snapshot, int laneIndex, BlockPos hyperAnchor) {
        this.shipId = shipId;
        this.origin = origin;
        this.target = target;
        this.distanceBlocks = distanceBlocks;
        this.travelledBlocks = travelledBlocks;
        this.arrivalTick = arrivalTick;
        this.lastTicked = lastTicked;
        this.speed = speed;
        this.crew = crew == null ? new ArrayList<UUID>() : new ArrayList<>(crew);
        this.snapshot = snapshot;
        this.laneIndex = laneIndex;
        this.hyperAnchor = hyperAnchor;
    }

    public NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("shipId", shipId);
        nbt.setLong("arrivalTick", arrivalTick);
        nbt.setLong("lastTicked", lastTicked);
        nbt.setLong("speed", speed);

        nbt.setLong("distance", distanceBlocks);
        nbt.setLong("travelled", travelledBlocks);

        NBTTagCompound orgC = new NBTTagCompound();
        origin.writeToNBT(orgC);
        nbt.setTag("org", orgC);

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
        // Absent rather than -1/0 when there is no lane, so a reader tells "this transit holds no
        // lane" from "lane zero" — index 0 is a real, and the first, lane.
        if (laneIndex >= 0) {
            nbt.setInteger("lane", laneIndex);
        }
        if (hyperAnchor != null) {
            nbt.setLong("hyperAnchor", hyperAnchor.toLong());
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
                GalacticCoord.readFromNBT(nbt.getCompoundTag("org")),
                GalacticCoord.readFromNBT(nbt.getCompoundTag("tgt")),
                nbt.getLong("distance"),
                nbt.getLong("travelled"),
                nbt.getLong("arrivalTick"),
                nbt.getLong("lastTicked"),
                nbt.getLong("speed"),
                crew,
                snapshot,
                nbt.hasKey("lane") ? nbt.getInteger("lane") : -1,
                nbt.hasKey("hyperAnchor") ? BlockPos.fromLong(nbt.getLong("hyperAnchor")) : null);
    }
}
