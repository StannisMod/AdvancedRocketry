package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.network.PacketSystemBodiesSync.RenderBody;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.CellFrames;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.space.SystemBodiesProducer;
import zmaster587.advancedRocketry.space.SystemBodiesProducer.BodyLookup;
import zmaster587.advancedRocketry.universe.BodyEphemeris;
import zmaster587.advancedRocketry.universe.CellFrame;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure contract of the render-body producer {@link SystemBodiesProducer#buildByDim}: it maps every
 * MATERIALIZED CELL's slot dim to the bodies of that cell, carrying each body as the
 * observer&rarr;body DIRECTION (so {@code BoundarySky} points at a planet that sits at its own cell
 * centre). No MC boot &mdash; the cell body source is injected through the {@link BodyLookup} seam, the
 * cell&rarr;slot bindings are passed as plain data, and the ledger is built through its public
 * {@code settle}/{@code beginTransit} API.
 *
 * <p>The feed is derived from the cells that exist, NOT from ship lifecycle: whoever is inside a live
 * cell sees its surroundings whatever the ship he arrived on is doing.</p>
 */
public class SystemBodiesProducerTest {

    /** The cell&rarr;slot bindings {@link SpaceManager#loadedCells} hands the producer. */
    private static Map<String, Integer> live(GalacticCoord cell, int dim) {
        Map<String, Integer> bound = new LinkedHashMap<>();
        bound.put(cell.cellKey(), dim);
        return bound;
    }

    /** A lookup that answers a fixed body list for one CELL (any coordinate in it), empty elsewhere. */
    private static BodyLookup lookupIn(final GalacticCoord cell, final SystemBody... bodies) {
        return new BodyLookup() {
            @Override
            public List<SystemBody> skyBodiesAt(GalacticCoord asked) {
                return asked != null && asked.sameCell(cell)
                        ? Arrays.asList(bodies) : Collections.<SystemBody>emptyList();
            }
        };
    }

    @Test
    public void aBodyAtTheCellCentreIsCarriedAsTheDirectionFromTheShipThatIsThere() {
        // Ship parked OFF the cell centre; a planet sitting AT the cell centre (local 0,0,0).
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 100L, 50L, -30L);
        GalacticCoord planet = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        SystemBody body = SystemBody.fixedAt(planet, SystemBodyKind.PLANET, 3, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(ship, 42), ledger.snapshot(), lookupIn(ship, body));

        assertEquals("only the live cell's slot dim is keyed", 1, byDim.size());
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
        SystemBody star = SystemBody.fixedAt(body, SystemBodyKind.STAR, Constants.INVALID_PLANET, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(ship, 9), ledger.snapshot(), lookupIn(ship, star));

        RenderBody rb = byDim.get(9).get(0);
        assertEquals("dx carries one CELL step minus the local offset", GalacticCoord.CELL - 100L, rb.localX);
        assertEquals(0L, rb.localY);
        assertEquals(0L, rb.localZ);
    }

    @Test
    public void nonDescendBodyCarriesDescendTargetFalse() {
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 5L, 0L, 0L);
        GalacticCoord beltCoord = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        SystemBody belt = SystemBody.fixedAt(beltCoord, SystemBodyKind.ASTEROID_BELT, Constants.INVALID_PLANET, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(ship, 3), ledger.snapshot(), lookupIn(ship, belt));

        RenderBody rb = byDim.get(3).get(0);
        assertEquals("kind propagated", SystemBodyKind.ASTEROID_BELT.ordinal(), rb.kindOrdinal);
        assertFalse("a belt with no real dim is not a descend target", rb.descendTarget);
    }

    @Test
    public void aLiveCellWhoseOnlyShipIsMidJumpStillShowsItsBodies() {
        // The measured shape of the blank-sky report: a pilot sitting in a live cell world whose ship
        // the ledger calls IN_TRANSIT (a jump that began, or one whose arrival crossing stranded it).
        // The cell is bound to a slot, it has six bodies in it, and it is what he is looking at - so
        // the sky is the cell's, not the ship's lifecycle stage's. Keyed off the ship's state, every
        // one of those bodies vanished from his sky and the blank was indistinguishable from a void.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(57L, 0L, 5L, 0L, 0L, 0L);
        GalacticCoord shipPos = GalacticCoord.ofSectorLocal(57L, 0L, 5L, 125L, 0L, -1016L);
        SystemBody moon = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(57L, 0L, 5L, 2900L, 0L, 0L),
                SystemBodyKind.MOON, 4, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.beginTransit(UUID.randomUUID(), shipPos);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(cell, 107), ledger.snapshot(), lookupIn(cell, moon));

        assertEquals("the live cell is keyed whatever its ship is doing", 1, byDim.size());
        assertEquals("and it carries the cell's body", 1, byDim.get(107).size());
        assertEquals("dimId propagated", 4, byDim.get(107).get(0).dimId);
        // Its coordinate is a real point in the cell, so it is a better bearing than the centre.
        assertEquals("bearing measured from the ship that is there", 2900L - 125L,
                byDim.get(107).get(0).localX);
    }

    @Test
    public void aShipMidJumpKeysNoDimensionOfItsOwn() {
        // The other half of the same rule: hyperspace is not a cell, so a ship parked in it adds no
        // dimension to the feed. Only the cells that are live key anything, and here none is.
        ShipLedger ledger = new ShipLedger();
        ledger.beginTransit(UUID.randomUUID(), GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L));

        SystemBody body = SystemBody.fixedAt(GalacticCoord.ORIGIN, SystemBodyKind.PLANET, 3, 7);
        BodyLookup always = new BodyLookup() {
            @Override
            public List<SystemBody> skyBodiesAt(GalacticCoord cell) {
                return Collections.singletonList(body);
            }
        };

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                new HashMap<String, Integer>(), ledger.snapshot(), always);
        assertTrue("with no cell live there is no sky to key", byDim.isEmpty());
    }

    @Test
    public void aLiveCellWithNoShipInItIsStillFedFromItsCentre() {
        // Nobody's ship is in this cell at all - it is live because someone is standing in it (a crew
        // member whose ship departed without him, a passenger, a player put there by an on-ramp). His
        // sky is the cell's, measured from the only point that is his if no ship is: the cell centre.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(4L, 1L, 2L, 0L, 0L, 0L);
        SystemBody planet = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(4L, 1L, 2L, 0L, 5000L, 0L),
                SystemBodyKind.PLANET, 3, 7);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(cell, 55), new ShipLedger().snapshot(), lookupIn(cell, planet));

        assertEquals("the cell is keyed with no ship anywhere", 1, byDim.size());
        assertEquals(1, byDim.get(55).size());
        assertEquals("direction from the cell centre", 5000L, byDim.get(55).get(0).localY);
    }

    @Test
    public void aSettledShipIsPreferredOverAParkedOneAsTheObserver() {
        // Two ledger entries name the same cell: one really parked there, one whose coordinate is only
        // its jump TARGET. The settled one is the ship that is physically in the cell, so it owns the
        // bearing; picking the other would aim everyone's sky from a ship that is not there yet.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord settledAt = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 700L, 0L, 0L);
        GalacticCoord inboundTo = GalacticCoord.ofSectorLocal(0L, 0L, 0L, -900L, 0L, 0L);
        SystemBody planet = SystemBody.fixedAt(cell, SystemBodyKind.PLANET, 3, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.beginTransit(UUID.randomUUID(), inboundTo);
        ledger.settle(UUID.randomUUID(), settledAt);

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(cell, 6), ledger.snapshot(), lookupIn(cell, planet));

        assertEquals("bearing taken from the SETTLED ship", -700L, byDim.get(6).get(0).localX);
    }

    @Test
    public void aLiveCellWithNoBodyIsKeyedWithAnEmptyList() {
        // A live cell that holds no body must still key its slot dim with an EMPTY list, so the client
        // clears any stale bodies for that dim and draws just the boundary ring.
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 12L, 0L, 0L);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        BodyLookup empty = new BodyLookup() {
            @Override
            public List<SystemBody> skyBodiesAt(GalacticCoord cell) {
                return new ArrayList<>();
            }
        };

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                live(ship, 8), ledger.snapshot(), empty);
        assertEquals("the void cell still keys its slot dim", 1, byDim.size());
        assertNotNull("slot dim 8 present", byDim.get(8));
        assertTrue("with an empty body list", byDim.get(8).isEmpty());
    }

    @Test
    public void everyLiveCellKeysItsOwnSlotDim() {
        GalacticCoord shipA = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 10L, 0L, 0L);
        GalacticCoord shipB = GalacticCoord.ofSectorLocal(5L, 0L, 0L, 0L, 0L, 0L);
        final SystemBody planetA = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L),
                SystemBodyKind.PLANET, 3, 7);
        final SystemBody planetB = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(5L, 0L, 0L, 0L, 0L, 0L),
                SystemBodyKind.MOON, 4, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), shipA);
        ledger.settle(UUID.randomUUID(), shipB);

        BodyLookup lookup = new BodyLookup() {
            @Override
            public List<SystemBody> skyBodiesAt(GalacticCoord cell) {
                if (cell.sameCell(planetA.name())) {
                    return Collections.singletonList(planetA);
                }
                return cell.sameCell(planetB.name())
                        ? Collections.singletonList(planetB) : Collections.<SystemBody>emptyList();
            }
        };

        Map<String, Integer> bound = new LinkedHashMap<>();
        bound.put(shipA.cellKey(), 100);
        bound.put(shipB.cellKey(), 200);

        Map<Integer, List<RenderBody>> byDim =
                SystemBodiesProducer.buildByDim(bound, ledger.snapshot(), lookup);
        assertEquals("both live cells keyed", 2, byDim.size());
        assertEquals(3, byDim.get(100).get(0).dimId);
        assertEquals(4, byDim.get(200).get(0).dimId);
    }

    @Test
    public void aShipWhoseCellIsInNoSlotContributesNothing() {
        // A ship can be settled — the server knows exactly where it is — while its cell is bound to no
        // slot world at all (evicted, or not yet re-materialized after a restart). There is then no
        // dimension to key its sky under, and the only wrong answer is to invent one: keying the feed
        // to a stale or borrowed id points a cell's bodies at a world holding somebody else's cell.
        GalacticCoord ship = GalacticCoord.ofSectorLocal(4L, 0L, 0L, 0L, 0L, 0L);
        SystemBody planet = SystemBody.fixedAt(ship, SystemBodyKind.PLANET, 3, 7);

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        // Bound: it is keyed. The same fixture with the binding removed is the control.
        assertEquals("control: with its cell in a slot the cell IS keyed", 1,
                SystemBodiesProducer.buildByDim(live(ship, 12), ledger.snapshot(),
                        lookupIn(ship, planet)).size());

        Map<Integer, List<RenderBody>> byDim = SystemBodiesProducer.buildByDim(
                new HashMap<String, Integer>(), ledger.snapshot(), lookupIn(ship, planet));
        assertTrue("a ship whose cell is in no slot keys no dimension at all", byDim.isEmpty());
    }

    @Test
    public void anUnboundOrMalformedBindingIsNeverKeyed() {
        // The "no world" sentinel must never become a dimension key, and neither must a cell key the
        // coordinate parser cannot read back - both would put a body list under an id nothing renders.
        GalacticCoord cell = GalacticCoord.ofSectorLocal(1L, 1L, 1L, 0L, 0L, 0L);
        SystemBody planet = SystemBody.fixedAt(cell, SystemBodyKind.PLANET, 3, 7);

        Map<String, Integer> hostile = new LinkedHashMap<>();
        hostile.put(cell.cellKey(), SpaceManager.UNBOUND_SLOT);
        hostile.put("not-a-cell-key", 77);

        assertTrue("neither an unbound nor an unparseable cell keys anything",
                SystemBodiesProducer.buildByDim(hostile, new ShipLedger().snapshot(),
                        lookupIn(cell, planet)).isEmpty());
    }

    // ── The frames: what the sky shows is where things ARE, not where their names say ────────────

    /**
     * A planet whose cell rides a real orbit: one orbit-unit out, a 400-tick period, so tick 0 puts
     * it on +X and tick 100 — a quarter turn — swings that whole radius onto +Z. Each BODY carries
     * its own frame; the injected {@link CellFrames} answers only the OBSERVER's cell, and in
     * production both come from the same registry, so the two halves of the vector cannot disagree.
     */
    private static SystemBody planetOnAQuarterTurnOrbit(GalacticCoord name) {
        return new SystemBody(name,
                CellFrame.of(AbsolutePos.ofCellName(name),
                        BodyEphemeris.orbit(1d, 0d, 0d, false, 400d, 1_000_000L)),
                BodyEphemeris.STATIC, SystemBodyKind.PLANET, 3, 7);
    }

    /**
     * Direction AND distance, evaluated live. The vector a body is carried as is measured through
     * BOTH frames at the broadcast tick, so its length is the real distance right then — which is
     * what makes "the planet is receding" something the sky can show at all. Over the static grid
     * the same two names are the same distance apart forever.
     */
    @Test
    public void aBodyInAMovingCellIsFedFromWhereItIsNotFromWhereItsNameSays() {
        GalacticCoord here = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord elsewhere = GalacticCoord.ofSectorLocal(3L, 0L, 0L, 0L, 0L, 0L);
        SystemBody planet = planetOnAQuarterTurnOrbit(elsewhere);

        RenderBody early = SystemBodiesProducer.buildByDim(live(here, 5), new ShipLedger().snapshot(),
                lookupIn(here, planet), CellFrames.STATIC, 0L).get(5).get(0);
        RenderBody late = SystemBodiesProducer.buildByDim(live(here, 5), new ShipLedger().snapshot(),
                lookupIn(here, planet), CellFrames.STATIC, 100L).get(5).get(0);

        assertEquals("at tick 0 the body is one orbit-unit along +X of its cell",
                3L * GalacticCoord.CELL + 1_000_000L, early.localX);
        assertEquals("control: and nothing at all along +Z", 0L, early.localZ);
        assertEquals("a quarter turn later that whole radius has swung off +X",
                3L * GalacticCoord.CELL, late.localX);
        assertEquals("...and onto +Z", 1_000_000L, late.localZ);
    }

    /** The observer's OWN cell moves too, so a body in it stays exactly where it was relative to him. */
    @Test
    public void aBodyInTheObserversOwnMovingCellDoesNotDriftAwayFromHim() {
        final GalacticCoord cell = GalacticCoord.ofSectorLocal(7L, 0L, 0L, 0L, 0L, 0L);
        GalacticCoord ship = GalacticCoord.ofSectorLocal(7L, 0L, 0L, 900L, 0L, 0L);
        final SystemBody planet = planetOnAQuarterTurnOrbit(cell);
        // The observer stands in the SAME cell, so his position resolves through the same frame.
        CellFrames observerFrames = new CellFrames() {
            @Override
            public AbsolutePos originAt(GalacticCoord name, long tick) {
                return name.sameCell(cell) ? planet.frame().originAt(tick)
                        : AbsolutePos.ofCellName(name);
            }
        };

        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        long early = SystemBodiesProducer.buildByDim(live(cell, 5), ledger.snapshot(),
                lookupIn(cell, planet), observerFrames, 0L).get(5).get(0).localX;
        long late = SystemBodiesProducer.buildByDim(live(cell, 5), ledger.snapshot(),
                lookupIn(cell, planet), observerFrames, 100L).get(5).get(0).localX;

        assertEquals("a ship parked beside a planet is carried by the same frame the planet is",
                early, late);
        assertEquals(-900L, early);

        // The control: measured against a STATIC observer frame the same fixture drifts, so the
        // assertion above is about the frames cancelling and not about a planet that cannot move.
        long staticEarly = SystemBodiesProducer.buildByDim(live(cell, 5), ledger.snapshot(),
                lookupIn(cell, planet), CellFrames.STATIC, 0L).get(5).get(0).localX;
        long staticLate = SystemBodiesProducer.buildByDim(live(cell, 5), ledger.snapshot(),
                lookupIn(cell, planet), CellFrames.STATIC, 100L).get(5).get(0).localX;
        assertNotEquals("the fixture's cell must genuinely be in motion", staticEarly, staticLate);
    }

    /**
     * Ledger #151's half that lives on the server. A star has no dimension of its own and carried
     * {@code INVALID_PLANET}, which the client's lenient lookup answers with the OVERWORLD — so the
     * star was drawn wearing Earth's texture and would be captioned with Earth's name. An AUTHORED
     * star has a proxy dimension; a procedural one (negative star id) does not, and inventing an id
     * for it would point the client at another body again.
     */
    @Test
    public void anAuthoredStarIsFedItsOwnProxyDimensionAndAProceduralOneIsNot() {
        GalacticCoord cell = GalacticCoord.ORIGIN;
        SystemBody authored = SystemBody.fixedAt(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, 4);
        SystemBody procedural = SystemBody.fixedAt(cell, SystemBodyKind.STAR, Constants.INVALID_PLANET, -9);
        GalacticCoord ship = GalacticCoord.ofSectorLocal(0L, 0L, 0L, 500L, 0L, 0L);
        ShipLedger ledger = new ShipLedger();
        ledger.settle(UUID.randomUUID(), ship);

        List<RenderBody> fed = SystemBodiesProducer.buildByDim(live(cell, 5), ledger.snapshot(),
                lookupIn(cell, authored, procedural)).get(5);

        assertEquals("an authored star is fed its own star-proxy dimension",
                Constants.STAR_ID_OFFSET + 4, fed.get(0).dimId);
        assertEquals("a procedural star has no proxy, so it stays unidentified rather than borrowing one",
                Constants.INVALID_PLANET, fed.get(1).dimId);
    }

    @Test
    public void nullInputsYieldEmptyMap() {
        GalacticCoord cell = GalacticCoord.ORIGIN;
        assertTrue("no bindings at all means no feed",
                SystemBodiesProducer.buildByDim(null, new ShipLedger().snapshot(),
                        lookupIn(cell)).isEmpty());
        assertTrue("no body source means no feed",
                SystemBodiesProducer.buildByDim(live(cell, 1), new ShipLedger().snapshot(), null)
                        .isEmpty());
        // A missing ledger is NOT a missing feed: the cell is live, so its sky is drawn - from the
        // cell centre, because there is no ship to measure it from.
        SystemBody planet = SystemBody.fixedAt(GalacticCoord.ofSectorLocal(0L, 0L, 0L, 0L, 800L, 0L),
                SystemBodyKind.PLANET, 3, 7);
        Map<Integer, List<RenderBody>> byDim =
                SystemBodiesProducer.buildByDim(live(cell, 1), null, lookupIn(cell, planet));
        assertEquals("a live cell is fed even with no ledger", 1, byDim.size());
        assertEquals(800L, byDim.get(1).get(0).localY);
    }
}
