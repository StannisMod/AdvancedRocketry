package zmaster587.advancedRocketry.space;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

/**
 * Carries the bodies aboard a ship that are NOT its crew — a mob on the deck, a dropped item, a
 * minecart — across a crossing, by the same ship-relative point the crew is carried by.
 *
 * <h2>Why these are stowed rather than held</h2>
 *
 * A crew member's movement is client-authoritative, so a crossing has to negotiate with his client:
 * it places him and then pins him until that client takes the deck capture over. Nothing here is
 * negotiating with anyone. The server owns these bodies outright, so the honest treatment is the one
 * the ship's own blocks get — write them down, take them out of the world, and put them back on the
 * far side. That also removes the window a held body would have to survive: there is no moment in
 * which one of these is standing in a world whose ship has not been rebuilt yet, so there is nothing
 * for gravity to do to it.
 *
 * <h2>What counts as aboard</h2>
 *
 * The ship's own stay region, in its subspace — the same volume the hyperspace void judges a crew
 * member by, so a mob and a player standing side by side on a deck are aboard by one definition
 * rather than two. A body whose position cannot be mapped into that frame is not aboard anything and
 * is left exactly where it is.
 *
 * <p>Server main thread only; a safe no-op when the physics mod is absent or the ship is not loaded.</p>
 */
public final class AboardBodies {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    /**
     * How far outside the hull's own box a body still counts as aboard, in blocks. One block: a mob
     * standing on a deck has its feet on the surface and its box above it, and a dropped item rests
     * fractionally proud of the block it landed on.
     */
    private static final double ABOARD_MARGIN = 1.0D;

    /** One stowed body: what it was, and where on the ship it was. */
    public static final class Stowed {
        final NBTTagCompound nbt;
        final double dx, dy, dz;

        Stowed(NBTTagCompound nbt, double dx, double dy, double dz) {
            this.nbt = nbt;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    private AboardBodies() { }

    /**
     * Take every non-crew body aboard the ship whose flight computer sits at subspace {@code afcPos}
     * out of {@code world}, recording each against that computer. Call BEFORE the crossing cuts the
     * ship's blocks; the bodies are removed from the world, so a caller that does not go on to
     * {@link #release} them has destroyed them.
     *
     * <p>Players and seat dummies are never stowed: the crew transfer owns the first and the seat
     * binding owns the second. A body that is RIDING something is left to its vehicle — stowing a
     * passenger without its mount would put it back on the far side sitting on nothing.</p>
     */
    public static List<Stowed> capture(WorldServer world, BlockPos afcPos) {
        List<Stowed> stowed = new ArrayList<>();
        if (world == null || afcPos == null) {
            return stowed;
        }
        String vsShipId = VSIntegration.shipIdManagingBlock(world, afcPos);
        AxisAlignedBB stay = vsShipId == null
                ? null : VSIntegration.subspaceStayRegion(world, vsShipId, ABOARD_MARGIN);
        if (stay == null) {
            // Say so. Carrying nothing is the right answer when there is no ship to be aboard of, and
            // it is indistinguishable from "nothing was aboard" — which is exactly the silence that
            // makes a body quietly left behind unattributable.
            LOGGER.info("[SPACE] no loaded ship at {} to stow bodies from (ship id {}); carrying none",
                    afcPos, vsShipId);
            return stowed;
        }
        for (Entity body : new ArrayList<>(world.loadedEntityList)) {
            if (body.isDead || body instanceof EntityPlayer || body instanceof EntityDummy
                    || body.isRiding()) {
                continue;
            }
            double[] local = VSIntegration.toShipFrameFor(
                    world, vsShipId, body.posX, body.posY, body.posZ);
            if (local == null || !stay.contains(new Vec3d(local[0], local[1], local[2]))) {
                continue;
            }
            NBTTagCompound nbt = new NBTTagCompound();
            // An entity that refuses to be written down is one vanilla itself would not save across
            // a world unload — leave it alone rather than delete it for the sake of a carry.
            if (!body.writeToNBTOptional(nbt)) {
                continue;
            }
            double[] offset = ShipRelativePoint.offsetOfSubspacePoint(
                    afcPos, local[0], local[1], local[2]);
            stowed.add(new Stowed(nbt, offset[0], offset[1], offset[2]));
            body.setDead();
        }
        if (!stowed.isEmpty()) {
            LOGGER.info("[SPACE] stowed {} body(ies) aboard the ship at {} for its crossing",
                    stowed.size(), afcPos);
        }
        return stowed;
    }

    /** One decimal place, dot-separated whatever the machine's locale is. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Put every stowed body back on the re-assembled ship whose flight computer sits at subspace
     * {@code afcPos} in {@code dstWorld}, at the point it was taken from and at rest. Returns how
     * many were placed; {@code 0} with a non-empty list means the ship is not up yet and the caller
     * should retry, which is the same contract the crew placement has.
     *
     * <p><b>All or nothing.</b> Whether the ship can say where a point on it is does not vary from
     * body to body — it is one question about one ship — so it is asked ONCE, before anything is
     * placed. A partial release would be re-run by the caller's retry and put the bodies it already
     * placed into the world a second time, which is how a carry turns into duplication.</p>
     */
    public static int release(WorldServer dstWorld, BlockPos afcPos, List<Stowed> bodies) {
        if (dstWorld == null || afcPos == null || bodies == null || bodies.isEmpty()) {
            return 0;
        }
        // Registry-keyed like the crew placement, and for the same reason: an arriving ship has
        // nobody near it, so a question only a LOADED ship can answer would never be answered.
        double[] afcWorld = VSIntegration.getRegisteredSubspacePointWorldPosition(dstWorld, afcPos,
                afcPos.getX(), afcPos.getY(), afcPos.getZ());
        if (afcWorld == null) {
            return 0; // the ship is not rebuilt here yet; nothing is lost, the caller retries
        }
        int placed = 0, unmappable = 0, unbuildable = 0, refused = 0;
        StringBuilder where = new StringBuilder();
        for (Stowed body : bodies) {
            double[] sub = ShipRelativePoint.subspacePointOf(afcPos, body.dx, body.dy, body.dz);
            double[] world = sub == null ? null
                    : VSIntegration.getRegisteredSubspacePointWorldPosition(
                            dstWorld, afcPos, sub[0], sub[1], sub[2]);
            if (world == null) {
                unmappable++;
                continue;
            }
            // Load the chunk this body lands in, FIRST. An arrival happens where nobody is standing —
            // that is the ordinary case for a jump, not an edge one — and vanilla refuses an entity
            // whose chunk is not in the loaded set, without a word (`World.spawnEntity` ->
            // `WorldServer.isChunkLoaded` -> `chunkExists`, which ignores its own allowEmpty flag).
            // Depending on somebody else having loaded it is what made a carried body vanish. The
            // crossing already force-loads the ship's own shipyard chunks for the same reason.
            dstWorld.getChunkFromBlockCoords(new BlockPos(world[0], world[1], world[2]));

            Entity restored = EntityList.createEntityFromNBT(body.nbt, dstWorld);
            if (restored == null) {
                unbuildable++;
                continue; // an entity type this world cannot build; its record is dropped, not retried
            }
            restored.setPosition(world[0], world[1], world[2]);
            restored.motionX = 0.0D;
            restored.motionY = 0.0D;
            restored.motionZ = 0.0D;
            restored.fallDistance = 0.0f;
            // The world gets to REFUSE, and it refuses SILENTLY: vanilla drops an entity whose chunk
            // is not loaded unless it is marked forceSpawn (`World.spawnEntity`), and it drops one
            // whose uuid it already knows (`WorldServer.canAddEntity`). Ignoring this boolean is how
            // a carry reports success for a body the destination never accepted — the count said
            // "placed 1" while the world held nothing.
            if (!dstWorld.spawnEntity(restored)) {
                refused++;
                // WHERE it was refused, in the same breath. The refusal branch used to print counts
                // only, so the one question it raises - which chunk is missing - could not be answered
                // from it, and the reader had to guess from a passing run's coordinates.
                where.append(where.length() == 0 ? "" : " | ")
                        .append("refused@").append(round1(world[0])).append(' ')
                        .append(round1(world[1])).append(' ').append(round1(world[2]))
                        .append(" chunk ").append(net.minecraft.util.math.MathHelper.floor(world[0] / 16.0))
                        .append(',').append(net.minecraft.util.math.MathHelper.floor(world[2] / 16.0));
                continue;
            }
            // Plain concatenation, never String.format("%.1f"): the default locale on a Russian
            // Windows prints a DECIMAL COMMA, which turns three coordinates into six numbers and
            // nobody reading the line can tell where one of them ends. A double prints with a dot
            // whatever the locale is.
            where.append(where.length() == 0 ? "" : " | ")
                    .append(round1(world[0])).append(' ')
                    .append(round1(world[1])).append(' ')
                    .append(round1(world[2]));
            placed++;
        }
        // Say what happened, in both directions. A carry that delivers everything and a carry that
        // drops half of it used to be the same silence, and the count alone cannot be read without
        // the two reasons beside it: a body whose point would not map is one the ship could not place
        // yet, a body this world could not build is one whose record has just been thrown away for
        // good. They are different losses and only one of them is retried.
        if (unmappable > 0 || unbuildable > 0 || refused > 0) {
            LOGGER.warn("[SPACE] released {} of {} stowed body(ies) onto the ship at {}: {} could not "
                            + "be mapped onto it, {} could not be rebuilt in this world (dropped for "
                            + "good), {} were REFUSED by the world itself - which it does without a "
                            + "word when the chunk they would land in is not loaded. Bodies: {}",
                    placed, bodies.size(), afcPos, unmappable, unbuildable, refused, where);
        } else {
            // WHERE, not just how many. A body that came back and a body that came back to the wrong
            // place are the same count, and the second one is what "the jump lost my things" actually
            // looks like from inside the game. The computer's own mapped position is printed beside
            // them because it is the frame every one of these coordinates was derived from: if the
            // bodies sit around it and the ship reports itself somewhere else, the disagreement is
            // between two answers about the ship, not between the ship and its cargo.
            LOGGER.info("[SPACE] released {} body(ies) back onto the ship at {}, whose computer maps "
                            + "to {} {} {} in the world; bodies at {}",
                    placed, afcPos, round1(afcWorld[0]), round1(afcWorld[1]), round1(afcWorld[2]),
                    where);
        }
        return placed;
    }
}
