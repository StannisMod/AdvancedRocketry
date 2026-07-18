package zmaster587.advancedRocketry.space;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The per-player durable record "<i>I am aboard tier-2 ship X, seated at Y</i>", stored in the
 * player's persistent ForgeData compound so it survives a logout and a server restart.
 *
 * <p><b>Why this exists.</b> Nothing else can answer "which ship was this player aboard" once the
 * server has been restarted. {@link ShipLedger} is keyed by SHIP id and carries no crew and no
 * reverse index, so it cannot be searched player-first; the in-memory crew stash a crossing builds
 * ({@link CrewTransfer.Crew}) lives only for the duration of that crossing and is gone with the JVM;
 * and the ship's own blocks may not even be materialized at the moment the player logs in. This tag
 * is therefore the ONLY player&rarr;ship binding that crosses a restart boundary, and the login
 * restore path reads it before the player is placed into any world.</p>
 *
 * <p><b>Why the offset triple.</b> A seat is recorded by its flight-computer link OFFSET
 * ({@code afcDx/afcDy/afcDz} = the linked computer's position minus the seat's), never by an
 * absolute position. Every re-assembly of a ship rebuilds it into a FRESH subspace, so absolute
 * subspace coordinates go stale on any jump, entry or descent, while the relative offset is
 * invariant under the rigid relocation. It is the same identity {@link CrewTransfer} matches seats
 * by after a crossing, which is what lets a restored player be put back in the seat he left.</p>
 *
 * <p><b>Why the coordinate is only a diagnostic.</b> The galactic coordinate stamped here is the
 * ship's position at the moment the player sat down. It may be stale by the time he logs back in
 * (the ship can keep flying under another crew member), so the ledger's coordinate wins wherever
 * the two disagree; this one is kept for logging and cross-checks.</p>
 *
 * <p>The NBT half ({@link #write}, {@link #read}, {@link #clear(NBTTagCompound)}) touches no world,
 * server or player type, so it is exercisable against a bare compound. The player-facing wrappers
 * are thin shims over {@code getEntityData()}. Server-side in practice.</p>
 */
public final class ShipAboardTag {

    /**
     * The ForgeData sub-compound key. Everything this class writes goes UNDER this one key: the
     * ForgeData compound is shared with every other mod on the pack, so a flat set of fields there
     * would be a collision waiting to happen.
     */
    public static final String KEY = "arShipAboard";

    private static final String SHIP_ID = "shipId";
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_STRING = 8;

    /**
     * Immutable value: the ship a player is aboard, that ship's last-known galactic coordinate, and
     * the flight-computer link offset of the seat he occupies (see the class doc for why a seat is
     * identified by an offset rather than a position).
     */
    public static final class Aboard {

        public final UUID shipId;
        public final GalacticCoord coord;
        public final int afcDx, afcDy, afcDz;

        public Aboard(UUID shipId, GalacticCoord coord, int afcDx, int afcDy, int afcDz) {
            this.shipId = shipId;
            this.coord = coord;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Aboard)) {
                return false;
            }
            Aboard other = (Aboard) o;
            return afcDx == other.afcDx && afcDy == other.afcDy && afcDz == other.afcDz
                    && (shipId == null ? other.shipId == null : shipId.equals(other.shipId))
                    && (coord == null ? other.coord == null : coord.equals(other.coord));
        }

        @Override
        public int hashCode() {
            int result = shipId == null ? 0 : shipId.hashCode();
            result = 31 * result + (coord == null ? 0 : coord.hashCode());
            result = 31 * result + afcDx;
            result = 31 * result + afcDy;
            result = 31 * result + afcDz;
            return result;
        }

        @Override
        public String toString() {
            return "Aboard[ship=" + shipId + ", coord=" + coord + ", afcOffset=("
                    + afcDx + "," + afcDy + "," + afcDz + ")]";
        }
    }

    private ShipAboardTag() { }

    /**
     * Stamp {@code aboard} into {@code forgeData}, replacing any previous record. A {@code null}
     * {@code aboard} clears instead of writing a half-formed tag, so a caller that lost track of the
     * ship cannot leave behind a record {@link #read} would have to reject.
     *
     * <p>The coordinate is encoded with {@link GalacticCoord#writeToNBT} on the sub-compound — the
     * one shared encoding, so any reader of a galactic coordinate decodes this one too.</p>
     */
    public static void write(NBTTagCompound forgeData, Aboard aboard) {
        if (forgeData == null) {
            return;
        }
        if (aboard == null || aboard.shipId == null || aboard.coord == null) {
            forgeData.removeTag(KEY);
            return;
        }
        NBTTagCompound sub = new NBTTagCompound();
        sub.setString(SHIP_ID, aboard.shipId.toString());
        aboard.coord.writeToNBT(sub); // writes the "galacticCoord" sub-tag
        sub.setInteger("afcDx", aboard.afcDx);
        sub.setInteger("afcDy", aboard.afcDy);
        sub.setInteger("afcDz", aboard.afcDz);
        forgeData.setTag(KEY, sub);
    }

    /**
     * The record written by {@link #write}, or {@code null} when there is none to be had — no tag,
     * a tag of the wrong shape, a missing or unparseable ship id, or a missing coordinate. Never
     * throws and never returns a partially-populated {@link Aboard}: this runs inside the login
     * path, where an exception would be a failed login and a half-read record would place a player
     * at a coordinate he was never at.
     *
     * <p>The coordinate is checked for presence explicitly because
     * {@link GalacticCoord#readFromNBT} is deliberately lenient and answers {@code ORIGIN} for an
     * absent sub-tag — which is a legitimate position, so it cannot be used to detect absence. A
     * ship genuinely parked at the origin therefore still reads back as {@code ORIGIN}.</p>
     */
    public static Aboard read(NBTTagCompound forgeData) {
        if (forgeData == null || !forgeData.hasKey(KEY, TAG_COMPOUND)) {
            return null;
        }
        NBTTagCompound sub = forgeData.getCompoundTag(KEY);
        if (!sub.hasKey(SHIP_ID, TAG_STRING) || !sub.hasKey("galacticCoord", TAG_COMPOUND)) {
            return null;
        }
        UUID shipId;
        try {
            shipId = UUID.fromString(sub.getString(SHIP_ID));
        } catch (IllegalArgumentException bad) {
            return null; // corrupt id: treat as "not aboard" rather than fail the login
        }
        return new Aboard(shipId, GalacticCoord.readFromNBT(sub),
                sub.getInteger("afcDx"), sub.getInteger("afcDy"), sub.getInteger("afcDz"));
    }

    /** Drop the record from {@code forgeData}. A no-op when there is none, and it touches nothing
     *  else in the shared compound. */
    public static void clear(NBTTagCompound forgeData) {
        if (forgeData != null) {
            forgeData.removeTag(KEY);
        }
    }

    /** Stamp {@code aboard} onto {@code player}'s persistent entity data (he just sat down). */
    public static void stamp(EntityPlayer player, Aboard aboard) {
        if (player != null) {
            write(player.getEntityData(), aboard);
        }
    }

    /** {@code player}'s aboard record, or {@code null} if he is not aboard a tier-2 ship. */
    public static Aboard of(EntityPlayer player) {
        return player == null ? null : read(player.getEntityData());
    }

    /** Drop {@code player}'s aboard record (he stood up, or his ship is gone). */
    public static void clear(EntityPlayer player) {
        if (player != null) {
            clear(player.getEntityData());
        }
    }
}
