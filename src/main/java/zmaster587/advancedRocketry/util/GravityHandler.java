package zmaster587.advancedRocketry.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityThrowable;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.AdvancedRocketryAPI;
import zmaster587.advancedRocketry.api.IGravityManager;
import zmaster587.advancedRocketry.api.IPlanetaryProvider;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.integration.vs.ShipFrameTravel;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

public class GravityHandler implements IGravityManager {

    public static final float LIVING_OFFSET = 0.0755f;
    public static final float FLUID_LIVING_OFFSET = 0.02f;
    public static final float THROWABLE_OFFSET = 0.03f;
    public static final float OTHER_OFFSET = 0.04f;
    public static final float ARROW_OFFSET = 0.05f;

    static Class gcWorldProvider;
    static Method gcGetGravity;
    private static WeakHashMap<Entity, Double> entityMap = new WeakHashMap<>();

    static {
        AdvancedRocketryAPI.gravityManager = new GravityHandler();


        try {
            gcWorldProvider = Class.forName("micdoodle8.mods.galacticraft.api.world.IGalacticraftWorldProvider");
            AdvancedRocketry.logger.info("GC IGalacticraftWorldProvider  found");
            gcGetGravity = gcWorldProvider.getMethod("getGravity");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            gcWorldProvider = null;
            AdvancedRocketry.logger.info("GC IGalacticraftWorldProvider not found");
        }
    }

    public static void applyGravity(Entity entity) {
        if (entity.hasNoGravity()) return;
        //Because working gravity on elytra-flying players can cause..... severe problems at lower gravity, it is my utter delight to announce to you elytra are now magic!
        //This totally isn't because Mojang decided for some godforsaken @#@#@#% reason to make ALL WAYS TO SET ELYTRA FLIGHT _protected_
        //With no set methods
        //So I cannot, without much more effort than it's worth, set elytra flight. Therefore, they're magic.
        if ((!(entity instanceof EntityPlayer) && !(entity instanceof EntityFlying)) || (!(entity instanceof EntityFlying) && !(((EntityPlayer) entity).capabilities.isFlying || ((EntityLivingBase) entity).isElytraFlying()))) {

            // A living entity aboard a ship has its whole movement - gravity included - resolved in
            // the ship's frame by ShipFrameTravel. Applying a world-frame delta here as well would
            // pull it toward the deck twice, so we hand it over untouched. The two call sites must
            // agree on WHICH entities: both ask ShipFrameTravel.handles.
            if (entity instanceof EntityLivingBase
                    && ShipFrameTravel.handles((EntityLivingBase) entity)) {
                return;
            }

            // Ship-floor gravity for everything else (items, minecarts, TNT, arrows): an entity
            // aboard a Valkyrien Skies ship is pulled toward the ship's deck (its local down,
            // rotated by the ship attitude) rather than straight world-down, so the floor is "down"
            // at any ship orientation. On an upright ship the direction is (0,-1,0) and the delta
            // below is exactly zero (no change from vanilla). A fixed ~1G deck gravity (the per-type
            // offset) makes ships walkable even in 0G space. These types are safe to steer from the
            // world frame: their per-type offset cancels vanilla's own gravity EXACTLY (unlike the
            // living one), and their drag is isotropic, so no along-deck force survives. Takes
            // precedence over dimension gravity (the ship supplies its own). Null (no ship / no VS)
            // falls through to the existing per-dimension handling unchanged.
            double[] shipDown = VSIntegration.shipDownDirectionFor(
                    entity.world, entity.posX, entity.posY, entity.posZ);
            if (shipDown != null) {
                double[] dv = zmaster587.advancedRocketry.api.FreeFlightPhysics
                        .shipGravityDelta(shipGravityOffset(entity), shipDown);
                entity.motionX += dv[0];
                entity.motionY += dv[1];
                entity.motionZ += dv[2];
                return;
            }

            Double d;
            if (entityMap.containsKey(entity) && (d = entityMap.get(entity)) != null) {

                double multiplier = (isOtherEntity(entity) || entity instanceof EntityItem) ? OTHER_OFFSET * d : (entity instanceof EntityArrow) ? ARROW_OFFSET * d : (entity instanceof EntityThrowable) ? THROWABLE_OFFSET * d : LIVING_OFFSET * d;

                entity.motionY += multiplier;

            } else if (DimensionManager.getInstance().isDimensionCreated(entity.world.provider.getDimension()) || entity.world.provider instanceof WorldProviderSpace) {
                double gravMult;

                if (entity.world.provider instanceof IPlanetaryProvider)
                    gravMult = ((IPlanetaryProvider) entity.world.provider).getGravitationalMultiplier(entity.getPosition());
                else
                    gravMult = DimensionManager.getInstance().getDimensionProperties(entity.world.provider.getDimension()).gravitationalMultiplier;

                if (entity instanceof EntityItem)
                    entity.motionY -= (gravMult * OTHER_OFFSET - OTHER_OFFSET);
                else if (isOtherEntity(entity))
                    entity.motionY -= (gravMult * OTHER_OFFSET - OTHER_OFFSET);
                else if (entity instanceof EntityThrowable)
                    entity.motionY -= (gravMult * THROWABLE_OFFSET - THROWABLE_OFFSET);
                else if (entity instanceof EntityArrow)
                    entity.motionY -= (gravMult * ARROW_OFFSET - ARROW_OFFSET);
                else if (entity instanceof EntityLivingBase && entity.isInWater() || entity.isInLava())
                    entity.motionY -= (gravMult * FLUID_LIVING_OFFSET - FLUID_LIVING_OFFSET);
                else if (entity instanceof EntityLivingBase)
                    entity.motionY -= (gravMult * LIVING_OFFSET - LIVING_OFFSET);

            } else {
                //GC handling
                if (gcWorldProvider != null && gcWorldProvider.isAssignableFrom(entity.world.provider.getClass())) {
                    try {
                        entity.motionY -= LIVING_OFFSET - (float) gcGetGravity.invoke(entity.world.provider);
                    } catch (IllegalAccessException | IllegalArgumentException
                             | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public static boolean isOtherEntity(Entity entity) {
        return entity instanceof EntityBoat || entity instanceof EntityMinecart || entity instanceof EntityFallingBlock || entity instanceof EntityTNTPrimed;
    }

    /**
     * The per-tick gravity magnitude (blocks/tick) used for a ship's ~1G deck gravity, chosen by
     * entity type. Mirrors the per-type offset selection of the scalar world-Y path, minus the
     * dimension multiplier - a ship supplies a constant deck gravity independent of the dimension.
     */
    private static float shipGravityOffset(Entity entity) {
        if (entity instanceof EntityItem || isOtherEntity(entity)) {
            return OTHER_OFFSET;
        }
        if (entity instanceof EntityThrowable) {
            return THROWABLE_OFFSET;
        }
        if (entity instanceof EntityArrow) {
            return ARROW_OFFSET;
        }
        if (entity instanceof EntityLivingBase && (entity.isInWater() || entity.isInLava())) {
            return FLUID_LIVING_OFFSET;
        }
        return LIVING_OFFSET;
    }

    @Override
    public void setGravityMultiplier(Entity entity, double multiplier) {
        //TODO: packet handling
        entityMap.put(entity, multiplier);
    }

    @Override
    public void clearGravityEffect(Entity entity) {
        entityMap.remove(entity);
    }
}
