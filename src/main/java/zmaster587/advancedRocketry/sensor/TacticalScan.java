package zmaster587.advancedRocketry.sensor;

import com.github.stannismod.affs.util.CodeUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import zmaster587.advancedRocketry.api.sensor.SensorMode;
import zmaster587.advancedRocketry.api.sensor.TargetTrack;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One sweep: everything a sensor can currently hold, in the order it would shoot at them.
 *
 * <h3>Classification happens HERE, not at the trigger</h3>
 * <p>A friend is not something a gun declines to fire at — a friend never becomes a contact in the
 * first place. That is a stronger statement than it looks: a battery cannot be talked into shelling
 * its own crew by a race, a stale target or a console left pointing at the wrong thing, because the
 * name of the friendly was never written down anywhere a gun could read it. The two exclusions are
 * the credential a target carries, and simply being aboard the ship the sensor is bolted to.</p>
 *
 * <h3>World frame, always</h3>
 * <p>Entities live in the world's coordinates and a ship's blocks live in the ship's, so a sensor
 * aboard a hull converts its OWN position out to the world before looking around, and the tracks it
 * produces are in world coordinates. A gun consuming them converts back, once, using the same seam
 * it already uses for every other target.</p>
 */
public final class TacticalScan {

    private TacticalScan() {
    }

    /**
     * Look around and answer what is out there, best contact first.
     *
     * @param world          the server world the sensor sits in
     * @param origin         the sensor's own position, in WORLD coordinates
     * @param ownShipId      the ship the sensor is bolted to, or null for a ground installation
     * @param radiusBlocks   how far the device can look at all
     * @param mode           listening, or illuminating
     * @param activePlateau  the quality an illuminated contact is held at inside the envelope
     * @param friendlyCode   the installation's access code; anything carrying it is not a contact
     * @param hostilesOnly   whether harmless livestock and villagers may be acquired
     * @param maxTracks      how many contacts the device can hold at once
     */
    public static List<TargetTrack> sweep(World world, Vec3d origin, String ownShipId,
                                          double radiusBlocks, SensorMode mode, double activePlateau,
                                          String friendlyCode, boolean hostilesOnly, int maxTracks) {
        if (world == null || world.isRemote || origin == null || radiusBlocks <= 0.0D || maxTracks <= 0) {
            return Collections.emptyList();
        }

        AxisAlignedBB envelope = new AxisAlignedBB(
                origin.x - radiusBlocks, origin.y - radiusBlocks, origin.z - radiusBlocks,
                origin.x + radiusBlocks, origin.y + radiusBlocks, origin.z + radiusBlocks);

        List<TargetTrack> tracks = new ArrayList<>();
        for (EntityLivingBase candidate : world.getEntitiesWithinAABB(EntityLivingBase.class, envelope)) {
            TargetTrack track = trackOf(candidate, origin, ownShipId, radiusBlocks, mode, activePlateau,
                    friendlyCode, hostilesOnly, world);
            if (track != null) {
                tracks.add(track);
            }
        }

        // Best held first, nearest breaking a tie: a battery that has to choose should choose the
        // one it can actually hit, and among equals the one that will arrive first.
        tracks.sort(Comparator.<TargetTrack>comparingDouble(t -> -t.getQuality())
                .thenComparingDouble(TargetTrack::getDistance));
        return tracks.size() <= maxTracks ? tracks : new ArrayList<>(tracks.subList(0, maxTracks));
    }

    /** One candidate, or null if it is not a contact at all. */
    private static TargetTrack trackOf(EntityLivingBase candidate, Vec3d origin, String ownShipId,
                                       double radiusBlocks, SensorMode mode, double activePlateau,
                                       String friendlyCode, boolean hostilesOnly, World world) {
        if (candidate == null || candidate.isDead) {
            return null;
        }
        if (hostilesOnly && !(candidate instanceof IMob) && !(candidate instanceof EntityPlayer)) {
            // A defensive battery that opens up on passing livestock is a battery a player switches
            // off, and a switched-off battery defends nothing.
            return null;
        }
        if (CodeUtils.entityHasMatchingCode(candidate, friendlyCode)) {
            return null;
        }
        if (isAboard(world, ownShipId, candidate)) {
            // Standing on our own deck. The crew of a ship carry no credential by default and are
            // not going to acquire one mid-boarding-action; being aboard IS the credential.
            return null;
        }

        Vec3d position = bodyCentre(candidate);
        double distance = position.distanceTo(origin);
        if (distance > radiusBlocks) {
            // The bounding box is a cube and the envelope is a sphere.
            return null;
        }

        double temperature = SignatureModel.estimatedTemperatureKelvin(candidate);
        double area = SignatureModel.estimatedAreaSquareMetres(candidate);
        double radiance = SignatureModel.radiance(temperature);

        double quality;
        if (mode == SensorMode.ACTIVE) {
            quality = SignatureModel.activeQuality(distance, radiusBlocks, activePlateau);
        } else {
            if (distance > SignatureModel.detectionRangeBlocks(temperature, area)) {
                // Not heard at all: a listening sensor's reach is the target's own output, not the
                // device's rating. Silence never makes a thing invisible, but it does move this line.
                return null;
            }
            quality = SignatureModel.passiveQuality(temperature, distance);
        }
        if (quality <= 0.0D) {
            return null;
        }

        return new TargetTrack(candidate.getUniqueID(), position, velocityOf(candidate), quality, mode,
                radiance, distance);
    }

    /** The middle of the body: a round at foot height passes under everything on uneven ground. */
    private static Vec3d bodyCentre(Entity entity) {
        return entity.getPositionVector().addVector(0.0D, entity.height * 0.5D, 0.0D);
    }

    /**
     * How fast it is actually going, in blocks per tick, taken from where it WAS rather than from
     * its motion fields — a player's motion is decided on their own client and a mob's is spent
     * before it is read, while the distance covered since the last tick is true for both.
     */
    private static Vec3d velocityOf(Entity entity) {
        return new Vec3d(entity.posX - entity.lastTickPosX, entity.posY - entity.lastTickPosY,
                entity.posZ - entity.lastTickPosZ);
    }

    /** Whether this entity is standing on the ship the sensor is part of. */
    private static boolean isAboard(World world, String ownShipId, Entity entity) {
        if (ownShipId == null) {
            return false;
        }
        return VSIntegration.shipIdsAt(world, entity.posX, entity.posY, entity.posZ).contains(ownShipId);
    }
}
