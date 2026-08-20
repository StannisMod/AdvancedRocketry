package zmaster587.advancedRocketry.test.unit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import zmaster587.advancedRocketry.network.PacketSystemBodiesSync;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderBody;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure write&rarr;readClient ByteBuf round-trip for {@link PacketSystemBodiesSync}: the per-slot-dim render
 * body payload must survive the wire unchanged. No MC server/client boot &mdash; we exercise the packet's
 * own {@code write} + {@code readClient} + {@link PacketSystemBodiesSync#payload()} accessor and never call
 * {@code executeClient} (it is {@code @SideOnly(Side.CLIENT)} and touches client statics).
 */
public class PacketSystemBodiesSyncTest {

    private static ByteBuf newBuffer() {
        return Unpooled.buffer();
    }

    @Test
    public void twoDimsWithDifferingBodyListsRoundTrip() {
        Map<Integer, List<RenderBody>> sent = new LinkedHashMap<>();

        List<RenderBody> dimA = new ArrayList<>();
        // A descend target carries a shell; the body beside it carries none. The two must survive
        // the wire as DIFFERENT numbers — a codec that dropped the field, or wrote one body's value
        // for every body, would still round-trip a payload where they all agreed.
        dimA.add(new RenderBody(2, 100L, -200L, 300L, 41, true, 512L, 25_512L, RenderBody.NO_PARENT));
        dimA.add(new RenderBody(0, -7L, 8L, -9L, 55, false, 0L, 0L, 0));

        List<RenderBody> dimB = new ArrayList<>();
        dimB.add(new RenderBody(5, 1_000_000_000_000L, 0L, -1_000_000_000_000L, 7, false, 7_777L,
                2_800_000L, RenderBody.NO_PARENT));

        sent.put(11, dimA);
        sent.put(-4, dimB);

        PacketSystemBodiesSync packet = PacketSystemBodiesSync.forDims(sent);
        assertFalse("payload with bodies is not empty", packet.isEmpty());

        ByteBuf buffer = newBuffer();
        packet.write(buffer);

        PacketSystemBodiesSync received = new PacketSystemBodiesSync();
        received.readClient(buffer);

        assertEquals("wire fully consumed", 0, buffer.readableBytes());

        Map<Integer, List<RenderBody>> decoded = received.payload();
        assertEquals("both dims survive", 2, decoded.size());

        List<RenderBody> outA = decoded.get(11);
        assertNotNull("dim 11 present", outA);
        assertEquals(2, outA.size());
        assertBody(dimA.get(0), outA.get(0));
        assertBody(dimA.get(1), outA.get(1));

        List<RenderBody> outB = decoded.get(-4);
        assertNotNull("dim -4 present", outB);
        assertEquals(1, outB.size());
        assertBody(dimB.get(0), outB.get(0));
    }

    @Test
    public void emptyPayloadRoundTrip() {
        PacketSystemBodiesSync packet = PacketSystemBodiesSync.forDims(new LinkedHashMap<Integer, List<RenderBody>>());
        assertTrue("no dims -> empty", packet.isEmpty());

        ByteBuf buffer = newBuffer();
        packet.write(buffer);

        PacketSystemBodiesSync received = new PacketSystemBodiesSync();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        assertTrue("decoded empty payload stays empty", received.isEmpty());
        assertEquals(0, received.payload().size());
    }

    @Test
    public void dimWithNoBodiesRoundTrip() {
        // A slot dim keyed with an empty body list must survive as a present-but-empty entry, not vanish.
        Map<Integer, List<RenderBody>> sent = new LinkedHashMap<>();
        sent.put(9, new ArrayList<RenderBody>());

        PacketSystemBodiesSync packet = PacketSystemBodiesSync.forDims(sent);
        assertFalse("a keyed dim, even empty-listed, is not an empty payload", packet.isEmpty());

        ByteBuf buffer = newBuffer();
        packet.write(buffer);

        PacketSystemBodiesSync received = new PacketSystemBodiesSync();
        received.readClient(buffer);

        assertEquals(0, buffer.readableBytes());
        Map<Integer, List<RenderBody>> decoded = received.payload();
        assertEquals(1, decoded.size());
        assertNotNull(decoded.get(9));
        assertEquals(0, decoded.get(9).size());
    }

    private static void assertBody(RenderBody expected, RenderBody actual) {
        assertEquals("kindOrdinal", expected.kindOrdinal, actual.kindOrdinal);
        assertEquals("localX", expected.localX, actual.localX);
        assertEquals("localY", expected.localY, actual.localY);
        assertEquals("localZ", expected.localZ, actual.localZ);
        assertEquals("dimId", expected.dimId, actual.dimId);
        assertEquals("descendTarget", expected.descendTarget, actual.descendTarget);
        assertEquals("boundaryRadius", expected.boundaryRadius, actual.boundaryRadius);
        // The body's OWN size, distinct from the shell around it: the sky cannot draw a giant as a
        // giant if this is dropped, and dropping it looks exactly like the old distance-only sizing.
        assertEquals("radiusBlocks", expected.radiusBlocks, actual.radiusBlocks);
        // Whose moon it is. Dropped, a giant and its retinue arrive as unrelated dots.
        assertEquals("parentIndex", expected.parentIndex, actual.parentIndex);
    }
}
