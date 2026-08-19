package zmaster587.advancedRocketry.block;

import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.damage.Contact;
import zmaster587.advancedRocketry.api.damage.ContactResult;

/**
 * Reactive plating — a charge that spends ITSELF to eat part of a hit, and takes nothing else with it.
 *
 * <h3>Local, and directed away</h3>
 * <p>It is made of much what an explosive is, arranged differently, and it detonates only where it
 * stands: outward, into the thing that struck it, never into the hull it is bolted to or the plate
 * beside it. That is the whole reason a charge is a sane thing to clad a ship in — the alternative is
 * armour that finishes the enemy's work.</p>
 *
 * <h3>It eats a PORTION, and the portion is its volume</h3>
 * <p>How much of an impact it swallows is declared per block: heavy plating eats more than light, and
 * two layers eat more than one because the second is asked after the first is gone — layers being
 * neighbouring VOXELS along the body's path, which is the only kind of layering a voxel world has. So
 * the ordering the mechanic exists to produce:</p>
 * <ul>
 *   <li>machine-gun fire, micrometeorites, splinters — swallowed whole, one charge each;</li>
 *   <li>a railgun round — punches through a single layer as if it were not there, because what it
 *       carries dwarfs what one charge can take. Against that the answer is a shield, and reactive
 *       plating is honest about not being one.</li>
 * </ul>
 *
 * <h3>It is spent, not damaged</h3>
 * <p>A charge that has gone off is gone. The block removes itself and the next body through that spot
 * meets whatever was behind it — which is what makes "the second shot is not stopped" a property of
 * the thing rather than a number somebody has to keep.</p>
 */
public class BlockReactivePlating extends BlockPlating {

    private final int capacity;

    /**
     * @param capacity how much impact energy this much plating swallows before it is spent — the ONE
     *                 thing separating a plate from a block, because what a body meets is the voxel
     *                 and never the shape inside it
     */
    public BlockReactivePlating(int capacity) {
        super(Material.IRON);
        this.capacity = Math.max(1, capacity);
        setHardness(2.0F);
    }

    /** How much of an impact this much plating can swallow. */
    public int getCapacity() {
        return capacity;
    }

    @Override
    public ContactResult onContact(World world, Contact contact) {
        if (contact == null) {
            return null;
        }
        int eaten = Math.min(contact.getEnergy(), capacity);
        // It goes off whatever happens next: a charge that met something does not un-meet it, and the
        // difference between stopping a round and merely blunting one is not the charge's to make.
        detonate(world, contact.getPos());

        int residual = contact.getEnergy() - eaten;
        return residual > 0 ? ContactResult.passedThrough(residual) : ContactResult.stopped();
    }

    /**
     * The charge spends itself and nothing else. No block break, no explosion in the world, no
     * neighbour touched — the blast is outward, into what struck it, and the world has no way to
     * represent that other than by this plate ceasing to exist.
     */
    private void detonate(World world, BlockPos pos) {
        if (world != null && !world.isRemote && pos != null) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        }
    }

}
