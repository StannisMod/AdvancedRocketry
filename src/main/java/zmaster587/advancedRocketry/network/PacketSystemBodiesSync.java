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
 * {@code writeLong(boundaryRadius)}.
 * {@code executeClient} stashes the decoded payload into a client-side static map (idempotent overwrite)
 * that {@link #bodiesForDim(int)} reads; {@code read} and {@code executeServer} are never used.</p>
 */
public final class PacketSystemBodiesSync extends BasePacket {

    /** One render body for a slot dim: what to draw and where, plus the descend-target highlight flag. */
    public static final class RenderBody {
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

        public RenderBody(int kindOrdinal, long localX, long localY, long localZ, int dimId,
                          boolean descendTarget, long boundaryRadius) {
            this.kindOrdinal = kindOrdinal;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.dimId = dimId;
            this.descendTarget = descendTarget;
            this.boundaryRadius = boundaryRadius;
        }

        @Override
        public String toString() {
            return "RenderBody{kind=" + kindOrdinal + ",dir=" + localX + "," + localY + "," + localZ
                    + ",dim=" + dimId + ",descend=" + descendTarget + ",shell=" + boundaryRadius + "}";
        }
    }

    /** Client-side render store: slot dim id -> bodies to draw. Read by the sky renderer via {@link #bodiesForDim}. */
    private static final Map<Integer, List<RenderBody>> CLIENT_BODIES = new LinkedHashMap<>();

    /** The decoded payload carried by this instance (server: what to send; client: what was received). */
    private Map<Integer, List<RenderBody>> byDim = new LinkedHashMap<>();

    public PacketSystemBodiesSync() {
    }

    /** Server factory: snapshot the per-slot-dim render bodies to broadcast to a client. */
    public static PacketSystemBodiesSync forDims(Map<Integer, List<RenderBody>> byDim) {
        PacketSystemBodiesSync p = new PacketSystemBodiesSync();
        if (byDim != null) {
            for (Map.Entry<Integer, List<RenderBody>> e : byDim.entrySet()) {
                List<RenderBody> bodies = e.getValue() == null
                        ? new ArrayList<RenderBody>()
                        : new ArrayList<>(e.getValue());
                p.byDim.put(e.getKey(), bodies);
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
                bodies.add(new RenderBody(kindOrdinal, localX, localY, localZ, dimId, descendTarget,
                        boundaryRadius));
            }
            decoded.put(slotDimId, bodies);
        }
        byDim = decoded;
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
}
