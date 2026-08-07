package org.valkyrienskies.mod.common;

import com.google.common.collect.ImmutableList;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayer.SleepResult;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.eventhandler.Event.Result;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.mod.common.entity.EntityMountable;
import org.valkyrienskies.mod.common.network.ShipTransformUpdateMessage;
import org.valkyrienskies.mod.common.ships.entity_interaction.EntityDraggable;
import org.valkyrienskies.mod.common.ships.entity_interaction.IDraggable;
import org.valkyrienskies.mod.common.ships.ship_transform.CoordinateSpaceType;
import org.valkyrienskies.mod.common.ships.ship_transform.ShipTransform;
import org.valkyrienskies.mod.common.ships.ship_world.*;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.TransformType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = ValkyrienSkiesMod.HOST_MOD_ID)
public class EventsCommon {

    @Deprecated
    private static final Map<EntityPlayer, double[]> lastPositions = new HashMap<>();
    private static final Logger logger = LogManager.getLogger(EventsCommon.class);

    @SubscribeEvent
    public static void onPlayerSleepInBedEvent(PlayerSleepInBedEvent event) {
        EntityPlayer player = event.getEntityPlayer();
        BlockPos pos = event.getPos();
        Optional<PhysicsObject> physicsObject = ValkyrienUtils
            .getPhysoManagingBlock(player.getEntityWorld(), pos);

        if (physicsObject.isPresent()) {
            if (player instanceof EntityPlayerMP) {
                player.sendMessage(new TextComponentString("Spawn Point Set!"));
                player.setSpawnPoint(pos, false);
                event.setResult(SleepResult.NOT_POSSIBLE_HERE);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoinWorldEvent(EntityJoinWorldEvent event) {
        Entity entity = event.getEntity();

        World world = entity.world;
        BlockPos posAt = new BlockPos(entity);

        Optional<PhysicsObject> physicsObject = ValkyrienUtils.getPhysoManagingBlock(world, posAt);
        if (!event.getWorld().isRemote && physicsObject.isPresent()
            && !(entity instanceof EntityFallingBlock)) {
            if (entity instanceof EntityArmorStand
                || entity instanceof EntityPig || entity instanceof EntityBoat) {
                EntityMountable entityMountable = new EntityMountable(world,
                    entity.getPositionVector(), CoordinateSpaceType.SUBSPACE_COORDINATES, posAt);
                world.spawnEntity(entityMountable);
                entity.startRiding(entityMountable);
            }
            physicsObject.get()
                .getShipTransformationManager()
                .getCurrentTickTransform().transform(entity,
                TransformType.SUBSPACE_TO_GLOBAL, false);
            // TODO: This should work but it doesn't because of sponge. Instead we have to rely on MixinChunk.preAddEntity() to fix this
            // event.setCanceled(true);
            // event.getWorld().spawnEntity(entity);
        }
    }

    /**
     * When each dimension last had a pose packet sent, by whichever side sent it. Read by the
     * physics thread's watchdog to decide whether the game tick has gone quiet.
     */
    private static final Map<Integer, Long> LAST_POSE_SEND_NANOS = new ConcurrentHashMap<>();

    /**
     * How long a dimension may go without a pose packet before the physics thread sends one itself.
     * Longer than a healthy tick (50 ms) so it never races the normal path, short enough that a
     * stalled tick does not freeze every client's view of ships whose physics is still advancing.
     */
    private static final long POSE_WATCHDOG_NANOS = 60_000_000L;

    /**
     * True when nothing has put this dimension's poses on the wire for longer than the watchdog —
     * i.e. the game tick has stopped running while physics has not. Called from the PHYSICS thread.
     */
    public static boolean poseSendIsOverdue(int dimensionId) {
        final Long last = LAST_POSE_SEND_NANOS.get(dimensionId);
        return last == null || System.nanoTime() - last > POSE_WATCHDOG_NANOS;
    }

    /**
     * Put every loaded ship's pose on the wire, once per GAME tick.
     *
     * <p>This used to live in {@code VSWorldPhysicsLoop}, sent from the physics thread on a wall
     * clock ({@code > 0.04 s}). That paced the producer off a 60 Hz loop while the consumer — a
     * client that applies poses on its own 20 Hz tick and interpolates between them — ran at 50 ms.
     * The gate opened every third physics iteration, about 48.7 ms, and drifted: sooner or later one
     * client tick received no update and the next received two. The client's pose filter has an
     * alpha of 0.5, so a single missed update becomes a step of half the true one followed by eight
     * ticks of visible catch-up. That is what a pilot calls flying in jerks.</p>
     *
     * <p>Here the send is driven by the same clock the consumer keeps, so the cadence is exactly one
     * update per tick by construction rather than by coincidence. Physics still runs at 60 Hz — the
     * rate is the rigid-body integrator's requirement (contact resolution and angular integration
     * both rest on step size), and it was never what put packets on the wire.</p>
     *
     * <p><b>The game tick is the primary sender, not the only one.</b> Making it the only one was
     * tried and withdrawn: a server whose tick stalls would then freeze every client's view of ships
     * whose PHYSICS is still advancing, because physics runs on its own thread and does not stall
     * with the tick. The physics loop therefore keeps a watchdog ({@link #poseSendIsOverdue}) and
     * sends for itself when nothing has gone out for longer than a healthy tick. Delivery is thus a
     * superset of the old behaviour — the watchdog can only ADD a packet the tick failed to send,
     * never remove one.</p>
     *
     * <p>Reading the physics transform from this thread is safe because it is an immutable
     * {@code ShipTransform} published through a volatile field; the AABB is derived from the ship's
     * block set, which is mutated on THIS thread, so computing it here is safer than where it was
     * computed before.</p>
     */
    public static void sendShipTransformUpdates(World world, IPhysObjectWorld physObjectWorld) {
        LAST_POSE_SEND_NANOS.put(world.provider.getDimension(), System.nanoTime());
        try {
            final ShipTransformUpdateMessage message = new ShipTransformUpdateMessage();
            message.setDimensionID(world.provider.getDimension());
            boolean any = false;
            for (final PhysicsObject physicsObject : physObjectWorld.getAllLoadedThreadSafe()) {
                final ShipTransform shipTransform =
                        physicsObject.getShipTransformationManager().getCurrentPhysicsTransform();
                final AxisAlignedBB shipBB = physicsObject.getPhysicsTransformAABB();
                if (shipTransform == null || shipBB == null) {
                    continue; // a ship whose pose or extent is not ready yet says nothing this tick
                }
                message.addData(physicsObject.getUuid(), shipTransform, shipBB);
                any = true;
            }
            if (any) {
                ValkyrienSkiesMod.physWrapperTransformUpdateNetwork
                        .sendToDimension(message, message.getDimensionID());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldTickEvent(WorldTickEvent event) {
        // This only gets called server side, because forge wants it that way. But in case they
        // change their mind, this exception will crash the game to notify us of the change.
        if (event.side == Side.CLIENT) {
            throw new IllegalStateException("This event should never get called client side");
        }
        World world = event.world;
        IPhysObjectWorld physObjectWorld = ValkyrienUtils.getPhysObjWorld(world);
        switch (event.phase) {
            case START:
                // Reset the air pocket status of all entities
                for (final Entity entity : world.loadedEntityList) {
                    final IDraggable draggable = (IDraggable) entity;
                    draggable.decrementTicksAirPocket();
                }
                break;
            case END:
                physObjectWorld.tick();
                sendShipTransformUpdates(world, physObjectWorld);
                EntityDraggable.tickAddedVelocityForWorld(world);
                break;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTickEvent(PlayerTickEvent event) {
        if (!event.player.world.isRemote) {
            EntityPlayerMP p = (EntityPlayerMP) event.player;

            double[] pos = lastPositions.computeIfAbsent(p, k -> new double[3]);
            try {
                if (pos[0] != p.posX || pos[2] != p.posZ) { // Player has moved
                    if (Math.abs(p.posX) > 27000000
                        || Math.abs(p.posZ) > 27000000) { // Player is outside of world
                        // border, tp them back
                        p.attemptTeleport(pos[0], pos[1], pos[2]);
                        p.sendMessage(new TextComponentString(
                            "You can't go beyond 27000000 blocks because airships are stored there!"));
                    }
                }
            } catch (NullPointerException e) {
                logger.warn("Nullpointer EventsCommon.java:onPlayerTickEvent");
            }

            pos[0] = p.posX;
            pos[1] = p.posY;
            pos[2] = p.posZ;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        event.getWorld().addEventListener(new VSWorldEventListener(world));
        IHasShipManager shipManager = (IHasShipManager) world;
        if (!event.getWorld().isRemote) {
            shipManager.setManager(WorldServerShipManager::new);
        } else {
            shipManager.setManager(WorldClientShipManager::new);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldUnload(WorldEvent.Unload event) {
        // Fixes memory leak; @DaPorkChop please don't leave static maps lying around D:
        lastPositions.clear();
        IHasShipManager shipManager = (IHasShipManager) event.getWorld();
        shipManager.getManager().onWorldUnload();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerInteractEvent(PlayerInteractEvent event) {
        BlockPos pos = event.getPos();

        Optional<PhysicsObject> physicsObject = ValkyrienUtils
            .getPhysoManagingBlock(event.getWorld(), pos);
        if (physicsObject.isPresent()) {
            event.setResult(Result.ALLOW);
        }
    }

    private static final List<String> MEMED = ImmutableList.of("Drake_Eldridge", "thebest108", "DaPorkChop_");

    @SubscribeEvent
    public static void onJoin(PlayerLoggedInEvent event) {
        // The upstream "install Valkyrien Skies Control / World" login nag is gone, along with the
        // module detection behind it: this physics core is vendored as part of this mod, not shipped
        // as a standalone platform for those add-ons, so recommending them was advice to install
        // something that is not part of this game.
        if (!event.player.world.isRemote) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            lastPositions.put(player, new double[]{0D, 256D, 0D});

            if (MEMED.contains(player.getName())) {
                WorldServer server = (WorldServer) event.player.world;

                // 20% chance of getting memed on!
                if (Math.random() < .2) {
                    server.mcServer.getPlayerList()
                        .sendMessage(new TextComponentString(
                            TextFormatting.BLUE + "An absolute " + TextFormatting.RED
                                + TextFormatting.ITALIC + "legend" + TextFormatting.BLUE
                                + " has arrived! Welcome " + TextFormatting.GOLD
                                + TextFormatting.BOLD + player.getName()));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerLoggedOutEvent event) {
        if (!event.player.world.isRemote) {
            lastPositions.remove(event.player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreakFirst(BlockEvent event) {
        ValkyrienUtils.getPhysoManagingBlock(event.getWorld(), event.getPos())
            .ifPresent(physicsObject -> event.setResult(Result.ALLOW));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionStart(ExplosionEvent.Start event) {
        // Only run on server side
        if (!event.getWorld().isRemote) {
            Explosion explosion = event.getExplosion();
            Vector3dc center = new Vector3d(explosion.x, explosion.y, explosion.z);
            Optional<PhysicsObject> optionalPhysicsObject = ValkyrienUtils.getPhysoManagingBlock(event.getWorld(),
                    new BlockPos(event.getExplosion().getPosition()));
            if (optionalPhysicsObject.isPresent()) {
                return;
            }
            // Explosion radius
            float radius = explosion.size;
            AxisAlignedBB toCheck = new AxisAlignedBB(center.x() - radius, center.y() - radius,
                center.z() - radius,
                center.x() + radius, center.y() + radius, center.z() + radius);
            // Find nearby ships, we will check if the explosion effects them
            List<PhysicsObject> shipsNear = ((IHasShipManager) event.getWorld()).getManager()
                    .getPhysObjectsInAABB(toCheck);
            // Process the explosion on the nearby ships
            for (PhysicsObject ship : shipsNear) {
                Vector3d inLocal = new Vector3d(center);
                inLocal.mulPosition(ship.getShipTransform().getGlobalToSubspace());

                Explosion expl = new Explosion(event.getWorld(), explosion.exploder, inLocal.x, inLocal.y,
                    inLocal.z, radius, explosion.causesFire, explosion.damagesTerrain);

                double waterRange = .6D;

                for (int x = (int) Math.floor(expl.x - waterRange);
                    x <= Math.ceil(expl.x + waterRange); x++) {
                    for (int y = (int) Math.floor(expl.y - waterRange);
                        y <= Math.ceil(expl.y + waterRange); y++) {
                        for (int z = (int) Math.floor(expl.z - waterRange);
                            z <= Math.ceil(expl.z + waterRange); z++) {
                            IBlockState state = event.getWorld()
                                .getBlockState(new BlockPos(x, y, z));
                            if (state.getBlock() instanceof BlockLiquid) {
                                return;
                            }
                        }
                    }
                }

                expl.doExplosionA();
                event.getExplosion().affectedBlockPositions.addAll(expl.affectedBlockPositions);
            }
        }
    }

}
