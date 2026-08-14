package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * What the star model can EXPRESS: a single star, a close pair, a wide pair, and a hierarchy three
 * deep — each one placed, lit and round-tripped without a special case for its shape.
 *
 * <p>The model could nest companions in storage long before it could mean anything by them. A
 * companion was given its primary's id, so no {@code starId} could address it and it could own no
 * world; its separation was an angle, so nothing could say where it was; and every companion was lit
 * as though it stood exactly where its primary does. These pin the shape that replaced that, never
 * the balance numbers: what is asserted is that a distance is a distance, that identity is per star,
 * and that light falls off with the separation it is given.</p>
 */
public class StellarHierarchyTest {

    private static StellarBody star(String name, float size) {
        StellarBody s = new StellarBody();
        s.setName(name);
        s.setSize(size);
        s.setTemperature(100);
        return s;
    }

    // ─── identity ──────────────────────────────────────────────────────────────

    @Test
    public void bindingACompanionLeavesItsIdentityAlone() {
        // The whole reason a companion could own nothing: it was handed its primary's id, and a
        // planet binds to its star by that number. Minting one is the registry's job; binding is not
        // allowed to overwrite what the registry handed out.
        StellarBody primary = star("A", 1f);
        primary.setId(7);
        StellarBody companion = star("B", 0.5f);
        companion.setId(19);

        primary.addSubStar(companion);

        assertEquals("the primary keeps its id", 7, primary.getId());
        assertEquals("and so does the companion", 19, companion.getId());
        assertSame("which now knows what it orbits", primary, companion.getParentStar());
    }

    @Test
    public void aCompanionAnswersForItsOwnWorldsAndNotItsPrimarys() {
        StellarBody primary = star("A", 1f);
        StellarBody companion = star("B", 0.5f);
        primary.addSubStar(companion);

        assertEquals("a companion with no worlds holds none", 0, companion.getNumPlanets());
        assertEquals("and the primary's count is its own", 0, primary.getNumPlanets());
    }

    // ─── placement ─────────────────────────────────────────────────────────────

    @Test
    public void aCompanionStandsWhereItsOrbitSaysAndAPrimaryAtTheOrigin() {
        StellarBody primary = star("A", 1f);
        StellarBody companion = star("B", 0.5f);
        companion.setOrbitalDistance(2_000); // 20 AU
        companion.setBaseTheta(0d);
        primary.addSubStar(companion);

        assertEquals("a primary defines its system's origin", 0d,
                primary.offsetFromSystemAu()[0], 0d);
        assertEquals(20d, companion.offsetFromSystemAu()[0], 1e-9);
        assertEquals(20d, companion.separationAuFrom(primary), 1e-9);
        assertEquals("separation is symmetric", 20d, primary.separationAuFrom(companion), 1e-9);
    }

    @Test
    public void aThreeStarHierarchyComposesRatherThanSpecialCases() {
        // B orbits A at 20 AU; C orbits B at 5 AU on the same bearing. C is 25 AU from A, and the
        // arithmetic that says so is the same one a pair uses.
        StellarBody a = star("A", 1f);
        StellarBody b = star("B", 0.8f);
        StellarBody c = star("C", 0.3f);
        b.setOrbitalDistance(2_000);
        b.setBaseTheta(0d);
        c.setOrbitalDistance(500);
        c.setBaseTheta(0d);
        a.addSubStar(b);
        b.addSubStar(c);

        assertEquals(25d, c.separationAuFrom(a), 1e-9);
        assertEquals(5d, c.separationAuFrom(b), 1e-9);
        assertEquals("every star of the system is reached from any of them",
                3, AstronomicalBodyHelper.systemOf(c).size());
    }

    @Test
    public void unstatedCompanionPhasesAreSpreadRatherThanStacked() {
        // Two companions on the same bearing would be one object as far as every consumer is
        // concerned. Nothing here says WHICH angles they get — only that binding gives them
        // different ones when nobody has said.
        StellarBody primary = star("A", 1f);
        StellarBody first = star("B", 0.5f);
        StellarBody second = star("C", 0.5f);
        primary.addSubStar(first);
        primary.addSubStar(second);

        assertNotEquals(first.getBaseTheta(), second.getBaseTheta(), 1e-9);
    }

    @Test
    public void anAuthoredPhaseSurvivesBinding() {
        StellarBody primary = star("A", 1f);
        StellarBody companion = star("B", 0.5f);
        companion.setBaseTheta(1.25d);
        primary.addSubStar(companion);

        assertEquals(1.25d, companion.getBaseTheta(), 0d);
    }

    // ─── the sky ───────────────────────────────────────────────────────────────

    @Test
    public void apparentSeparationIsARealAngleFromARealDistance() {
        StellarBody primary = star("A", 1f);
        StellarBody close = star("B", 0.5f);
        close.setOrbitalDistance(5); // 0.05 AU
        primary.addSubStar(close);

        StellarBody other = star("C", 1f);
        StellarBody wide = star("D", 0.5f);
        wide.setOrbitalDistance(2_000); // 20 AU
        other.addSubStar(wide);

        float closeAngle = close.apparentSeparationDegrees(100);
        float wideAngle = wide.apparentSeparationDegrees(100);

        assertTrue("a close pair reads as two suns almost together, saw " + closeAngle,
                closeAngle > 0f && closeAngle < 10f);
        assertTrue("a wide companion is somewhere else in the sky entirely, saw " + wideAngle,
                wideAngle > 60f);
        assertEquals("a star nobody orbits has no separation from itself", 0f,
                primary.apparentSeparationDegrees(100), 0f);
    }

    // ─── round trip ────────────────────────────────────────────────────────────

    @Test
    public void aHierarchyRoundTripsThroughNBTWithItsGeometry() {
        StellarBody a = star("A", 1f);
        a.setId(3);
        StellarBody b = star("B", 0.8f);
        b.setId(4);
        b.setOrbitalDistance(2_000);
        b.setBaseTheta(0.75d);
        StellarBody c = star("C", 0.3f);
        c.setId(5);
        c.setOrbitalDistance(500);
        c.setBaseTheta(2.5d);
        a.addSubStar(b);
        b.addSubStar(c);

        NBTTagCompound nbt = new NBTTagCompound();
        a.writeToNBT(nbt);
        StellarBody read = new StellarBody();
        read.readFromNBT(nbt);

        assertEquals(1, read.getSubStars().size());
        StellarBody readB = read.getSubStars().get(0);
        assertEquals("a companion's own id survives", 4, readB.getId());
        assertEquals(2_000, readB.getOrbitalDistance());
        assertEquals(0.75d, readB.getBaseTheta(), 1e-9);
        assertSame("and it still knows what it orbits", read, readB.getParentStar());

        assertEquals(1, readB.getSubStars().size());
        StellarBody readC = readB.getSubStars().get(0);
        assertEquals(5, readC.getId());
        assertEquals(500, readC.getOrbitalDistance());
        assertEquals(2.5d, readC.getBaseTheta(), 1e-9);
        assertEquals("the geometry survives to the third star", c.separationAuFrom(a),
                readC.separationAuFrom(read), 1e-9);
    }
}
