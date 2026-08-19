package zmaster587.advancedRocketry.api.damage;

import net.minecraft.world.World;

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
     * {@link ContactResult#noOpinion()} to decline having one, and the default law decides instead —
     * price, stages, ricochet, the lot — exactly as it does for a block that implements nothing.
     *
     * <p><b>Do not decline with {@link ContactResult#passedThrough(int)}.</b> That is an answer, and
     * what it says is "through, carrying this much", so declining with the arriving energy says
     * "through, for free". This javadoc told implementers to do exactly that until 2026-08-19, and
     * mirror plating followed it: a solid round crossed the film spending nothing and left it standing,
     * so the one armour a beam could strip was the one kinetic fire could not.</p>
     *
     * <p>The world is passed rather than carried on the {@link Contact} on purpose. A contact states
     * the FACTS of a meeting — that is what lets a held beam, which is not a shot in any registry, use
     * the same seam — while a block that spends ITSELF needs a handle on the game to do it with. One
     * argument keeps both true, where a world on the contact would have made every future caller
     * produce one and a static would have made the answer depend on who asked last.</p>
     *
     * @param world the world this meeting happened in; server side, never null
     */
    ContactResult onContact(World world, Contact contact);
}
