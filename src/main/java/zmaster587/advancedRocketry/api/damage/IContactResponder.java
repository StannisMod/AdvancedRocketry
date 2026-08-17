package zmaster587.advancedRocketry.api.damage;

/**
 * A block that has something to say about a body meeting it — armour, in one word.
 *
 * <p>Implement on the {@code Block} when the answer is a property of the material (a plate is a plate
 * wherever it is placed), or on its {@code TileEntity} when the answer depends on state the block is
 * carrying (a reactive charge that has already been spent). The block is asked first.</p>
 *
 * <p>An implementation decides and answers; it does not spend budgets, advance stages or destroy
 * anything through this call — except itself, which is its own business. What it must NOT do is
 * assume it is the only block being asked: a body wide enough covers several at once, and each is
 * asked with {@link Contact#getShare()} of the energy.</p>
 *
 * <p>A block that implements nothing gets the default law, which is ordinary penetration — so this
 * interface is what armour opts INTO, never something every block has to answer.</p>
 */
public interface IContactResponder {

    /**
     * Answer for one body meeting this block. Never null: return
     * {@link ContactResult#passedThrough(int)} to decline having an opinion.
     */
    ContactResult onContact(Contact contact);
}
