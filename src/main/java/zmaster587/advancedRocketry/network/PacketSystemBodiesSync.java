package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import zmaster587.libVulpes.network.BasePacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server&rarr;client render channel telling the client, PER SLOT DIM, which bodies to render around a
 * settled ship: the descent boundary and any nearby descend targets. This is the shared render feed for
 * the space-subsystem sky &mdash; the client draws a billboard per body (looked up from the already-synced
 * {@code DimensionProperties} via {@code dimId}) and highlights the ones flagged {@code descendTarget}.
 *
 * <p>Presence is the gate: a body only appears here if the server chose to send it, so there is NO
 * discovery / {@code isSystemKnown} check on this channel &mdash; every field is render-facing. The
 * {@code localX/Y/Z} triple is the ship&rarr;body direction (the body's absolute position minus the
 * settled ship's, component-wise) &mdash; a body at its own cell centre still points away from a ship
 * parked off-centre; the client normalises it. {@code kindOrdinal} selects the render style;
 * {@code descendTarget} drives the boundary highlight.</p>
 *
 * <p>Wire contract (same-version, client-bound): {@code writeInt(dimCount)} then, per dim,
 * {@code writeInt(slotDimId)}, {@code writeInt(bodyCount)} and, per body,
 * {@code writeInt(kindOrdinal)}, {@code writeLong(localX)}, {@code writeLong(localY)},
 * {@code writeLong(localZ)}, {@code writeInt(dimId)}, {@code writeBoolean(descendTarget)},
 * {@code writeLong(boundaryRadius)}, {@code writeLong(radiusBlocks)}, {@code writeInt(parentIndex)};
 * then the NEBULA half, {@code writeInt(dimCount)} and, per dim,
 * {@code writeInt(slotDimId)}, {@code writeInt(nebulaCount)} and, per cloud,
 * {@code writeFloat(dirX/dirY/dirZ)}, {@code writeFloat(angularRadius)},
 * {@code writeInt(appearanceOrdinal)}, {@code writeFloat(opacity)}.
 * {@code executeClient} stashes the decoded payload into client-side static maps (idempotent overwrite)
 * that {@link #bodiesForDim(int)} and {@link #nebulaeForDim(int)} read; {@code read} and
 * {@code executeServer} are never used.</p>
 *
 * <p>The nebula half rides this packet rather than one of its own because it answers the same question
 * — what does the sky of this cell show — keyed by the same cell&rarr;slot binding and cleared by the
 * same empty payload. Bodies carry a POSITION (they are destinations); a cloud carries a DIRECTION and
 * an apparent size, and nothing else, because it is not one.</p>
 */
public final class PacketSystemBodiesSync extends BasePacket {

    /** One render body for a slot dim: what to draw and where, plus the descend-target highlight flag. */
    public static final class RenderBody {

        /** {@link #parentIndex} of a body that belongs to nothing — a star, a planet, a lone POI. */
        public static final int NO_PARENT = -1;

        public final int kindOrdinal;
        public final long localX;
        public final long localY;
        public final long localZ;
        public final int dimId;
        public final boolean descendTarget;
        /**
         * Blocks from this body's address at which its atmosphere begins — the surface a ship
         * crosses to enter it, and what the range shown beside the body counts down to.
         *
         * <p>Sent per body rather than assumed by the client, and that is the point: it is one
         * number for every body only while bodies are dimensionless points. The moment a body has a
         * real radius this differs per body, and a client that had derived it from a shared
         * constant would draw and label every approach wrong with nothing to indicate it. Zero for
         * a body that is not a descend target — a star has no shell to cross.</p>
         */
        public final long boundaryRadius;

        /**
         * How big the body itself is, in blocks — its own radius on the chart metric, not the shell
         * around it.
         *
         * <p>Sent because the client cannot derive it: the universe registry is server-side, and a
         * procedural world has no dimension to read a radius out of until somebody lands on it.
         * Without this the sky sized a body by DISTANCE alone, so a moon and a gas giant side by
         * side drew exactly the same disc. Zero for anything that is not a sphere — a belt, a
         * station slot — which a renderer must treat as "no size of its own" rather than as
         * "infinitely small".</p>
         */
        public final long radiusBlocks;

        /**
         * Index, WITHIN THIS DIM'S BODY LIST, of the body this one belongs to — or {@code -1} for a
         * body that belongs to nothing.
         *
         * <p>Structure, which is the half of the feed that was missing: a moon carried a direction
         * and a size but no way to say whose moon it was, so the sky could draw a giant and its
         * retinue and not tell a pilot they were one destination. An INDEX rather than an id because
         * the list is sent as a unit and a procedural body has no id of any kind — it has no
         * dimension until somebody lands on it.</p>
         *
         * <p>Resolved server-side from the invariant the universe layer already holds: a moon shares
         * its parent's CELL, and a cell holds at most one real body with moons excepted. So the
         * parent of a moon is the non-moon body of the same cell, and there is never a second
         * candidate.</p>
         */
        public final int parentIndex;

        public RenderBody(int kindOrdinal, long localX, long localY, long localZ, int dimId,
                          boolean descendTarget, long boundaryRadius, long radiusBlocks,
                          int parentIndex) {
            this.kindOrdinal = kindOrdinal;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.dimId = dimId;
            this.descendTarget = descendTarget;
            this.boundaryRadius = boundaryRadius;
            this.radiusBlocks = radiusBlocks;
            this.parentIndex = parentIndex;
        }

        @Override
        public String toString() {
            return "RenderBody{kind=" + kindOrdinal + ",dir=" + localX + "," + localY + "," + localZ
                    + ",dim=" + dimId + ",descend=" + descendTarget + ",shell=" + boundaryRadius
                    + ",r=" + radiusBlocks + ",parent=" + parentIndex + "}";
        }
    }

    /**
     * One nebula for a slot dim: a DIRECTION and an apparent SIZE, never a position.
     *
     * <p>A cloud is light years across and hundreds of light years away, so it has no parallax across
     * a cell and nothing can be flown to it — it is deliberately not a destination and carries no
     * address. What the sky needs is where to look, how much of the sky it covers, what it looks
     * like, and how thick it is; those four are all of it.</p>
     */
    public static final class RenderNebula {
        /** Unit vector from the observer towards the cloud's centre, in the static frame. */
        public final float dirX;
        public final float dirY;
        public final float dirZ;
        /**
         * Half-angle the cloud subtends, in radians. A viewer INSIDE one gets a right angle: the
         * cloud is all around him, which is the honest limit rather than an overflow.
         */
        public final float angularRadius;
        /** {@code Nebula.Appearance} ordinal — dark, emission or reflection. Decides the tint. */
        public final int appearanceOrdinal;
        /** How thick it is at its densest, {@code 0}..{@code 1}. Decides how strongly it draws. */
        public final float opacity;

        public RenderNebula(float dirX, float dirY, float dirZ, float angularRadius,
                            int appearanceOrdinal, float opacity) {
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.angularRadius = angularRadius;
            this.appearanceOrdinal = appearanceOrdinal;
            this.opacity = opacity;
        }

        @Override
        public String toString() {
            return "RenderNebula{dir=" + dirX + "," + dirY + "," + dirZ + ",theta=" + angularRadius
                    + ",look=" + appearanceOrdinal + ",opacity=" + opacity + "}";
        }
    }

    /** Client-side render store: slot dim id -> bodies to draw. Read by the sky renderer via {@link #bodiesForDim}. */
    private static final Map<Integer, List<RenderBody>> CLIENT_BODIES = new LinkedHashMap<>();

    /** Client-side render store: slot dim id -> nebulae to draw. Read via {@link #nebulaeForDim}. */
    private static final Map<Integer, List<RenderNebula>> CLIENT_NEBULAE = new LinkedHashMap<>();

    /** The decoded payload carried by this instance (server: what to send; client: what was received). */
    private Map<Integer, List<RenderBody>> byDim = new LinkedHashMap<>();

    /** The nebula half of the same payload, keyed the same way. */
    private Map<Integer, List<RenderNebula>> nebulaeByDim = new LinkedHashMap<>();

    public PacketSystemBodiesSync() {
    }

    /** Server factory: snapshot the per-slot-dim render bodies to broadcast to a client. */
    public static PacketSystemBodiesSync forDims(Map<Integer, List<RenderBody>> byDim) {
        return forDims(byDim, null);
    }

    /**
     * Server factory carrying BOTH halves of a cell's sky.
     *
     * <p>One channel and not two, because both are answers to the same question — what does the sky of
     * this cell show — keyed by the same cell&rarr;slot binding, cleared by the same empty payload and
     * broadcast on the same tick. A second channel would be a second lifecycle to keep in step, and the
     * two skies could then disagree about which cell the viewer is in.</p>
     */
    public static PacketSystemBodiesSync forDims(Map<Integer, List<RenderBody>> byDim,
                                                 Map<Integer, List<RenderNebula>> nebulaeByDim) {
        PacketSystemBodiesSync p = new PacketSystemBodiesSync();
        if (byDim != null) {
            for (Map.Entry<Integer, List<RenderBody>> e : byDim.entrySet()) {
                List<RenderBody> bodies = e.getValue() == null
                        ? new ArrayList<RenderBody>()
                        : new ArrayList<>(e.getValue());
                p.byDim.put(e.getKey(), bodies);
            }
        }
        if (nebulaeByDim != null) {
            for (Map.Entry<Integer, List<RenderNebula>> e : nebulaeByDim.entrySet()) {
                List<RenderNebula> clouds = e.getValue() == null
                        ? new ArrayList<RenderNebula>()
                        : new ArrayList<>(e.getValue());
                p.nebulaeByDim.put(e.getKey(), clouds);
            }
        }
        return p;
    }

    /** Nothing to render (no dims carry any bodies). */
    public boolean isEmpty() {
        return byDim.isEmpty();
    }

    /** The decoded payload of THIS instance &mdash; the test reads it back without touching client statics. */
    public Map<Integer, List<RenderBody>> payload() {
        return byDim;
    }

    /** The nebula half of the decoded payload of THIS instance. */
    public Map<Integer, List<RenderNebula>> nebulaPayload() {
        return nebulaeByDim;
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeInt(byDim.size());
        for (Map.Entry<Integer, List<RenderBody>> e : byDim.entrySet()) {
            List<RenderBody> bodies = e.getValue();
            buffer.writeInt(e.getKey());
            buffer.writeInt(bodies.size());
            for (RenderBody b : bodies) {
                buffer.writeInt(b.kindOrdinal);
                buffer.writeLong(b.localX);
                buffer.writeLong(b.localY);
                buffer.writeLong(b.localZ);
                buffer.writeInt(b.dimId);
                buffer.writeBoolean(b.descendTarget);
                buffer.writeLong(b.boundaryRadius);
                buffer.writeLong(b.radiusBlocks);
                buffer.writeInt(b.parentIndex);
            }
        }
        buffer.writeInt(nebulaeByDim.size());
        for (Map.Entry<Integer, List<RenderNebula>> e : nebulaeByDim.entrySet()) {
            List<RenderNebula> clouds = e.getValue();
            buffer.writeInt(e.getKey());
            buffer.writeInt(clouds.size());
            for (RenderNebula n : clouds) {
                buffer.writeFloat(n.dirX);
                buffer.writeFloat(n.dirY);
                buffer.writeFloat(n.dirZ);
                buffer.writeFloat(n.angularRadius);
                buffer.writeInt(n.appearanceOrdinal);
                buffer.writeFloat(n.opacity);
            }
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        Map<Integer, List<RenderBody>> decoded = new LinkedHashMap<>();
        int dimCount = buffer.readInt();
        for (int i = 0; i < dimCount; i++) {
            int slotDimId = buffer.readInt();
            int bodyCount = buffer.readInt();
            List<RenderBody> bodies = new ArrayList<>();
            for (int j = 0; j < bodyCount; j++) {
                int kindOrdinal = buffer.readInt();
                long localX = buffer.readLong();
                long localY = buffer.readLong();
                long localZ = buffer.readLong();
                int dimId = buffer.readInt();
                boolean descendTarget = buffer.readBoolean();
                long boundaryRadius = buffer.readLong();
                long radiusBlocks = buffer.readLong();
                int parentIndex = buffer.readInt();
                bodies.add(new RenderBody(kindOrdinal, localX, localY, localZ, dimId, descendTarget,
                        boundaryRadius, radiusBlocks, parentIndex));
            }
            decoded.put(slotDimId, bodies);
        }
        byDim = decoded;

        Map<Integer, List<RenderNebula>> decodedClouds = new LinkedHashMap<>();
        int cloudDimCount = buffer.readInt();
        for (int i = 0; i < cloudDimCount; i++) {
            int slotDimId = buffer.readInt();
            int cloudCount = buffer.readInt();
            List<RenderNebula> clouds = new ArrayList<>();
            for (int j = 0; j < cloudCount; j++) {
                float dirX = buffer.readFloat();
                float dirY = buffer.readFloat();
                float dirZ = buffer.readFloat();
                float angularRadius = buffer.readFloat();
                int appearanceOrdinal = buffer.readInt();
                float opacity = buffer.readFloat();
                clouds.add(new RenderNebula(dirX, dirY, dirZ, angularRadius, appearanceOrdinal,
                        opacity));
            }
            decodedClouds.put(slotDimId, clouds);
        }
        nebulaeByDim = decodedClouds;
    }

    @Override
    public void read(ByteBuf in) {
        // never read on the server
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void executeClient(EntityPlayer player) {
        CLIENT_BODIES.clear();
        CLIENT_BODIES.putAll(byDim);
        CLIENT_NEBULAE.clear();
        CLIENT_NEBULAE.putAll(nebulaeByDim);
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }

    /** Client render read: the bodies to draw in {@code slotDimId}. Never null &mdash; an empty list when none. */
    @SideOnly(Side.CLIENT)
    public static List<RenderBody> bodiesForDim(int slotDimId) {
        List<RenderBody> bodies = CLIENT_BODIES.get(slotDimId);
        return bodies == null ? Collections.<RenderBody>emptyList() : bodies;
    }

    /** Client render read: the nebulae to draw in {@code slotDimId}. Never null. */
    @SideOnly(Side.CLIENT)
    public static List<RenderNebula> nebulaeForDim(int slotDimId) {
        List<RenderNebula> clouds = CLIENT_NEBULAE.get(slotDimId);
        return clouds == null ? Collections.<RenderNebula>emptyList() : clouds;
    }
}
