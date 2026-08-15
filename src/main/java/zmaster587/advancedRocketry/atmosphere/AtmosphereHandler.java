package zmaster587.advancedRocketry.atmosphere;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AreaBlob;
import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.api.event.AtmosphereEvent;
import zmaster587.advancedRocketry.api.util.IBlobHandler;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.network.PacketAtmSync;
import zmaster587.advancedRocketry.util.AtmosphereBlob;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.HashedBlockPosition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class AtmosphereHandler {
    public static final DamageSource vacuumDamage = new DamageSource("Vacuum").setDamageBypassesArmor().setDamageIsAbsolute();
    public static final DamageSource lowOxygenDamage = new DamageSource("LowOxygen").setDamageBypassesArmor().setDamageIsAbsolute();
    public static final DamageSource heatDamage = new DamageSource("Heat").setDamageBypassesArmor().setDamageIsAbsolute();
    public static final DamageSource oxygenToxicityDamage = new DamageSource("OxygenToxicity").setDamageBypassesArmor().setDamageIsAbsolute();
    private static final int MAX_BLOB_RADIUS = ((ARConfiguration.getCurrentConfig().atmosphereHandleBitMask & 1) == 1) ? 256 : ARConfiguration.getCurrentConfig().oxygenVentSize;
    public static long lastSuffocationTime = Integer.MIN_VALUE;
    //Stores current Atm on the CLIENT
    public static IAtmosphere currentAtm;
    public static int currentPressure;
    private static HashMap<Integer, AtmosphereHandler> dimensionOxygen = new HashMap<>();
    private static HashMap<EntityPlayer, IAtmosphere> prevAtmosphere = new HashMap<>();
    private HashMap<IBlobHandler, AreaBlob> blobs;
    private int dimId;

    private AtmosphereHandler(int dimId) {
        this.dimId = dimId;
        blobs = new HashMap<>();
    }

    /**
     * Registers the Atmosphere handler for the dimension given
     *
     * @param dimId the dimension id to register the dimension for
     */
    public static void registerWorld(int dimId) {

        //If O2 is allowed and
        DimensionProperties dimProp = DimensionManager.getInstance().getDimensionProperties(dimId);
        if (ARConfiguration.getCurrentConfig().enableOxygen && dimProp.hasSurface() && (ARConfiguration.getCurrentConfig().overrideGCAir || dimId != ARConfiguration.getCurrentConfig().MoonId || dimProp.isNativeDimension)) {

            //dunno how, but double registering could happen.
            //don't let old registered handler survive in the background forever
            if (dimensionOxygen.containsKey(dimId)) {
                unregisterWorld(dimId);
            }

            AtmosphereHandler handler = new AtmosphereHandler(dimId);
            dimensionOxygen.put(dimId, handler);
            MinecraftForge.EVENT_BUS.register(handler);
        }
    }

    /**
     * Unregisters the Atmosphere handler for the dimension given
     *
     * @param dimId the dimension id to register the dimension for
     */
    public static void unregisterWorld(int dimId) {
        AtmosphereHandler handler = dimensionOxygen.remove(dimId);

        if (handler != null) {
            handler.blobs.clear();

            MinecraftForge.EVENT_BUS.unregister(handler);
        }
    }

    /**
     * Proper Clearing on ServerStopped
     */
    public static void clear() {
        for (AtmosphereHandler handler : new LinkedList<>(dimensionOxygen.values())) {
            if (handler != null) {
                handler.blobs.clear();

                MinecraftForge.EVENT_BUS.unregister(handler);
            }
        }
        dimensionOxygen.clear();
        prevAtmosphere.clear();
        currentAtm = null;
        currentPressure = 0;
        lastSuffocationTime = Integer.MIN_VALUE;
    }

    /**
     * @return true if the dimension has an AtmosphereHandler Object associated with it
     */
    public static boolean hasAtmosphereHandler(int dimId) {
        return dimensionOxygen.containsKey(dimId);
    }

    /** Nesting depth of multi-block structure writes in flight on the server thread. */
    private static int structurePasteDepth = 0;

    /**
     * Open a multi-block structure write: until the matching {@link #endStructurePaste()}, the
     * environmental block CONVERSIONS below (burn, vaporize) are held off.
     *
     * <p>They ask whether a block is exposed to the local atmosphere, and answer it from the world
     * as it stands at that instant. A structure is written one block at a time, so mid-write every
     * block that will end up sealed inside a hull is momentarily standing alone in the open, and
     * the answer is wrong for reasons that have nothing to do with the finished structure. The
     * volume bookkeeping is NOT held off - the blobs must stay correct as the blocks land.</p>
     *
     * <p>Deliberately not re-run at the end: re-judging the whole footprint once the paste closes
     * would re-answer the same question with the same instrument, and a craft parked in a landing
     * lane is not "sealed" by any measure the blob logic can take. A block that genuinely is
     * exposed will be converted by its next block update, which is the same rule everything else
     * in the world lives by.</p>
     *
     * <p>Server thread only, like every other path through this class; nesting is counted so an
     * inner paste cannot re-arm the conversions while an outer one is still running.</p>
     */
    public static void beginStructurePaste() {
        structurePasteDepth++;
    }

    /** Close a write opened by {@link #beginStructurePaste()}. Always call from a finally. */
    public static void endStructurePaste() {
        if (structurePasteDepth > 0) {
            structurePasteDepth--;
        }
    }

    /** Whether a multi-block structure write is in flight. @see #beginStructurePaste() */
    public static boolean isStructurePasteInFlight() {
        return structurePasteDepth > 0;
    }

    //Called from setBlock in World.class
    public static void onBlockChange(@Nonnull World world, @Nonnull BlockPos bpos) {

        // I am very sure all this shit here was NEVER tested!

        if (ARConfiguration.getCurrentConfig().enableOxygen && !world.isRemote && world.getChunkFromBlockCoords(new BlockPos(bpos)).isLoaded()) {
            HashedBlockPosition pos = new HashedBlockPosition(bpos);

            AtmosphereHandler handler = getOxygenHandler(world.provider.getDimension());

            //Bonus chests cause world gen to begin before loading the world
            //Because atmosphere handlers are created at world load time
            //there is a possibility handler can be null here
            if (handler == null)
                return; //WTF

            //Block handling for what should and shouldn't exist or what should be on fire
            //Things should be on fire
            if (!isStructurePasteInFlight() && handler.getAtmosphereType(bpos) == AtmosphereType.SUPERHEATED) {
                if (world.getBlockState(bpos).getBlock().isLeaves(world.getBlockState(bpos), world, bpos)) {
                    world.setBlockToAir(bpos);
                } else if (world.getBlockState(bpos).getMaterial() == Material.CACTUS) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.PLANTS) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.VINE) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getBlock().isLeaves(world.getBlockState(bpos), world, bpos)) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.WOOD) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.WEB) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.CARPET) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.CLOTH) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.GOURD) {
                    world.setBlockState(bpos, Blocks.FIRE.getDefaultState());
                }
            }


            // sure.. causes stackoverflow left right center
            /*
            else if (!handler.getAtmosphereType(bpos).allowsCombustion()) {
                if (world.getBlockState(bpos).getBlock().isLeaves(world.getBlockState(bpos), world, bpos)) {
                    if (!(Boolean)world.getBlockState(bpos).getValue(BlockLeaves.CHECK_DECAY)) {
                        world.setBlockToAir(bpos);
                    }
                } else if (world.getBlockState(bpos).getMaterial() == Material.FIRE) {
                    world.setBlockToAir(bpos);
                } else if (world.getBlockState(bpos).getMaterial() == Material.CACTUS) {
                    world.setBlockToAir(bpos);
                } else if (world.getBlockState(bpos).getMaterial() == Material.PLANTS && world.getBlockState(bpos).getBlock() != Blocks.DEADBUSH) {
                    world.setBlockState(bpos, Blocks.DEADBUSH.getDefaultState());
                } else if (world.getBlockState(bpos).getMaterial() == Material.VINE) {
                    world.setBlockToAir(bpos);
                } else if (world.getBlockState(bpos).getMaterial() == Material.GRASS) {
                    world.setBlockState(bpos, Blocks.DIRT.getDefaultState());
                }
            }
             */

            //Gasses should automatically vaporize and dissipate
            if (!isStructurePasteInFlight() && handler.getAtmosphereType(bpos) == AtmosphereType.VACUUM) {
                if (world.getBlockState(bpos).getMaterial() == Material.WATER && world.getBlockState(bpos).getBlock() instanceof IFluidBlock) {
                    IFluidBlock fluidblock = (IFluidBlock) world.getBlockState(bpos).getBlock();
                    if (fluidblock.getFluid().isGaseous())
                        world.setBlockToAir(bpos);
                }
            }
            //Water blocks should also vaporize and disappear
            /*
            yes but not like this because it crashes the game
            every updated water causes the water next to it to update -> stackoverflow -> server goes boom


            if (handler.getAtmosphereType(bpos) == AtmosphereType.SUPERHEATED || handler.getAtmosphereType(bpos) == AtmosphereType.SUPERHEATEDNOO2 || handler.getAtmosphereType(bpos) == AtmosphereType.VERYHOT || handler.getAtmosphereType(bpos) == AtmosphereType.VERYHOTNOO2) {
                if (world.getBlockState(bpos).getMaterial() == Material.WATER && world.getBlockState(bpos).getValue(BlockLiquid.LEVEL) == 0) {
                    world.setBlockToAir(bpos);
                }
            }
             */


            List<AreaBlob> nearbyBlobs = handler.getBlobWithinRadius(pos, MAX_BLOB_RADIUS);
            for (AreaBlob blob : nearbyBlobs) {

                if (blob.getBlobMaxRadius() > pos.getDistance(blob.getRootPosition())) {
                    if (world.isAirBlock(bpos))
                        handler.onBlockRemove(pos);
                    else {
                        //Place block
                        if (blob.contains(pos) && !blob.isPositionAllowed(world, pos, nearbyBlobs)) {
                            blob.removeBlock(pos);
                        } else if (!blob.contains(blob.getRootPosition())) {
                            blob.addBlock(blob.getRootPosition(), nearbyBlobs);
                        } else if (!blob.contains(pos) && blob.isPositionAllowed(world, pos, nearbyBlobs))//isFulBlock(world, pos.getBlockPos()))
                            blob.addBlock(pos, nearbyBlobs);
                    }
                }
            }
        }
    }

    /**
     * @param dimNumber dimension number for which to get the oxygenhandler
     * @return the oxygen handler for the planet or null if none exists
     */
    @Nullable
    public static AtmosphereHandler getOxygenHandler(int dimNumber) {
        //Get your oxyclean!
        return dimensionOxygen.get(dimNumber);
    }

    @SubscribeEvent
    public void onTick(LivingUpdateEvent event) {
        Entity entity = event.getEntity();
        if (!entity.world.isRemote && entity.world.provider.getDimension() == this.dimId) {
            respire(event.getEntityLiving());
            IAtmosphere atmosType = getAtmosphereType(entity);

            if (entity instanceof EntityPlayer && atmosType != prevAtmosphere.get(entity)) {
                AtmosphereType.sendToRealPlayer(new PacketAtmSync(atmosType.getUnlocalizedName(), getAtmospherePressure(entity)), (EntityPlayer) entity);
                prevAtmosphere.put((EntityPlayer) entity, atmosType);
            }

            // Connectionless player-shaped entities (FakePlayers, headless
            // test players) can't receive the packets the effect paths send
            // (potion sync, oxygen state) — vanilla NPEs in connection.sendPacket
            // and takes the server tick loop down. They still get the cache/
            // sync bookkeeping above; only the effects are skipped.
            if (entity instanceof net.minecraft.entity.player.EntityPlayerMP
                    && ((net.minecraft.entity.player.EntityPlayerMP) entity).connection == null) {
                return;
            }

            if (atmosType.canTick() &&
                    !(event.getEntityLiving().isInLava() || event.getEntityLiving().isInsideOfMaterial(Material.WATER))) {
                AtmosphereEvent event2 = new AtmosphereEvent.AtmosphereTickEvent(entity, atmosType);
                MinecraftForge.EVENT_BUS.post(event2);
                if (!event2.isCanceled() && !atmosType.isImmune(event.getEntity().getClass()))
                    atmosType.onTick(event.getEntityLiving());
            }
        }
    }

    /**
     * The zone containing this entity, or null if it is in none.
     * <p>
     * This is the ONE place the new life-support code turns an entity into a zone. The three
     * public queries below still build the key inline, and all four share the same known defect:
     * the key is world-frame while a blob aboard an assembled ship is subspace-frame (see the
     * atmosphere subsystem doc). Deliberately not worked around here — a fourth, differently-wrong
     * lookup would make the real fix harder, not easier.
     */
    @Nullable
    private AtmosphereBlob getBlobContaining(@Nonnull Entity entity) {
        return getBlobContaining(new HashedBlockPosition((int) Math.floor(entity.posX), (int) Math.ceil(entity.posY), (int) Math.floor(entity.posZ)));
    }

    @Nullable
    private AtmosphereBlob getBlobContaining(@Nonnull HashedBlockPosition pos) {
        for (AreaBlob blob : blobs.values()) {
            if (blob instanceof AtmosphereBlob && blob.contains(pos))
                return (AtmosphereBlob) blob;
        }
        return null;
    }

    /**
     * The gas contents of the zone this position sits in, or null if it is in none. A machine that
     * treats the air of the room it stands in works through this.
     */
    @Nullable
    public AirState getAirStateAt(@Nonnull BlockPos pos) {
        if (!ARConfiguration.getCurrentConfig().enableOxygen)
            return null;
        AtmosphereBlob blob = getBlobContaining(new HashedBlockPosition(pos));
        return blob == null ? null : blob.getAirState();
    }

    /**
     * How many cells the zone containing this position has, or 0 if it is in none. A machine that
     * converts between a room's partial pressures and a tank's millibuckets needs the volume it is
     * dividing by.
     */
    public int getBlobSizeAt(@Nonnull BlockPos pos) {
        AtmosphereBlob blob = getBlobContaining(new HashedBlockPosition(pos));
        return blob == null ? 0 : blob.getBlobSize();
    }

    /**
     * Re-derive and publish the atmosphere of the zone this position sits in, after something
     * changed its gases. A no-op where there is no zone, or where the vent has not declared one
     * breathable — the vent stays the authority on whether a zone is maintained at all.
     */
    /**
     * The same refresh, for a caller that owns the zone rather than a position inside it. A vent
     * knows its blob by identity; making it name a cell would be guessing at its own geometry.
     */
    public void refreshDerivedAtmosphere(@Nonnull IBlobHandler handler) {
        AreaBlob blob = blobs.get(handler);
        if (blob instanceof AtmosphereBlob && isLifeSupportManaged((AtmosphereBlob) blob))
            blob.setData(((AtmosphereBlob) blob).getAirState().deriveAtmosphere());
    }

    public void refreshDerivedAtmosphereAt(@Nonnull BlockPos pos) {
        AtmosphereBlob blob = getBlobContaining(new HashedBlockPosition(pos));
        if (blob != null && isLifeSupportManaged(blob))
            blob.setData(blob.getAirState().deriveAtmosphere());
    }

    /**
     * Whether life support may act on this zone's gases at all — a question about the MACHINE
     * holding the zone, never about the atmosphere the zone currently shows.
     * <p>
     * The distinction is not academic. Asking the published value ("is it breathable, or is it
     * already what these gases derive to?") reads correctly only while the two agree, and the
     * whole purpose of a refresh is the moment they stop: a zone that has just gone stale shows
     * low-oxygen while its gases now derive to something else, so the gate closes exactly when it
     * is needed and the zone latches on the first hazard it ever reaches — never getting worse as
     * the crew keep breathing, and never recoverable by a recirculator or a combiner either.
     * <p>
     * A planet's own atmosphere is excluded by the same rule for the honest reason: nothing is
     * maintaining it.
     */
    private boolean isLifeSupportManaged(@Nonnull AtmosphereBlob blob) {
        return blob.getBlobHandler().isMaintainingAtmosphere();
    }

    /**
     * A breathing entity turns some of its zone's oxygen into carbon dioxide, and the zone's
     * breathability follows.
     * <p>
     * The vent stays the authority on whether a zone is MAINTAINED at all: this only refines a
     * zone the vent has already declared breathable, so an unpowered or unsealed room keeps
     * reverting to the dimension default exactly as before. Consumption is divided by the zone
     * volume, so a bigger cabin buys proportionally more time on the same lungs.
     */
    private void respire(@Nullable net.minecraft.entity.EntityLivingBase entity) {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        if (entity == null || !config.enableOxygen || !config.lifeSupportZones)
            return;
        // Once a second, not once a tick, because the rate is expressed per second. Phased on the
        // ENTITY's own age rather than world time: a shared `% 20` clock would make every living
        // thing in the world respire on the same tick, and world time does not advance under a
        // force-ticking harness at all.
        if (entity.ticksExisted % 20 != 0)
            return;

        AtmosphereBlob blob = getBlobContaining(entity);
        if (blob == null || !isLifeSupportManaged(blob))
            return;

        int volume = Math.max(1, blob.getBlobSize());
        blob.getAirState().respire(config.lifeSupportRespirationRate / volume);
        blob.setData(blob.getAirState().deriveAtmosphere());
    }

    @SubscribeEvent
    public void onPlayerChangeDim(PlayerChangedDimensionEvent event) {
        prevAtmosphere.remove(event.player);
    }

    //Called from World.setBlockMetaDataWithNotify
	/*public static void onBlockMetaChange(World world, int x , int y, int z) {
		if(Configuration.enableOxygen && !world.isRemote && world.getChunkFromBlockCoords(new BlockPos(x, y, z)).isLoaded()) {
			AtmosphereHandler handler = getOxygenHandler(world.provider.getDimension());
			HashedBlockPosition pos = new HashedBlockPosition(x, y, z);


			if(handler == null)
				return; //WTF

			for(AreaBlob blob : handler.getBlobWithinRadius(pos, MAX_BLOB_RADIUS)) {

				if(blob.contains(pos) && !blob.isPositionAllowed(world, pos))
					blob.removeBlock(x, y, z);
				else if(!blob.contains(pos) && blob.isPositionAllowed(world, pos))
					handler.onBlockRemove(pos);
				else if(!blob.contains(pos) && !blob.isPositionAllowed(world, pos) && blob.getBlobSize() == 0) {
					blob.addBlock(blob.getRootPosition());
				}
			}
		}
	}*/

    @SubscribeEvent
    public void onPlayerLogoutEvent(PlayerLoggedOutEvent event) {
        prevAtmosphere.remove(event.player);
    }

    private void onBlockRemove(HashedBlockPosition pos) {
        List<AreaBlob> blobs = getBlobWithinRadius(pos, MAX_BLOB_RADIUS);
        for (AreaBlob blob : blobs) {
            //Make sure that a block can actually be attached to the blob
            for (EnumFacing dir : EnumFacing.VALUES)
                if (blob.contains(pos.getPositionAtOffset(dir))) {
                    blob.addBlock(pos, blobs);
                    break;
                }
        }
    }

    /**
     * Gets a list of AreaBlobs within a radius
     *
     * @param pos    position
     * @param radius distance from the position to find blobs within
     * @return List of AreaBlobs within the radius from the position
     */
    @Nonnull
    protected List<AreaBlob> getBlobWithinRadius(@Nonnull HashedBlockPosition pos, int radius) {
        LinkedList<AreaBlob> list = new LinkedList<>();
        for (AreaBlob blob : blobs.values()) {
            if (blob.getRootPosition().getDistance(pos) - radius <= 0) {
                list.add(blob);
            }
        }
        return list;
    }

    /**
     * Registers a Blob with the atmosphere handler.
     * Must be called before use
     *
     * @param handler IBlobHander to register with
     * @param pos
     */
    public void registerBlob(@Nonnull IBlobHandler handler, BlockPos pos) {
        AreaBlob blob = blobs.get(handler);
        if (blob == null) {
            blob = new AtmosphereBlob(handler);
            blobs.put(handler, blob);
            blob.setData(AtmosphereType.PRESSURIZEDAIR);
        }
    }

    /**
     * Registers a Blob with provided blob type
     * Must be called before use
     *
     * @param handler IBlobHander to register with
     * @param pos
     * @param blob2
     */
    public void registerBlob(@Nonnull IBlobHandler handler, BlockPos pos, @Nonnull AreaBlob blob2) {
        AreaBlob blob = blobs.get(handler);
        if (blob == null) {
            blob = blob2;
            blobs.put(handler, blob);
            blob.setData(AtmosphereType.PRESSURIZEDAIR);
        }
    }

    /**
     * Unregisters a blob from the atmosphere handler
     *
     * @param handler IBlobHandlerObject the blob is associated with
     */
    public void unregisterBlob(@Nonnull IBlobHandler handler) {
        blobs.remove(handler);
    }

    /**
     * Removes all blocks from the blob associated with this handler
     *
     * @param handler the handler associated with this blob
     */
    public void clearBlob(@Nonnull IBlobHandler handler) {

        if (blobs.containsKey(handler)) {
            blobs.get(handler).clearBlob();
        }
    }

    /**
     * Adds a block to the blob
     *
     * @param handler
     * @param x
     * @param y
     * @param z
     */
    public void addBlock(@Nonnull IBlobHandler handler, int x, int y, int z) {
        addBlock(handler, new HashedBlockPosition(x, y, z));
    }

    /**
     * Adds a block to the blob
     *
     * @param handler
     * @return true if blob addition is successful
     */
    public boolean addBlock(@Nonnull IBlobHandler handler, @Nonnull HashedBlockPosition pos) {
        AreaBlob blob = blobs.get(handler);
        blob.addBlock(pos, getBlobWithinRadius(pos, MAX_BLOB_RADIUS));
        return !blob.getLocations().isEmpty();
    }

    /**
     * @param pos2
     * @return AtmosphereType at this location
     */
    @Nonnull
    public IAtmosphere getAtmosphereType(@Nonnull BlockPos pos2) {
        if (ARConfiguration.getCurrentConfig().enableOxygen) {
            HashedBlockPosition pos = new HashedBlockPosition(pos2);

            for (AreaBlob blob : blobs.values()) {
                if (blob.contains(pos)) {
                    IAtmosphere atmosphere = (IAtmosphere) blob.getData();

                    if (atmosphere != null)
                        return atmosphere;
                }
            }

            return getDefaultAtmosphereType();
        }

        return AtmosphereType.AIR;
    }

    /**
     * @return the default atmosphere type used by this planet
     */
    @Nonnull
    public IAtmosphere getDefaultAtmosphereType() {
        return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere();
    }

    /**
     * Gets the atmosphere type at the location of this entity
     *
     * @param entity the entity to check against
     * @return The atmosphere type this entity is inside of
     */
    @Nullable
    public IAtmosphere getAtmosphereType(@Nonnull Entity entity) {
        if (ARConfiguration.getCurrentConfig().enableOxygen) {
            HashedBlockPosition pos = new HashedBlockPosition((int) Math.floor(entity.posX), (int) Math.ceil(entity.posY), (int) Math.floor(entity.posZ));
            for (AreaBlob blob : blobs.values()) {
                if (blob.contains(pos)) {
                    return (IAtmosphere) blob.getData();
                }
            }

            return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere();
        }
        return AtmosphereType.AIR;
    }

    /**
     * Gets the pressure at the location of this entity
     *
     * @param entity the entity to check against
     * @return The atmosphere pressure this entity is inside of, or -1 to use default
     */
    public int getAtmospherePressure(@Nonnull Entity entity) {
        if (ARConfiguration.getCurrentConfig().enableOxygen) {
            HashedBlockPosition pos = new HashedBlockPosition((int) Math.floor(entity.posX), (int) Math.ceil(entity.posY), (int) Math.floor(entity.posZ));
            for (AreaBlob blob : blobs.values()) {
                if (blob.contains(pos) && blob instanceof AtmosphereBlob) {
                    return ((AtmosphereBlob) blob).getPressure();
                }
            }
        }
        return -1;
    }

    /**
     * @param entity entity to check against
     * @return true if the entity can breathe in the this atmosphere
     */
    public boolean canEntityBreathe(@Nonnull EntityLiving entity) {
        if (ARConfiguration.getCurrentConfig().enableOxygen) {
            HashedBlockPosition pos = new HashedBlockPosition((int) Math.floor(entity.posX), (int) Math.ceil(entity.posY), (int) Math.floor(entity.posZ));
            for (AreaBlob blob : blobs.values()) {
                IAtmosphere atmosphere = (IAtmosphere) blob.getData();
                if (blob.contains(pos) && atmosphere != null && atmosphere.isImmune(entity)) {
                    return true;
                }
            }
            return DimensionManager.getInstance().getDimensionProperties(dimId).getAtmosphere().isImmune(entity);
        }

        return true;
    }

    /**
     * @param handler the handler registered to this blob
     * @return The current size of the blob
     */
    public int getBlobSize(@Nonnull IBlobHandler handler) {
        return blobs.get(handler).getBlobSize();
    }

    /**
     * Changes the atmosphere type of this blob
     *
     * @param handler the handler for the blob
     * @param data    the AtmosphereType to set this blob to.
     */
    public void setAtmosphereType(@Nonnull IBlobHandler handler, @Nonnull IAtmosphere data) {
        blobs.get(handler).setData(data);
    }

    /**
     * The gas contents of this handler's zone, or null if it has no zone (or none that carries
     * gases). Callers that persist a zone across a save go through here rather than holding the
     * blob, which the handler owns and rebuilds.
     */
    @Nullable
    public AirState getAirState(@Nonnull IBlobHandler handler) {
        AreaBlob blob = blobs.get(handler);
        return blob instanceof AtmosphereBlob ? ((AtmosphereBlob) blob).getAirState() : null;
    }

    /**
     * Restore gas contents into this handler's zone. No-op when the zone does not exist yet, so a
     * caller loading from NBT before its blob is registered must retry rather than assume.
     *
     * @return true if the zone existed and took the state
     */
    public boolean setAirState(@Nonnull IBlobHandler handler, @Nonnull AirState airState) {
        AreaBlob blob = blobs.get(handler);
        if (!(blob instanceof AtmosphereBlob))
            return false;
        ((AtmosphereBlob) blob).setAirState(airState);
        return true;
    }

    /**
     * Gets the atmosphere type of this blob
     *
     * @param handler the handler for the blob
     */
    @Nonnull
    public IAtmosphere getAtmosphereType(@Nonnull IBlobHandler handler) {
        if (ARConfiguration.getCurrentConfig().enableOxygen) {
            IAtmosphere atmosphere = (IAtmosphere) blobs.get(handler).getData();
            if (atmosphere != null)
                return atmosphere;
            else
                return getDefaultAtmosphereType();
        }

        return AtmosphereType.AIR;
    }
}
