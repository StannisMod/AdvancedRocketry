package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderBody;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.SystemBodiesProducer;
import zmaster587.advancedRocketry.space.SystemBodiesProducer.BodyLookup;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure contract of the render-body producer {@link SystemBodiesProducer#buildByDim}: it maps every
 * SETTLED ship's slot dim to the bodies of its cell, carrying each body as the ship&rarr;body
 * DIRECTION (so {@code BoundarySky} points at a planet that sits at its own cell centre). No MC boot
 * &mdash; the cell body source is injected through the {@link BodyLookup} seam and the ledger is built
 * through its public {@code settle}/{@code beginTransit} API.
 */
public class SystemBodiesProducerTest {

    /** A lookup that answers a fixed body list for one exact coordinate, empty for anything else. */
    private static BodyLookup lookupAt(final GalacticCoord at, final SystemBody... bodies) {
        final Map<GalacticCoord, List<SystemBody>> byCoord = new HashMap<>();
        byCoord.put(at, Arrays.asList(bodies));
        return new BodyLookup() {
            @Override
            public List<SystemBody> bodiesAt(GalacticCoord cell) {
                List<SystemBody> found = byCoord.get(cell);
                return found == null ? Collections.<SystemBody>emptyList() : found;
            }
        };
    }

    @Test
    public void settledPlanetAtCellCentreMapsToShipToBodyDirection() {
        // Ship parked OFF the cell centre; a planet sitting AT the cell centre (local 0,0,0).
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 100L, 50L, -30L);
        GalacticCoord planet = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        SystemBody body = new SystemBody(planet, SystemBodyKind.PLANET, 3, 7);

        ShipLedger ledger = new ShipLedger();
        UUID shipId = UUID.randomUUID();
        ledger.settle(shipId, ship, 42);

        Map<Integer, List<RenderBody>> byDim =
                SystemBodiesProducer.buildByDim(ledger.snapshot(), lookupAt(ship, body));

        assertEquals("only the ship's slot dim is keyed", 1, byDim.size());
        List<RenderBody> bodies = byDim.get(42);
        assertNotNull("slot dim 42 present", bodies);
        assertEquals("one body", 1, bodies.size());

        RenderBody rb = bodies.get(0);
        // Direction is ship->body: (0-100, 0-50, 0-(-30)) = (-100,-50,30). A centred planet is NOT
        // degenerate because the ship sits off-centre.
        assertEquals("dx = body-ship", -100L, rb.localX);
        assertEquals("dy = body-ship", -50L, rb.localY);
        assertEquals("dz = body-ship", 30L, rb.localZ);
        assertEquals("kind propagated", SystemBodyKind.PLANET.ordinal(), rb.kindOrdinal);
        assertEquals("dimId propagated", 3, rb.dimId);
        assertTrue("a planet with a real dim is a descend target", rb.descendTarget);
    }

    @Test
    public void crossCellBodyDirectionIncludesTheSectorTerm() {
        // A body one whole cell away in +X: the direction must carry the CELL-sized sector step, not
        // just the local delta (documents the component-wise sector-aware formula).
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 100L, 0L, 0L);
        GalacticCoord body = GalacticCoord.ofSectorLocal(1L, 0L, 0L, 0L, 0L, 0L);
        SystemBody star = new SystemBody(body, SystemBodyKind.STAR, Constants.INVALID_PLANET, 7);

        ShipLedger ledger = new ShipLedger();
        UUID shipId = UUID.randomUUID();
        ledger.settle(shipId, ship, 9);

        Map<Integer, List<RenderBody>> byDim =
                SystemBodiesProducer.buildByDim(ledger.snapshot(), lookupAt(ship, star));

        RenderBody rb = byDim.get(9).get(0);
        assertEquals("dx carries one CELL step minus the local offset", GalacticCoord.CELL - 100L, rb.localX);
        assertEquals(0L, rb.localY);
        assertEquals(0L, rb.localZ);
    }

    @Test
    public void nonDescendBodyCarriesDescendTargetFalse() {
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 5L, 0L, 0L);
        GalacticCoord beltCoord = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        SystemBody belt = new SystemBody(beltCoord, SystemBodyKind.ASTEROID_BELT, Constants.INVALID_PLANET, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship, 3);

        Map<Integer, List<RenderBody>> byDim =
                SystemBodiesProducer.buildByDim(ledger.snapshot(), lookupAt(ship, belt));

        RenderBody rb = byDim.get(3).get(0);
        assertEquals("kind propagated", SystemBodyKind.ASTEROID_BELT.ordinal(), rb.kindOrdinal);
        assertFalse("a belt with no real dim is not a descend target", rb.descendTarget);
    }

    @Test
    public void inTransitShipIsSkipped() {
        ShipLedger ledger = new ShipLedger();
        UUID shipId = UUID.randomUUID();
        ledger.beginTransit(shipId, GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L));

        // The lookup would answer bodies, but an in-transit ship has no slot to render into.
        SystemBody body = new SystemBody(GalacticCoord.ORIGIN, SystemBodyKind.PLANET, 3, 7);
        BodyLookup always = new BodyLookup() {
            @Override
            public List<SystemBody> bodiesAt(GalacticCoord cell) {
                return Collections.singletonList(body);
            }
        };

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(ledger.snapshot(), always);
        assertTrue("a parked (in-transit) ship contributes no slot", byDim.isEmpty());
    }

    @Test
    public void settledShipInVoidCellGetsPresentButEmptyList() {
        // A settled ship whose cell holds no body must still key its slot dim with an EMPTY list, so the
        // client clears any stale bodies for that dim and draws just the boundary ring.
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 12L, 0L, 0L);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship, 8);

        BodyLookup empty = new BodyLookup() {
            @Override
            public List<SystemBody> bodiesAt(GalacticCoord cell) {
                return new ArrayList<>();
            }
        };

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(ledger.snapshot(), empty);
        assertEquals("the void-cell ship still keys its slot dim", 1, byDim.size());
        assertNotNull("slot dim 8 present", byDim.get(8));
        assertTrue("with an empty body list", byDim.get(8).isEmpty());
    }

    @Test
    public void multipleSettledShipsMapToTheirOwnSlotDims() {
        GalacticCoord shipA = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 10L, 0L, 0L);
        GalacticCoord shipB = GalacticCoord.ofSectorLocal(5L, 0L, 0L, 0L, 0L, 0L);
        SystemBody planetA = new SystemBody(GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L),
                SystemBodyKind.PLANET, 3, 7);
        SystemBody planetB = new SystemBody(GalacticCoord.ofSectorLocal(5L, 0L, 0L, 0L, 0L, 0L),
                SystemBodyKind.MOON, 4, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), shipA, 100);
        ledger.settle(UUID.randomUUID(), shipB, 200);

        final Map<GalacticCoord, List<SystemBody>> byCoord = new HashMap<>();
        byCoord.put(shipA, Collections.singletonList(planetA));
        byCoord.put(shipB, Collections.singletonList(planetB));
        BodyLookup lookup = new BodyLookup() {
            @Override
            public List<SystemBody> bodiesAt(GalacticCoord cell) {
                List<SystemBody> f = byCoord.get(cell);
                return f == null ? Collections.<SystemBody>emptyList() : f;
            }
        };

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(ledger.snapshot(), lookup);
        assertEquals("both settled ships keyed", 2, byDim.size());
        assertEquals(3, byDim.get(100).get(0).dimId);
        assertEquals(4, byDim.get(200).get(0).dimId);
    }

    @Test
    public void nullInputsYieldEmptyMap() {
        assertTrue(SystemBodiesProducer.buildByDim(null, lookupAt(GalacticCoord.ORIGIN)).isEmpty());
        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), GalacticCoord.ORIGIN, 1);
        assertTrue(SystemBodiesProducer.buildByDim(ledger.snapshot(), null).isEmpty());
    }
}
