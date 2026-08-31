package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The bottom rung of the repair ladder: one use of the welder takes one stage of damage off a block,
 * paid for in that block's own materials and in charge — and every way it can refuse is a different,
 * visible answer that costs the player nothing.
 *
 * <p>What is pinned here is the CONTRACT (C20 REPAIR-1, REPAIR-3, REPAIR-7), not the price list. The
 * assertions say that material left the inventory, never how much: the fraction charged per stage is
 * a tuned number, and a test that pinned it would fail the first time somebody balanced the game
 * rather than the first time somebody broke it.</p>
 */
public class RepairWelderE2ETest extends AbstractSharedServerTest {

    /** A site of this class's own, clear of the ship scenarios. */
    private static final int X = 8000, Y = 80, Z = 7200;

    /** Crafted from nine ingots, so it has a recipe to be priced against. */
    private static final String SUBJECT = "minecraft:iron_block";
    private static final String MATERIAL = "minecraft:iron_ingot";
    /** Smelted, never crafted — so nothing can price a repair of it. */
    private static final String UNPRICEABLE = "minecraft:stone";

    private static final int PLENTY_OF_CHARGE = 100000;
    private static final int PLENTY_OF_MATERIAL = 64;

    @Test
    public void oneUseTakesOneStageAndIsPaidForTwice() throws Exception {
        int x = X, y = Y, z = Z;
        int damaged = placeAndDamage(x, y, z, SUBJECT, 2, 78001);
        assertTrue("the subject must be damaged but not destroyed, or the welder has nothing to do "
                + "or nothing to do it to (stage " + damaged + ")", damaged >= 1);

        String weld = weld(x, y, z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("the welder refused a damaged, priceable block: " + weld,
                "REPAIRED", extractString(weld, "outcome"));
        assertEquals("one use must remove exactly one stage: " + weld,
                damaged - 1, extractInt(weld, "stageAfter"));
        assertTrue("the repair took no material — nothing may be created from nothing: " + weld,
                extractInt(weld, "materialAfter") < extractInt(weld, "materialBefore"));
        assertTrue("the repair took no charge: " + weld,
                extractInt(weld, "energyAfter") < extractInt(weld, "energyBefore"));
    }

    @Test
    public void everyRefusalIsItsOwnAnswerAndCostsNothing() throws Exception {
        // Each case gets its own block: a refusal that quietly consumed something would otherwise be
        // hidden by the next case's fresh inventory.
        int damaged = placeAndDamage(X + 4, Y, Z, SUBJECT, 2, 78002);
        String noMaterials = weld(X + 4, Y, Z, PLENTY_OF_CHARGE, "none", 0);
        assertEquals("an empty inventory must be told apart from every other refusal: " + noMaterials,
                "NO_MATERIALS", extractString(noMaterials, "outcome"));
        assertEquals("a refused repair changed the block anyway: " + noMaterials,
                damaged, extractInt(noMaterials, "stageAfter"));
        assertEquals("a refused repair spent charge: " + noMaterials,
                extractInt(noMaterials, "energyBefore"), extractInt(noMaterials, "energyAfter"));

        int stillDamaged = placeAndDamage(X + 8, Y, Z, SUBJECT, 2, 78003);
        String noCharge = weld(X + 8, Y, Z, 0, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("a flat tool must be told apart from an empty inventory: " + noCharge,
                "NO_CHARGE", extractString(noCharge, "outcome"));
        assertEquals("a refused repair changed the block anyway: " + noCharge,
                stillDamaged, extractInt(noCharge, "stageAfter"));
        assertEquals("a refused repair took materials: " + noCharge,
                extractInt(noCharge, "materialBefore"), extractInt(noCharge, "materialAfter"));

        place(X + 12, Y, Z, SUBJECT);
        String undamaged = weld(X + 12, Y, Z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("an undamaged block must not read as a failed repair: " + undamaged,
                "UNDAMAGED", extractString(undamaged, "outcome"));
        assertEquals("welding an undamaged block took materials: " + undamaged,
                extractInt(undamaged, "materialBefore"), extractInt(undamaged, "materialAfter"));

        placeAndDamage(X + 16, Y, Z, UNPRICEABLE, 2, 78004);
        String noRecipe = weld(X + 16, Y, Z, PLENTY_OF_CHARGE, MATERIAL, PLENTY_OF_MATERIAL);
        assertEquals("a block nothing crafts must say so rather than be repaired for free or refused "
                + "as if the player were empty-handed: " + noRecipe,
                "NO_RECIPE", extractString(noRecipe, "outcome"));
    }

    /**
     * Put {@code block} down and shoot it for {@code stages} stages, returning the stage it ended at.
     * The budget comes from what production itself charges per stage, so this survives retuning
     * instead of pinning today's number.
     */
    private int placeAndDamage(int x, int y, int z, String block, int stages, int impactId) throws Exception {
        place(x, y, z, block);
        int stageCost = extractInt(exec("artest damage stage 0 " + x + " " + y + " " + z), "stageCost");
        assertTrue("no stage cost for " + block, stageCost > 0);
        String shot = exec("artest damage impact 0 " + (x + 0.5) + " " + (y + 4) + " " + (z + 0.5)
                + " 0 -1 0 " + (stageCost * stages) + " KINETIC " + impactId);
        assertTrue("the shot missed the subject block: " + shot, readLong(shot, "spent") > 0);
        return extractInt(exec("artest damage stage 0 " + x + " " + y + " " + z), "stage");
    }

    private void place(int x, int y, int z, String block) throws Exception {
        assertTrue("chunk warmup failed", exec("artest chunk warmup 0 " + ((x - 2) >> 4) + " "
                + ((z - 2) >> 4) + " " + ((x + 20) >> 4) + " " + ((z + 2) >> 4)).contains("\"ok\":true"));
        assertTrue("could not clear the site", exec("artest fill 0 " + (x - 1) + " " + y + " " + (z - 1)
                + " " + (x + 1) + " " + (y + 6) + " " + (z + 1) + " minecraft:air").contains("\"ok\":true"));
        assertTrue("could not place " + block, exec("artest fill 0 " + x + " " + y + " " + z
                + " " + x + " " + y + " " + z + " " + block).contains("\"ok\":true"));
    }

    private String weld(int x, int y, int z, int charge, String material, int count) throws Exception {
        String reply = exec("artest damage weld 0 " + x + " " + y + " " + z + " " + charge
                + " " + material + " " + count);
        assertTrue("the weld probe failed: " + reply, reply.contains("\"ok\":true"));
        return reply;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static long readLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue("no " + key + " field in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
