package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * How much substance a block IS, read off the block itself.
 *
 * <p>A slug's capacity and, later, a block's melting point are both {@code rho * c * dT * V}, and
 * this is where the V comes from: the block's own collision boxes, in the world, as the player built
 * it. Nothing about the volume is authored - which is why these scenarios are worth a real server
 * rather than a table lookup. The shapes are chosen to separate the two things that could stand in
 * for each other:</p>
 *
 * <ul>
 *   <li>a <b>slab</b> is half a block, and its bounding box says so too - so it alone cannot tell
 *       the collision read from the outline read;</li>
 *   <li><b>stairs</b> are three quarters, and their bounding box says a FULL cube
 *       ({@code Block.getBoundingBox} defaults to it). This is the scenario that separates them, and
 *       a build that measured the outline reports a whole block here.</li>
 * </ul>
 */
public class ThermalMaterialVolumeTest extends AbstractSharedServerTest {

    private static final int Y = 70;
    private static final int Z = 3300;
    private static final int X_FULL = 2000;
    private static final int X_SLAB = 2010;
    private static final int X_STAIRS = 2020;
    private static final int X_AIR = 2030;
    private static final int X_GOLD = 2040;

    /** One cubic metre in the millilitres the probe reports. */
    private static final long WHOLE_BLOCK = 1_000_000L;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(json);
        assertTrue("expected a numeric field " + name + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private String placeAndRead(int x, String block) throws Exception {
        String placed = exec("artest place 0 " + x + " " + Y + " " + Z + " " + block);
        assertTrue("could not place " + block + ": " + placed, !placed.contains("\"error\""));
        return exec("artest heat material 0 " + x + " " + Y + " " + Z);
    }

    @Test
    public void aWholeBlockIsAWholeCubicMetreOfItsSubstance() throws Exception {
        String iron = placeAndRead(X_FULL, "minecraft:iron_block");

        assertEquals("a full block is a cubic metre: " + iron, WHOLE_BLOCK,
                field(iron, "volumeMilliLitres"));
        assertTrue("and iron is a substance the table knows, so it has a capacity: " + iron,
                field(iron, "capacity") > 0);
    }

    @Test
    public void aSlabIsHalfABlockOfIt() throws Exception {
        String slab = placeAndRead(X_SLAB, "minecraft:stone_slab");

        assertEquals("half the shape is half the substance: " + slab, WHOLE_BLOCK / 2,
                field(slab, "volumeMilliLitres"));
    }

    /** The discriminator: the outline of a staircase is a full cube, and its substance is not. */
    @Test
    public void stairsAreThreeQuartersBecauseTheirCOLLISIONSaysSoAndTheirOutlineDoesNot()
            throws Exception {
        String stairs = placeAndRead(X_STAIRS, "minecraft:stone_stairs");

        long volume = field(stairs, "volumeMilliLitres");
        assertTrue("a staircase must not read as a whole block - that is what its bounding box says,"
                + " and the bounding box is not what it is made of: " + stairs, volume < WHOLE_BLOCK);
        assertEquals("it is the half slab plus the quarter step: " + stairs,
                3 * WHOLE_BLOCK / 4, volume);
    }

    /**
     * The same half-block, held rather than placed. Nothing in the ore dictionary describes a stone
     * slab, so without this the thing in your hand is nothing at all - while the identical block on
     * the ground is half a cubic metre.
     */
    @Test
    public void aSlabInTheHandIsTheSameHalfBlockAsASlabOnTheGround() throws Exception {
        String held = exec("artest heat item minecraft:stone_slab");

        assertEquals("an item the ore dictionary cannot name still has the shape of what it places: "
                + held, WHOLE_BLOCK / 2, field(held, "volumeMilliLitres"));
    }

    /**
     * The substance chain's second link. Vanilla names no stone in the ore dictionary, so without the
     * block's own {@code Material} a stone slab has a size and no identity - and a size alone answers
     * nothing, because capacity is the two multiplied.
     */
    @Test
    public void aBlockTheOreDictionaryNeverNamedStillKnowsWhatItIsMadeOf() throws Exception {
        String slab = placeAndRead(X_SLAB, "minecraft:stone_slab");

        assertTrue("stone must resolve through the block's own vanilla material: " + slab,
                slab.contains("\"material\":\"stone\""));
        assertTrue("and having both halves, it must have a capacity: " + slab,
                field(slab, "capacity") > 0);
    }

    /**
     * Precedence, on a block where the two sources DISAGREE - which is the only kind that can measure
     * it. A gold block is {@code blockGold} in the ore dictionary and {@code Material.IRON} to vanilla,
     * because that material means "metal-looking" and nothing finer. Asked on an IRON block this
     * assertion would pass whichever source won, which is a test that cannot fail.
     */
    @Test
    public void theOreDictionaryOutranksTheBlocksCoarseVanillaMaterial() throws Exception {
        String gold = placeAndRead(X_GOLD, "minecraft:gold_block");

        assertTrue("the specific name must win over the coarse one: " + gold,
                gold.contains("\"material\":\"gold\""));
    }

    @Test
    public void thereIsNoSubstanceInEmptySpace() throws Exception {
        String air = placeAndRead(X_AIR, "minecraft:air");

        assertEquals("air is not a small lump of something: " + air, 0L,
                field(air, "volumeMilliLitres"));
        assertEquals("and it can hold no heat", 0L, field(air, "capacity"));
    }
}
