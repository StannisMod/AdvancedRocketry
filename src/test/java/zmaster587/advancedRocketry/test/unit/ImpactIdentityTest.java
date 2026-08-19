package zmaster587.advancedRocketry.test.unit;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import zmaster587.advancedRocketry.projectile.ShotRegistry;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * An impact identity is handed out once, and the damage service's refusal of duplicates is only as
 * good as that.
 *
 * <p>This is a one-line property with an expensive failure. The duplicate memory answers a repeated
 * identity by handing the whole budget back, so a round whose identity somebody else already spent
 * bores nothing, marks nothing and flies on — through a stone wall, at full speed, with its budget
 * untouched. Nothing above it can tell that apart from a clean miss, which is why the property is
 * pinned here rather than left to be noticed downrange.</p>
 *
 * <p>What is deliberately NOT pinned: the numbers themselves, or that they are consecutive. The
 * contract is uniqueness and never rewinding; any two identities that differ satisfy it.</p>
 */
public class ImpactIdentityTest {

    /**
     * Far past the point the old identity broke down at. It was a shot id shifted eight bits with a
     * per-shot counter mixed into the low byte, so the 257th impact of one round minted the first
     * identity of the round after it — and one round boring for a few hundred ticks is an ordinary
     * afternoon, not an edge case.
     */
    private static final int MANY = 5000;

    @Test
    public void everyIdentityIsHandedOutOnce() {
        ShotRegistry registry = new ShotRegistry();
        Set<Long> seen = new HashSet<Long>();
        for (int i = 0; i < MANY; i++) {
            long id = registry.nextImpactId();
            assertTrue("identity " + id + " was handed out twice within " + MANY + " impacts, so the"
                    + " damage service will refuse a real impact as a repeat of an unrelated one and"
                    + " hand its whole budget back", seen.add(id));
        }
        assertEquals("the run minted fewer distinct identities than impacts", MANY, seen.size());
    }

    /**
     * Clearing the shots in flight must not rewind the identities. The duplicate memory outlives any
     * one round — that is what it is FOR — so a registry that started counting again after a clear
     * would be re-minting identities the service is still holding.
     */
    @Test
    public void clearingTheShotsDoesNotRewindTheIdentities() {
        ShotRegistry registry = new ShotRegistry();
        Set<Long> before = new HashSet<Long>();
        for (int i = 0; i < 64; i++) {
            before.add(registry.nextImpactId());
        }
        registry.clear();
        for (int i = 0; i < 64; i++) {
            long id = registry.nextImpactId();
            assertTrue("identity " + id + " came back after the registry was cleared: a scenario that"
                    + " drops its shots would then re-use identities the damage service still refuses",
                    !before.contains(id));
        }
    }

    /**
     * And a restart must not rewind them either. The memory is in RAM and the counter is on disk, so
     * a counter that reset on load would start handing out identities that a still-running server —
     * the one that just saved — is holding.
     */
    @Test
    public void aSavedRegistryResumesWhereItLeftOff() {
        ShotRegistry registry = new ShotRegistry();
        Set<Long> before = new HashSet<Long>();
        for (int i = 0; i < 64; i++) {
            before.add(registry.nextImpactId());
        }

        ShotRegistry reloaded = new ShotRegistry();
        reloaded.readFromNBT(registry.writeToNBT(new NBTTagCompound()));

        for (int i = 0; i < 64; i++) {
            long id = reloaded.nextImpactId();
            assertTrue("identity " + id + " was minted again after a save and load", !before.contains(id));
        }
    }
}
