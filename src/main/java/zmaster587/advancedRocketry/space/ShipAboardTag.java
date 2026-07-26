package zmaster587.advancedRocketry.space;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The per-player durable record "<i>I am aboard tier-2 ship X, at Y</i>", stored in the player's
 * persistent ForgeData compound so it survives a logout and a server restart.
 *
 * <p><b>Aboard is not the same as seated.</b> Y is a seat for a crew member in one and a deck point
 * for a crew member on his feet; both are aboard, and the record carries whichever applies. Reading
 * "no seat" as "not aboard" is what used to send anyone who stood up in orbit to an ordinary spawn.</p>
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
    /** Present and true only for a STANDING record; its absence reads as SEATED. */
    private static final String STANDING = "standing";
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_STRING = 8;

    /** How a crew member was aboard. Both are ABOARD; they differ only in what position means. */
    public enum Posture {
        /** In a seat: the position IS the seat, expressed as its flight-computer link offset. */
        SEATED,
        /** On his feet: the position is where he stood, expressed relative to the same computer. */
        STANDING
    }

    /**
     * Immutable value: the ship a player is aboard, that ship's last-known galactic coordinate, and
     * WHERE he was on it — either the flight-computer link offset of the seat he occupies, or, for a
     * crew member on his feet, the point he stood at relative to that same computer.
     *
     * <p>Both postures measure from the flight computer for the same reason (see the class doc): it
     * is the one landmark that survives a re-assembly into a fresh subspace. A standing position is
     * continuous, so it is kept as doubles; a seat lands on a block and stays integral.</p>
     */
    public static final class Aboard {

        public final UUID shipId;
        public final GalacticCoord coord;
        public final Posture posture;
        /** SEATED: the seat's link offset. Zero and meaningless when {@link #posture} is STANDING. */
        public final int afcDx, afcDy, afcDz;
        /** STANDING: where he stood, relative to the computer. Zero when the posture is SEATED. */
        public final double standDx, standDy, standDz;

        /** A crew member in a seat, identified by that seat's flight-computer link offset. */
        public Aboard(UUID shipId, GalacticCoord coord, int afcDx, int afcDy, int afcDz) {
            this.shipId = shipId;
            this.coord = coord;
            this.posture = Posture.SEATED;
            this.afcDx = afcDx;
            this.afcDy = afcDy;
            this.afcDz = afcDz;
            this.standDx = 0.0D;
            this.standDy = 0.0D;
            this.standDz = 0.0D;
        }

        private Aboard(UUID shipId, GalacticCoord coord, double dx, double dy, double dz) {
            this.shipId = shipId;
            this.coord = coord;
            this.posture = Posture.STANDING;
            this.afcDx = 0;
            this.afcDy = 0;
            this.afcDz = 0;
            this.standDx = dx;
            this.standDy = dy;
            this.standDz = dz;
        }

        /**
         * A crew member on his feet at {@code (dx,dy,dz)} from his ship's flight computer. Standing
         * on the deck is a way of BEING aboard, not of having left — a distinction the restore path
         * used to collapse, sending anyone who stood up to an ordinary spawn.
         */
        public static Aboard standing(UUID shipId, GalacticCoord coord,
                                      double dx, double dy, double dz) {
            return new Aboard(shipId, coord, dx, dy, dz);
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
            return posture == other.posture
                    && afcDx == other.afcDx && afcDy == other.afcDy && afcDz == other.afcDz
                    && Double.compare(standDx, other.standDx) == 0
                    && Double.compare(standDy, other.standDy) == 0
                    && Double.compare(standDz, other.standDz) == 0
                    && (shipId == null ? other.shipId == null : shipId.equals(other.shipId))
                    && (coord == null ? other.coord == null : coord.equals(other.coord));
        }

        @Override
        public int hashCode() {
            int result = shipId == null ? 0 : shipId.hashCode();
            result = 31 * result + (coord == null ? 0 : coord.hashCode());
            result = 31 * result + posture.hashCode();
            result = 31 * result + afcDx;
            result = 31 * result + afcDy;
            result = 31 * result + afcDz;
            result = 31 * result + Long.valueOf(Double.doubleToLongBits(standDx)).hashCode();
            result = 31 * result + Long.valueOf(Double.doubleToLongBits(standDy)).hashCode();
            result = 31 * result + Long.valueOf(Double.doubleToLongBits(standDz)).hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Aboard[ship=" + shipId + ", coord=" + coord
                    + (posture == Posture.SEATED
                            ? ", seatOffset=(" + afcDx + "," + afcDy + "," + afcDz + ")"
                            : ", standOffset=(" + standDx + "," + standDy + "," + standDz + ")")
                    + "]";
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
        // A seated record writes nothing extra, so the common case stays exactly the shape it has
        // always been on disk; the standing keys appear only for the posture that needs them.
        if (aboard.posture == Posture.STANDING) {
            sub.setBoolean(STANDING, true);
            sub.setDouble("standDx", aboard.standDx);
            sub.setDouble("standDy", aboard.standDy);
            sub.setDouble("standDz", aboard.standDz);
        }
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
        // Absent posture key means SEATED — the only shape that existed when the tag was introduced,
        // and the shape a seated record still writes.
        if (sub.getBoolean(STANDING)) {
            return Aboard.standing(shipId, GalacticCoord.readFromNBT(sub),
                    sub.getDouble("standDx"), sub.getDouble("standDy"), sub.getDouble("standDz"));
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
