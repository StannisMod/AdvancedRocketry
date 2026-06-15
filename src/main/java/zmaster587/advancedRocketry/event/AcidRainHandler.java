package zmaster587.advancedRocketry.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.capability.CapabilitySpaceArmor;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;

/**
 * Applies damage to players caught in acidic rain — planets flagged
 * {@code acidicRain=true} in their definition — while standing under open sky
 * without a full protective space suit.
 *
 * <p>Acid rain is independent of breathability: a breathable acidic planet still
 * burns an unprotected player. Protection is the same {@code PROTECTIVEARMOR}
 * capability the atmosphere system uses, required on all four armor slots so a
 * mask-only loadout does not shield bare skin.</p>
 */
public class AcidRainHandler {

    public static final DamageSource ACID_RAIN =
            new DamageSource("acidRain").setDamageBypassesArmor();

    @SubscribeEvent
    public void playerTick(LivingUpdateEvent event) {
        if (!(event.getEntity() instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.getEntity();
        World world = player.world;
        if (world.isRemote) return;

        int interval = ARConfiguration.getCurrentConfig().acidRainDamageInterval;
        if (interval < 1) interval = 1;
        if (world.getTotalWorldTime() % interval != 0) return;

        float damage = ARConfiguration.getCurrentConfig().acidRainDamage;
        if (damage <= 0f) return;

        if (isExposedToAcidRain(player)) {
            player.attackEntityFrom(ACID_RAIN, damage);
        }
    }

    /**
     * True when {@code player} is currently being harmed by acid rain: on an AR
     * planet whose rain is acidic, standing where rain actually falls (open sky,
     * rain-capable biome), and not wearing a full protective suit.
     */
    public static boolean isExposedToAcidRain(EntityPlayer player) {
        World world = player.world;
        if (!(world.provider instanceof WorldProviderPlanet)) return false;

        DimensionProperties props = DimensionManager.getInstance()
                .getDimensionProperties(world.provider.getDimension());
        if (props == null || !props.isAcidicRain()) return false;

        BlockPos pos = player.getPosition();
        if (!world.isRainingAt(pos)) return false;

        return !isProtected(player);
    }

    /** A full protective space suit (all four slots) shields from acid rain. */
    public static boolean isProtected(EntityPlayer player) {
        if (player.capabilities.isCreativeMode || player.isSpectator()) return true;
        return hasProtectiveArmor(player, EntityEquipmentSlot.HEAD)
                && hasProtectiveArmor(player, EntityEquipmentSlot.CHEST)
                && hasProtectiveArmor(player, EntityEquipmentSlot.LEGS)
                && hasProtectiveArmor(player, EntityEquipmentSlot.FEET);
    }

    private static boolean hasProtectiveArmor(EntityPlayer player, EntityEquipmentSlot slot) {
        ItemStack stack = player.getItemStackFromSlot(slot);
        return !stack.isEmpty()
                && CapabilitySpaceArmor.PROTECTIVEARMOR != null
                && stack.hasCapability(CapabilitySpaceArmor.PROTECTIVEARMOR, null);
    }
}
