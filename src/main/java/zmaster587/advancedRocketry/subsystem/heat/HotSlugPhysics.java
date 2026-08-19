package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.tile.heat.TileHeatDump;

/**
 * A charged slug after it leaves the ship: a hot object on the same physics as everything else.
 *
 * <p>Nothing here is a special case for an ejected item. A slug is a lump of matter carrying energy,
 * so it does what a lump of matter carrying energy does - it cools by the same quartic law a radiator
 * sheds on, it melts what it lands on by SPENDING that energy (which is why it self-limits rather
 * than burning through a planet), and it burns whoever picks it up while it is still holding
 * anything. Once it has spent what it took it is an ordinary block of iron again, and the only thing
 * the player lost is the trip it made.</p>
 *
 * <p><b>The temperature is derived, never stored.</b> What is written on the stack is ENERGY; how hot
 * that makes the lump depends on how much lump there is, which is the same arithmetic the dump used
 * to charge it. Storing a temperature instead would let a nugget and a block of the same metal carry
 * the same heat.</p>
 */
public class HotSlugPhysics {

    /** How often a loose slug is looked at. Cheap, but not free: this runs over dropped items. */
    private static final int TICK_INTERVAL = 20;

    /**
     * How hot a stack is, in kelvin, given what it is carrying and how much of it there is. Ambient
     * for anything that carries nothing.
     */
    public static double temperatureOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return HeatNetwork.ambientKelvin();
        }
        long charge = TileHeatDump.chargeOf(stack);
        if (charge <= 0L) {
            return HeatNetwork.ambientKelvin();
        }
        ThermalMaterial material = ThermalMaterials.INSTANCE.of(stack);
        long full = ThermalMaterials.slugCapacity(material,
                ThermalMaterials.volumeMillilitres(stack));
        if (full <= 0L || material == null) {
            return HeatNetwork.ambientKelvin();
        }
        // The charge spans ambient to the material's usable ceiling, so where it sits in that span is
        // where the temperature sits. The same span the dump filled, read the other way.
        double usable = material.ceilingKelvin()
                - Math.max(0, ARConfiguration.getCurrentConfig().shipHeatSlugMarginKelvin)
                - HeatNetwork.ambientKelvin();
        return HeatNetwork.ambientKelvin() + usable * Math.min(1.0D, (double) charge / full);
    }

    /** Take spent energy off a stack, and answer what it is now carrying. */
    private static long spend(ItemStack stack, long amount) {
        long charge = TileHeatDump.chargeOf(stack);
        long spent = Math.max(0L, Math.min(amount, charge));
        if (spent > 0L && stack.getTagCompound() != null) {
            stack.getTagCompound().setLong(TileHeatDump.NBT_CHARGE, charge - spent);
        }
        return charge - spent;
    }

    /**
     * A loose slug cools, and burns what it is lying on while it can pay for it.
     *
     * <p>Both halves come out of the same purse, which is what makes the mess self-limiting: melting
     * a block costs that block's own heat capacity, so a slug carrying little melts nothing and a slug
     * carrying a lot leaves a short trail and stops. Radiating costs it too - in vacuum that is slow,
     * which is exactly why an ejected slug stays a bright object for a long time.</p>
     */
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        World world = event.world;
        if (world.getTotalWorldTime() % TICK_INTERVAL != 0 || !HeatNetwork.enabled()) {
            return;
        }
        for (Object obj : world.loadedEntityList.toArray()) {
            if (!(obj instanceof EntityItem)) {
                continue;
            }
            EntityItem item = (EntityItem) obj;
            ItemStack stack = item.getItem();
            if (TileHeatDump.chargeOf(stack) <= 0L) {
                continue;
            }
            tickOneSlug(world, item, stack);
        }
    }

    private void tickOneSlug(World world, EntityItem item, ItemStack stack) {
        double kelvin = temperatureOf(stack);
        BlockPos under = new BlockPos(item.posX, item.posY - 0.1D, item.posZ);

        // What it lands on is cooked by the slug's own temperature, and the block's own capacity is
        // the bill. A slug that cannot pay simply does not melt it.
        long cost = ThermalMaterials.blockCapacity(world, under);
        if (cost > 0L && TileHeatDump.chargeOf(stack) >= cost
                && HullMelting.meltIfPast(world, under, kelvin)) {
            spend(stack, cost);
        }

        // And it radiates, on the same curve as everything else in this subsystem.
        long shed = (long) Math.max(1.0D, HeatNetwork.cellPowerAt(kelvin) * TICK_INTERVAL);
        spend(stack, shed);
    }

    /**
     * Picking up something still holding heat hurts, through the atmosphere subsystem's own heat
     * damage source rather than a second one invented here.
     */
    @SubscribeEvent
    public void onPickup(EntityItemPickupEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        EntityItem item = event.getItem();
        if (player == null || item == null || player.world.isRemote) {
            return;
        }
        ItemStack stack = item.getItem();
        if (TileHeatDump.chargeOf(stack) <= 0L) {
            return;
        }
        double kelvin = temperatureOf(stack);
        if (kelvin < TileHeatDump.triggerKelvin()) {
            return; // cool enough to handle: the slug is reusable once it has spent itself
        }
        player.attackEntityFrom(AtmosphereHandler.heatDamage, 4);
        player.setFire(3);
    }
}
