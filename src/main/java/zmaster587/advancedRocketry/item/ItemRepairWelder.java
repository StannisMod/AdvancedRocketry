package zmaster587.advancedRocketry.item;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.damage.DamageState;
import zmaster587.advancedRocketry.damage.RepairCost;

import java.util.List;

/**
 * The hand tool at the bottom of the repair ladder: right-click a damaged block to take one stage of
 * damage off it, paid for in the block's own materials and in charge.
 *
 * <h3>What it is for, given that breaking the block also repairs it</h3>
 * <p>Replacing a damaged block by hand is a working repair and costs one block. The welder exists
 * because that is not the same operation: a block that is broken and replaced comes back EMPTY. Its
 * tile entity — an inventory, a linked seat, a machine's own accumulated wear — does not survive the
 * round trip. Welding leaves the block, and everything it is holding, exactly where it is. On plain
 * hull plate the pickaxe is often the cheaper option, and that is fine.</p>
 *
 * <h3>Charge</h3>
 * <p>Holds Forge Energy and exposes the standard capability, so it charges in whatever charger the
 * pack provides rather than in a block Advanced Rocketry has to ship. Energy is the reason a welder
 * is a machine and not a hammer; running out is a state the player can see on the durability bar
 * before the tool refuses.</p>
 *
 * <h3>Refusals are spoken</h3>
 * <p>Undamaged, unpriceable, out of materials and out of charge are four different answers, and each
 * says which it is. A tool that does nothing quietly is a tool players stop trusting.</p>
 */
public class ItemRepairWelder extends Item {

    private static final String NBT_ENERGY = "energy";

    public ItemRepairWelder() {
        setMaxStackSize(1);
    }

    /**
     * Every way one use of the welder can end. A type rather than four message strings, because the
     * four are genuinely different answers and callers — the player, a test, a future automated
     * rung — all need to tell them apart, not just read different words.
     */
    public enum Outcome {
        REPAIRED("msg.welder.repaired"),
        UNDAMAGED("msg.welder.undamaged"),
        NO_RECIPE("msg.welder.nocost"),
        NO_MATERIALS("msg.welder.nomaterials"),
        NO_CHARGE("msg.welder.nocharge");

        public final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    /**
     * One stage of repair at {@code pos}, paid for out of {@code player}'s inventory and {@code
     * tool}'s charge. Server-side, silent, and the whole decision — the item below only turns the
     * answer into words.
     *
     * <p>Nothing is taken unless everything can be: the two charges are checked before either is
     * made, so a use that ends in a refusal costs the player nothing at all.</p>
     */
    public static Outcome weld(EntityPlayer player, World world, BlockPos pos, ItemStack tool) {
        int stage = DamageState.getStage(world, pos);
        if (stage <= 0) {
            return Outcome.UNDAMAGED;
        }
        boolean free = player.capabilities.isCreativeMode;
        if (free) {
            // Creative repairs anything, including a block that nothing crafts. The price of a
            // repair is a fraction of the block's own recipe, so a block with no recipe has no
            // price — and the shield and armour families have no recipes yet, which would otherwise
            // make a shot-up shield generator permanently damaged with no path back even in
            // creative. Charging nothing for nothing is the one reading of that which is not a
            // refusal.
            DamageState.setStage(world, pos, stage - 1);
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
            return Outcome.REPAIRED;
        }

        List<ItemStack> cost = RepairCost.perStage(world, pos);
        if (cost == null) {
            return Outcome.NO_RECIPE;
        }
        int energyCost = ARConfiguration.getCurrentConfig().repairWelderEnergyPerStage;
        if (storedEnergy(tool) < energyCost) {
            return Outcome.NO_CHARGE;
        }
        if (!RepairCost.consume(player, cost, true)) {
            return Outcome.NO_MATERIALS;
        }

        RepairCost.consume(player, cost, false);
        setStoredEnergy(tool, storedEnergy(tool) - energyCost);
        DamageState.setStage(world, pos, stage - 1);
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        return Outcome.REPAIRED;
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            // The client is told what happened by the block's own sync; deciding here would let it
            // predict a repair the server may refuse.
            return EnumActionResult.PASS;
        }
        Outcome outcome = weld(player, world, pos, player.getHeldItem(hand));
        player.sendStatusMessage(new TextComponentTranslation(outcome.messageKey), true);
        if (outcome != Outcome.REPAIRED) {
            return EnumActionResult.FAIL;
        }
        world.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_ANVIL_USE,
                SoundCategory.BLOCKS, 0.4F, 1.6F);
        player.swingArm(hand);
        return EnumActionResult.SUCCESS;
    }

    // --- charge, on the stack --------------------------------------------------------------------

    public static int storedEnergy(ItemStack stack) {
        NBTTagCompound nbt = stack.getTagCompound();
        return nbt == null ? 0 : nbt.getInteger(NBT_ENERGY);
    }

    public static void setStoredEnergy(ItemStack stack, int energy) {
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound().setInteger(NBT_ENERGY,
                Math.max(0, Math.min(energy, ARConfiguration.getCurrentConfig().repairWelderCapacity)));
    }

    @Override
    public boolean showDurabilityBar(ItemStack stack) {
        return storedEnergy(stack) < ARConfiguration.getCurrentConfig().repairWelderCapacity;
    }

    @Override
    public double getDurabilityForDisplay(ItemStack stack) {
        int capacity = Math.max(1, ARConfiguration.getCurrentConfig().repairWelderCapacity);
        return 1.0D - (storedEnergy(stack) / (double) capacity);
    }

    @Override
    @Nullable
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable NBTTagCompound unused) {
        return new EnergyProvider(stack);
    }

    /** Forge Energy on the stack itself, so any charger in the pack can fill it. */
    private static final class EnergyProvider implements ICapabilityProvider, IEnergyStorage {

        private final ItemStack stack;

        private EnergyProvider(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityEnergy.ENERGY;
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked")
        public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
            return capability == CapabilityEnergy.ENERGY ? (T) this : null;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int room = getMaxEnergyStored() - getEnergyStored();
            int accepted = Math.min(room, Math.max(0, maxReceive));
            if (!simulate && accepted > 0) {
                setStoredEnergy(stack, getEnergyStored() + accepted);
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0; // a welder spends its charge on repairs, not into the grid
        }

        @Override
        public int getEnergyStored() {
            return storedEnergy(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return ARConfiguration.getCurrentConfig().repairWelderCapacity;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
