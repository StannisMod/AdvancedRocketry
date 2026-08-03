package zmaster587.advancedRocketry.test.unit;

import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import zmaster587.advancedRocketry.network.EntityNetworkIds;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The entity spawn-network-id space is owned in one place and cannot hand the same number out twice.
 *
 * <p>This test fails if production breaks the contract that <b>no two entities registered by this jar
 * share a spawn network id, and an entity nobody declared cannot be registered at all</b>. Both
 * halves matter: the id -- not the registry name -- is what a client resolves an incoming spawn with,
 * it is scoped to the mod container that three code bases in this jar share, and Forge rejects a
 * duplicate nowhere. A collision makes the client build the wrong class and then read the sender's
 * synced fields out of it, which surfaces as a cast failure in an unrelated class on a later tick.</p>
 *
 * <p>The exact numbers are deliberately NOT pinned. They are runtime-only -- NBT and the registry use
 * names, and both ends of a connection are this same jar -- so appending an entity legitimately moves
 * nothing and pinning the values would only forbid appending. What is pinned is the property that
 * makes the space safe. That the ids the space hands out are also the ids actually registered is
 * covered end-to-end by the server-tier resolution scan, which walks the real registry.</p>
 */
public class EntityNetworkIdSpaceTest {

    /**
     * The floor below which a clean result is not evidence: an EMPTY space has no duplicates either,
     * so a distinctness check over nothing passes and says nothing. This jar registers this many
     * entities across its three code bases. A floor, not a pin -- appending keeps it true.
     */
    private static final int MIN_DECLARED = 13;

    @Test
    public void noTwoEntitiesShareANetworkId() {
        Map<String, Integer> space = EntityNetworkIds.declared();
        assertTrue("a space of " + space.size() + " entities cannot be this jar's (expected >= "
                        + MIN_DECLARED + "): a distinctness check over an empty or truncated space"
                        + " passes without examining anything. Declared: " + space,
                space.size() >= MIN_DECLARED);
        Set<Integer> distinct = new HashSet<>(space.values());
        assertEquals("two entities under one mod container cannot share a spawn network id: a client"
                        + " resolves an id to the FIRST registration carrying it and then applies the"
                        + " other entity's synced fields to what it built. Declared space: " + space,
                space.size(), distinct.size());
    }

    @Test
    public void theVendoredCodeBasesAreInTheHostSpace() {
        Map<String, Integer> space = EntityNetworkIds.declared();
        // Being vendored rather than loaded as their own mods is exactly why these must be here:
        // they register into the host's container, so their ids are the host's to allocate.
        for (String vendored : new String[]{
                "affs:laser_bolt",
                "valkyrienskies:entity_mountable",
                "valkyrienskies:entity_mountable_chair"}) {
            assertTrue("a code base vendored into this jar registers into the host's mod container,"
                            + " so its entities must be declared in the host's id space; " + vendored
                            + " is missing from " + space,
                    space.containsKey(vendored));
        }
    }

    @Test
    public void anUndeclaredEntityCannotGetAnId() {
        try {
            EntityNetworkIds.of(new ResourceLocation("advancedrocketry", "notDeclaredAnywhere"));
            fail("an entity that is not declared in the space must not resolve to an id at all --"
                    + " otherwise it silently takes a number that belongs to something else");
        } catch (IllegalStateException expected) {
            // Registration fails at load, loudly, which is the whole point.
        }
    }

    @Test
    public void aDuplicateDeclarationDoesNotLoad() {
        try {
            // Mixed case on purpose: registry names are lower-cased by Forge, so two declarations
            // that differ only in case are the SAME entity name and must be caught as a duplicate.
            EntityNetworkIds.index(new String[]{"amod:one", "amod:two", "AMOD:ONE"});
            fail("a name declared twice in the id space must not build an index: one of the two"
                    + " entities would be unreachable and the other would receive its fields");
        } catch (IllegalStateException expected) {
            assertTrue("the failure must name the offending entity: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("amod"));
        }
    }
}
