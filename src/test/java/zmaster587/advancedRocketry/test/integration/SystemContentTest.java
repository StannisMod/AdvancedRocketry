package zmaster587.advancedRocketry.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.SystemBody;
import zmaster587.advancedRocketry.universe.SystemBodyKind;
import zmaster587.advancedRocketry.universe.SystemContent;
import zmaster587.advancedRocketry.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Authored system content (universe-model.md &sect;2 A#1a + &sect;4): a catalogued {@link StellarBody} with
 * planets resolves to addressable {@link SystemBody} data — star at the anchor, each planet in its OWN cell
 * (snapped to the cell centre) inside the anchor's super-cell box — and a planet dim resolves to its own
 * cell through the registry. Needs {@link MinecraftBootstrap} for {@link DimensionProperties} construction.
 * Scale constants are {@code tunable} and never pinned; only the placement SHAPE is.
 */
public class SystemContentTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @After
    public void resetSeams() {
        UniverseRegistry.setStarLookup(null);
        UniverseRegistry.setGenerator(null);
    }

    /**
     * A body at orbital angle {@code theta}. BOTH angle fields are set, and that is the physically
     * consistent state rather than belt-and-braces: {@code baseOrbitTheta} is the authored angle a
     * body's durable cell name is derived from, {@code orbitTheta} is the live angle the world
     * advances every tick, and at tick zero the live one IS the base one. A fixture that set only the
     * live field described a body whose AUTHORED position was the +X axis whatever angle it asked
     * for — invisible while an address was derived live, and decisive once it is not.
     */
    private static DimensionProperties planet(int dimId, int orbitalDist, double theta) {
        DimensionProperties p = new DimensionProperties(dimId);
        p.orbitalDist = orbitalDist;
        p.baseOrbitTheta = theta;
        p.orbitTheta = theta;
        p.orbitalPhi = 0;
        return p;
    }

    @Test
    public void authoredPlanetsGetTheirOwnCellsInsideTheSuperCellBox() {
        StellarBody star = new StellarBody();
        star.setId(4242);
        star.setName("TestStar");
        planet(700, 100, 0.0).setStar(star);       // setStar back-adds the planet to the star
        planet(701, 200, Math.PI / 2).setStar(star);

        GalacticCoord anchor = GalacticCoord.ofSectorLocal(10, 20, 30, 0, 0, 0);
        long s = GalaxyGenConfig.DEFAULT_MIN_SPACING;
        List<SystemBody> bodies = SystemContent.bodiesOf(star, anchor);

        assertEquals("first body is the star at the anchor cell", SystemBodyKind.STAR, bodies.get(0).kind());
        assertTrue(bodies.get(0).address().sameCell(anchor));
        assertEquals(0, bodies.get(0).address().localX());

        int planets = 0;
        SystemBody aPlanet = null;
        for (SystemBody b : bodies) {
            assertEquals("every body belongs to the star", 4242, b.starId());
            // Snapped to its own cell's centre (zone content sits near the cell centre — A#1a).
            assertEquals(0, b.address().localX());
            assertEquals(0, b.address().localY());
            assertEquals(0, b.address().localZ());
            // Inside the anchor's super-cell box, so member attribution stays exact.
            assertEquals(Math.floorDiv(anchor.sectorX(), s), Math.floorDiv(b.address().sectorX(), s));
            assertEquals(Math.floorDiv(anchor.sectorY(), s), Math.floorDiv(b.address().sectorY(), s));
            assertEquals(Math.floorDiv(anchor.sectorZ(), s), Math.floorDiv(b.address().sectorZ(), s));
            if (b.kind() == SystemBodyKind.PLANET) {
                planets++;
                aPlanet = b;
                assertFalse("a planet sits in its OWN cell, not the anchor's (A#1a)",
                        b.address().sameCell(anchor));
            }
        }
        assertEquals("both authored planets become bodies", 2, planets);
        assertNotNull(aPlanet);
        assertTrue("an authored planet body is a descend target (real dim)", aPlanet.isDescendTarget());
        assertTrue(aPlanet.dimId() == 700 || aPlanet.dimId() == 701);

        // Distinct orbits land in distinct cells (per-body cells are real, not a shared one).
        SystemBody first = null;
        for (SystemBody b : bodies) {
            if (b.kind() != SystemBodyKind.PLANET) {
                continue;
            }
            if (first == null) {
                first = b;
            } else {
                assertFalse("planets on different orbits sit in different cells",
                        b.address().sameCell(first.address()));
            }
        }
    }

    @Test
    public void planetResolvesToItsOwnCellThroughTheRegistry() {
        StellarBody star = new StellarBody();
        star.setId(4243);
        DimensionProperties p = planet(710, 120, 0.0);
        p.setStar(star);

        UniverseRegistry reg = new UniverseRegistry();
        GalacticCoord anchor = GalacticCoord.ofSectorLocal(5, 5, 5, 0, 0, 0);
        reg.place(anchor, 4243);

        // Without content resolution (star not in the catalogue) the seam falls back to the anchor.
        assertEquals("catalogue-miss fallback = the system anchor", Optional.of(anchor), reg.coordForPlanet(p));

        // With content resolvable, the planet resolves to its OWN cell (A#1a), which is where its body sits.
        UniverseRegistry.setStarLookup(id -> id == 4243 ? star : null);
        Optional<GalacticCoord> resolved = reg.coordForPlanet(p);
        assertTrue(resolved.isPresent());
        assertFalse("the planet's coord is its own zone cell, NOT the anchor (A#1a)",
                resolved.get().sameCell(anchor));

        GalacticCoord bodyCell = null;
        for (SystemBody b : reg.systemBodiesAt(anchor)) {
            if (b.dimId() == 710) {
                bodyCell = b.address().cellCentre();
            }
        }
        assertNotNull(bodyCell);
        assertEquals("coordForPlanet agrees with the body's own cell", bodyCell, resolved.get());
    }

    /**
     * An authored orbit is in RADIANS, and a body must land where that orbit puts it. A quarter turn
     * is a quarter turn: the body belongs on the anchor's +Z axis, with its +X offset gone. Running
     * the angle through a degrees&rarr;radians conversion a second time collapsed every orbit into a
     * 6&deg; wedge, which parked every body in a system on the {@code x ≈ orbitalDist} line — one
     * cell apart, each against a cell boundary, so their addresses flipped under the slightest motion
     * and two bodies could share one.
     */
    @Test
    public void aQuarterTurnPutsTheBodyAQuarterTurnRound() {
        StellarBody star = new StellarBody();
        star.setId(4244);
        planet(720, 400, Math.PI / 2).setStar(star);

        GalacticCoord anchor = GalacticCoord.ORIGIN;
        SystemBody body = null;
        for (SystemBody b : SystemContent.bodiesOf(star, anchor)) {
            if (b.dimId() == 720) {
                body = b;
            }
        }
        assertNotNull(body);
        assertEquals("a quarter turn leaves no offset along +X", 0L, body.address().sectorX());
        assertTrue("...and puts the whole orbital radius along +Z", body.address().sectorZ() > 0L);
    }

    /**
     * A body with no surface is not somewhere a ship can put down, so it must not be advertised as
     * one. It stays a real, addressable destination — it owns a cell and keeps its dimension, which
     * is what a pilot flies to and what a survey reads — but the descent trigger, the nav GUI and the
     * render channel all read {@code isDescendTarget()} and must be told the truth by the one place
     * bodies are made. Advertised as landable, it sent a ship's descent into a dimension with no
     * terrain to find.
     */
    @Test
    public void aBodyWithNoSurfaceIsNotADescendTarget() {
        StellarBody star = new StellarBody();
        star.setId(4245);
        DimensionProperties gasGiant = planet(730, 250, 1.0);
        gasGiant.setGasGiant(true);
        gasGiant.setStar(star);
        planet(731, 120, 2.0).setStar(star);

        SystemBody giantBody = null;
        SystemBody planetBody = null;
        for (SystemBody b : SystemContent.bodiesOf(star, GalacticCoord.ORIGIN)) {
            if (b.dimId() == 730) {
                giantBody = b;
            } else if (b.dimId() == 731) {
                planetBody = b;
            }
        }
        assertNotNull(giantBody);
        assertNotNull(planetBody);
        assertFalse("a surface-less body is not somewhere a ship can land",
                giantBody.isDescendTarget());
        assertEquals("...but it is still a body, with its own dimension to fly to and survey",
                730, giantBody.dimId());
        assertTrue("a body with a surface is still landable", planetBody.isDescendTarget());
    }

    /**
     * A body's cell does NOT move with time. Half an orbit later — the moment its position is as far
     * from where it started as that body ever gets — it is still addressed by the same cell.
     *
     * <p>This test used to assert the opposite, in as many words: "half an orbit later the body is
     * somewhere else — an address is a moment". That was the model, and it was the bug. An address is
     * how a pilot names a destination, how a ship's arrival is recorded and what the sky of a cell is
     * built from; a name that expires while its owner is still there took planets out of a parked
     * ship's sky every few minutes and sent jumps to cells their target had left.</p>
     *
     * <p>What is still a function of time — a moon's position INSIDE its parent's cell, which is what
     * a navigation computer leads its aim by — is pinned by
     * {@link #aMoonIsAimedAtWhereItIsNotAtItsParentsCellCentre}.</p>
     */
    @Test
    public void aBodysCellIsTheSameCellHalfAnOrbitLater() {
        StellarBody star = new StellarBody();
        star.setId(4246);
        star.setSize(1f);
        DimensionProperties p = planet(740, 100, 0.0);
        p.setStar(star);

        // Half an orbital period: the far side of the star, i.e. the largest displacement this body
        // ever has from where it began. Which tick that is comes from the body's own orbit, so this
        // pins the DURABILITY of a name, never a particular period.
        long halfPeriodTicks = (long) (24000d
                * AstronomicalBodyHelper.getOrbitalPeriod(100, 1f) / 2d);

        GalacticCoord nowCell = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN,
                GalaxyGenConfig.DEFAULT_MIN_SPACING, 0L), 740);
        GalacticCoord laterCell = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN,
                GalaxyGenConfig.DEFAULT_MIN_SPACING, halfPeriodTicks), 740);

        assertNotNull(nowCell);
        assertNotNull(laterCell);
        assertTrue("half an orbit later the body is still addressed by the same cell",
                nowCell.sameCell(laterCell));
    }

    /**
     * The negative leg of the clause above, and the reason it is not satisfiable by a constant: a
     * durable name is derived from the body's AUTHORED orbit, so authoring a different orbit gives a
     * different name. Without this, "the name never changes" would be passed by a derivation that
     * returned the same cell for every body in the universe.
     */
    @Test
    public void aDifferentAuthoredOrbitIsADifferentCell() {
        StellarBody star = new StellarBody();
        star.setId(4256);
        star.setSize(1f);
        planet(745, 100, 0.0).setStar(star);
        planet(746, 100, Math.PI).setStar(star);

        GalacticCoord first = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 745);
        GalacticCoord second = cellOf(SystemContent.bodiesOf(star, GalacticCoord.ORIGIN), 746);

        assertNotNull(first);
        assertNotNull(second);
        assertFalse("two bodies authored on opposite sides of one star are not one address",
                first.sameCell(second));
    }

    /**
     * A recorded name WINS over the derivation, for every later query.
     *
     * <p>This is what makes a name durable in the only sense that matters to a player: a coordinate
     * he wrote down still denotes his planet after the authored data has been re-saved (the angles
     * round-trip through the world's XML), after a spacing change, and after any later edit to the
     * derivation itself. A name that is merely re-derived consistently is only as stable as its
     * inputs, and those inputs are known to move.</p>
     */
    @Test
    public void aRecordedNameBeatsAFreshDerivation() {
        StellarBody star = new StellarBody();
        star.setId(4257);
        star.setSize(1f);
        planet(747, 100, 0.0).setStar(star);

        final GalacticCoord recorded = GalacticCoord.ofSectorLocal(77L, -3L, 12L, 0L, 0L, 0L);
        List<SystemBody> bodies = SystemContent.bodiesOf(star, GalacticCoord.ORIGIN,
                GalaxyGenConfig.DEFAULT_MIN_SPACING, SystemContent.NOW,
                new SystemContent.CellNames() {
                    @Override
                    public GalacticCoord nameFor(int dimId, GalacticCoord derived) {
                        return dimId == 747 ? recorded : derived;
                    }
                });

        GalacticCoord actual = cellOf(bodies, 747);
        assertNotNull(actual);
        assertTrue("the store's name is the body's name, whatever the derivation would have said",
                recorded.sameCell(actual));
    }

    /**
     * A moon's ADDRESS is the moon, not the middle of the cell it shares with its parent.
     *
     * <p>Both answers are wanted and they are not the same one. "Which cell is this body in"
     * (cell-centred) is right for attribution and for anything comparing cell keys. "Where do I aim a
     * ship at it" has to be the body's own position: a moon sits tens of thousands of blocks off its
     * parent's cell centre — far beyond a descent's reach — so a ship flown to the cell arrives at the
     * PARENT, and the pilot who picked the moon can never put down on it.</p>
     */
    @Test
    public void aMoonIsAimedAtWhereItIsNotAtItsParentsCellCentre() {
        StellarBody star = new StellarBody();
        star.setId(4247);
        star.setSize(1f);
        DimensionProperties parent = planet(750, 200, 0.5);
        parent.gravitationalMultiplier = 1f;
        DimensionProperties moon = planet(751, 127, 0.9);
        DimensionManager.getInstance().setDimProperties(750, parent);
        DimensionManager.getInstance().setDimProperties(751, moon);
        parent.setStar(star);
        moon.setParentPlanet(parent);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 4247);
        UniverseRegistry.setStarLookup(id -> id == 4247 ? star : null);

        Optional<GalacticCoord> cell = reg.coordForPlanet(moon);
        Optional<GalacticCoord> aim = reg.addressForPlanet(moon, SystemContent.NOW);
        assertTrue(cell.isPresent());
        assertTrue(aim.isPresent());

        assertTrue("the moon is addressed inside its parent's cell", aim.get().sameCell(cell.get()));
        assertTrue("...and a ship dropped at that cell's centre would be nowhere near the moon",
                aim.get().distanceTo(cell.get()) > 1000d);
    }

    /**
     * A body on the NEGATIVE side of its star belongs to that star's system, exactly like one on the
     * positive side.
     *
     * <p>A system's neighbourhood is the box centred on its anchor — that is where bodies are placed.
     * Attributing a cell back by looking it up in a fixed grid of super-cubes asks a different
     * question, and for the home system, whose anchor sits at sector 0, every negative-offset orbit
     * lands in the neighbouring cube and resolves to NO system: an address the console will happily
     * offer, with nothing at it, that a ship can fly to and never descend from.</p>
     */
    @Test
    public void aBodyBehindItsStarStillBelongsToThatSystem() {
        StellarBody star = new StellarBody();
        star.setId(4248);
        // Half a turn round: straight down the anchor's NEGATIVE X axis.
        planet(760, 300, Math.PI).setStar(star);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, 4248);
        UniverseRegistry.setStarLookup(id -> id == 4248 ? star : null);

        GalacticCoord bodyCell = cellOf(reg.systemBodiesAt(GalacticCoord.ORIGIN), 760);
        assertNotNull(bodyCell);
        assertTrue("the fixture must actually put the body behind the star", bodyCell.sectorX() < 0L);

        assertTrue("its own cell must attribute back to its system",
                reg.anchorForCell(bodyCell).isPresent());
        assertEquals("...to THAT system's anchor", GalacticCoord.ORIGIN,
                reg.anchorForCell(bodyCell).get());
        assertFalse("...and the cell must report the body standing in it",
                reg.bodiesAt(bodyCell).isEmpty());
    }

    private static GalacticCoord cellOf(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return b.address();
            }
        }
        return null;
    }
}
