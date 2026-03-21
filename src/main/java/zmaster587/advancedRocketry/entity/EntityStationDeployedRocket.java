package zmaster587.advancedRocketry.entity;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.RocketEvent;
import zmaster587.advancedRocketry.api.RocketEvent.RocketLaunchEvent;
import zmaster587.advancedRocketry.api.RocketEvent.RocketPreLaunchEvent;
import zmaster587.advancedRocketry.api.StatsRocket;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.client.SoundRocketEngine;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.mission.MissionGasCollection;
import zmaster587.advancedRocketry.network.PacketSatellite;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.util.AudioRegistry;
import zmaster587.advancedRocketry.util.StorageChunk;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleButton;
import zmaster587.libVulpes.inventory.modules.ModuleText;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.HashedBlockPosition;
import zmaster587.libVulpes.util.Vector3F;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import static zmaster587.advancedRocketry.api.StatsRocket.INVALID_SEAT;

public class EntityStationDeployedRocket extends EntityRocket {

    public EnumFacing launchDirection;
    public EnumFacing forwardDirection;
    public HashedBlockPosition launchLocation;
    public Vec3d actualLaunchLocation;
    boolean coastMode;
    private ModuleText atmText;
    private short gasId;
    private Ticket ticket;
    private long plannedHarvestMb = 0L;  // planned total mB to attempt this mission
    private transient boolean postedLandedAfterLoad = false;
    private transient boolean postedDeorbit = false;
    
    public EntityStationDeployedRocket(World world) {
        super(world);
        launchDirection = EnumFacing.DOWN;
        launchLocation = new HashedBlockPosition(0, 0, 0);
        actualLaunchLocation = new Vec3d(0, 0, 0);
        atmText = new ModuleText(182, 114, "", 0x2d2d2d);
        gasId = 0;
        ticket = null;
    }

    public EntityStationDeployedRocket(World world, StorageChunk storage, StatsRocket stats, double x, double y, double z) {
        super(world, storage, stats, x, y, z);
        actualLaunchLocation = new Vec3d(x, y, z);
        launchLocation = new HashedBlockPosition((int) x, (int) y, (int) z);
        launchDirection = EnumFacing.DOWN;
        stats.setSeatLocation(INVALID_SEAT, -1, -1); //No seats
        atmText = new ModuleText(182, 114, "", 0x2d2d2d);
        gasId = 0;
    }

    //Use as a way of checking when chunk is unloaded
    @Override
    public void setDead() {
        super.setDead();
        if (ticket != null)
            ForgeChunkManager.releaseTicket(ticket);
    }

    /**
     * Called immediately before launch
     */
    @Override
    public void prepareLaunch() {

        RocketPreLaunchEvent event = new RocketEvent.RocketPreLaunchEvent(this);
        MinecraftForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            if (world.isRemote)
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.LAUNCH.ordinal()));
            launch();
        }
    }

    @Override
    public void launch() {

        if (world.isRemote) return;

        if (isInFlight()) {
            //System.out.println("error in flight");
            return;
        }

        if (isInOrbit()) {
            setInFlight(true);
            return;
        }

        if (storage != null) {
            storage.recalculateStats(this.stats);  // keeps everything else in sync
        }
 

        FuelRegistry.FuelType rt = getRocketFuelType();
        if (rt != null && ARConfiguration.getCurrentConfig().rocketRequireFuel) {
            if (getFuelAmount(rt) < getFuelCapacity(rt)) return;

            if (rt == FuelRegistry.FuelType.LIQUID_BIPROPELLANT) {
                if (getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER)
                    < getFuelCapacity(FuelRegistry.FuelType.LIQUID_OXIDIZER)) return;
            }
        }

        ISpaceObject spaceObj;
        if (world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId &&
                (spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(getPosition())) != null &&
                spaceObj.getProperties().getParentProperties().isGasGiant()) { //Abort if destination is invalid


            setInFlight(true);
            launchLocation.x = (int) Math.floor(this.posX);
            launchLocation.y = (short) this.posY;
            launchLocation.z = (int) Math.floor(this.posZ);
            Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();

            MinecraftForge.EVENT_BUS.post(new RocketLaunchEvent(this));

            //Disconnect things linked to the rocket on liftoff
            while (connectedTiles.hasNext()) {
                IInfrastructure i = connectedTiles.next();
                if (i.disconnectOnLiftOff()) {
                    disconnectInfrastructure(i);
                    connectedTiles.remove();
                }
            }
        }
    }

    @Override
    public void onUpdate() {
        lastWorldTickTicked = world.getTotalWorldTime();
        if (!world.isRemote && !postedLandedAfterLoad && this.ticksExisted >= 5) {
            // Consider "landed" = entity exists, NOT in flight, NOT in orbit
            if (!isInFlight() && !isInOrbit()) {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new zmaster587.advancedRocketry.api.RocketEvent.RocketLandedEvent(this)
                );
                postedLandedAfterLoad = true;
            }
        }
        if (this.ticksExisted == 20) {
            //problems with loading on other world then where the infrastructure was set?
            for (HashedBlockPosition temp : new LinkedList<>(infrastructureCoords)) {
                TileEntity tile = this.world.getTileEntity(new BlockPos(temp.x, temp.y, temp.z));
                if (tile instanceof IInfrastructure) {
                    this.linkInfrastructure((IInfrastructure) tile);
                }
            }

            if (world.isRemote)
                LibVulpes.proxy.playSound(new SoundRocketEngine(AudioRegistry.combustionRocket, SoundCategory.NEUTRAL, this));
        }

        if (isInFlight()) {

            boolean burningFuel = isBurningFuel();

            if (launchLocation == null || storage == null ||actualLaunchLocation == null)
                return;

            //Grab a ticket when we take off
            if (!world.isRemote && ticket == null) {
                ticket = ForgeChunkManager.requestTicket(AdvancedRocketry.instance, world, Type.ENTITY);
                if (ticket != null) {
                    ticket.bindEntity(this);
                    for (int i = 0; i < 9; i++)
                        ForgeChunkManager.forceChunk(ticket, new ChunkPos(forwardDirection.getFrontOffsetX() * i + (launchLocation.x >> 4), forwardDirection.getFrontOffsetZ() * i + (launchLocation.z >> 4)));
                }
            }

            boolean isCoasting = Math.abs(this.posX - actualLaunchLocation.x) < 4 * storage.getSizeX() && Math.abs(this.posY - actualLaunchLocation.y) < 4 * storage.getSizeY() && Math.abs(this.posZ - actualLaunchLocation.z) < 4 * storage.getSizeZ();

            if (!isCoasting) {
                //Burn the rocket fuel

                //Spawn in the particle effects for the engines
                if (world.isRemote && Minecraft.getMinecraft().gameSettings.particleSetting < 2) {
                    for (Vector3F<Float> vec : stats.getEngineLocations()) {

                        float xMult = forwardDirection.getFrontOffsetX();
                        float zMult = forwardDirection.getFrontOffsetZ();
                        float xVel, zVel;

                        for (int i = 0; i < 4; i++) {
                            xVel = (1 - Math.abs(xMult)) * ((this.rand.nextFloat() - 0.5f) / 8f) + xMult * -.15f;
                            zVel = (1 - Math.abs(zMult)) * ((this.rand.nextFloat() - 0.5f) / 8f) + zMult * -.15f;


                            //TODO offset particles by 0.5 if rocket is not centered on one block
                            //double ox = (storage.getSizeX() % 2 == 0 ? 0.5 : 0);
                            //double oz = (storage.getSizeZ() % 2 == 0 ? 0.5 : 0);
                            double ox = 0;
                            double oz = 0;
                            double oy = 0.5;
                            //System.out.println(vec.x+":"+vec.z);

                            if (isInOrbit())
                                AdvancedRocketry.proxy.spawnParticle("rocketFlame", world, this.posX + vec.x - xMult+ox, this.posY + vec.y+oy, this.posZ + vec.z- zMult+oz, xVel+xMult*0.5, (this.rand.nextFloat() - 0.5f) / 8f, zVel+zMult*0.5);
                            else
                                AdvancedRocketry.proxy.spawnParticle("rocketFlame", world, this.posX + vec.x - xMult+ox, this.posY + vec.y+oy, this.posZ + vec.z - zMult+oz, xVel, (this.rand.nextFloat() - 0.5f) / 8f, zVel);


                        }
                    }
                }
            }


            if (forwardDirection == null)
                return;

            //Returning
            if (isInOrbit()) { //For unmanned rockets
                // Post deorbit once, as we start the return phase
                if (!world.isRemote && !postedDeorbit) {
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new zmaster587.advancedRocketry.api.RocketEvent.RocketDeOrbitingEvent(this)
                    );
                    postedDeorbit = true;
                }                
                EnumFacing dir;
                isCoasting = Math.abs(this.posX - actualLaunchLocation.x) < 0.01 && Math.abs(this.posZ - actualLaunchLocation.z) < 0.01;

                if (isCoasting) {
                    dir = launchDirection.getOpposite();
                    float speed = 0.02f;
                    motionX = speed * dir.getFrontOffsetX();
                    motionY = speed * dir.getFrontOffsetY();
                    motionZ = speed * dir.getFrontOffsetZ();
                } else {
                    dir = forwardDirection.getOpposite();

                    float acc = 0.005f;

                    motionX = acc * (actualLaunchLocation.x - this.posX) + 0.005 * dir.getFrontOffsetX();
                    motionY = 0;//acc*(launchLocation.y - this.posY) + 0.01*dir.offsetY;
                    motionZ = acc * (actualLaunchLocation.z - this.posZ) + 0.005 * dir.getFrontOffsetZ();

                    //setFuelAmount(getRocketFuelType(), getFuelAmount(getRocketFuelType()) - 1);
                }

                // what if the rocket touches the top structure towers? it would stay in flight forever

                if (this.posY + 0.1 >= actualLaunchLocation.y) {
                    if (!world.isRemote) {
                        this.setInFlight(false);
                        this.setInOrbit(false);
                        MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLandedEvent(this));

                        //Release ticket on landing
                        if (ticket != null) {
                            ForgeChunkManager.releaseTicket(ticket);
                            ticket = null;
                        }

                        //PacketHandler.sendToNearby(new PacketEntity(this, (byte)PacketType.ROCKETLANDEVENT.ordinal()), world.provider.dimensionId, (int)posX, (int)posY, (int)posZ, 64);
                        //PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, (byte)PacketType.ROCKETLANDEVENT.ordinal()), this);
                    }

                    this.motionY = 0;
                    this.setPosition(actualLaunchLocation.x, actualLaunchLocation.y, actualLaunchLocation.z);
                }
            } else {
                //Move out 4x the size of the rocket
                //Coast away from the station
                if (isCoasting) {
                    float speed = 0.02F;//(float)Math.min(0.2f, Math.abs(motionY) + 0.0001f);
                    motionX = speed * launchDirection.getFrontOffsetX() * (2.1 * storage.getSizeX() - Math.abs(2 * storage.getSizeX() - Math.abs(this.posX - actualLaunchLocation.x)) + 0.05);
                    motionY = speed * launchDirection.getFrontOffsetY() * (2.1 * storage.getSizeY() - Math.abs(2 * storage.getSizeY() - Math.abs(this.posY - actualLaunchLocation.y)) + 0.05);
                    motionZ = speed * launchDirection.getFrontOffsetZ() * (2.1 * storage.getSizeZ() - Math.abs(2 * storage.getSizeZ() - Math.abs(this.posZ - actualLaunchLocation.z)) + 0.05);
                } else {
                    float acc = 0.005f;
                    motionX += acc * forwardDirection.getFrontOffsetX();
                    motionY += acc * forwardDirection.getFrontOffsetY();
                    motionZ += acc * forwardDirection.getFrontOffsetZ();

                    // server-side fuel consumption for thrust ticks
                    if (!world.isRemote && burningFuel) {
                        // only consume if we actually need to (respect config + biprop pairing)
                        tryConsumeAscentFuel();
                    }
                }
                if (!world.isRemote && this.getDistance(actualLaunchLocation.x, actualLaunchLocation.y, actualLaunchLocation.z) > 128) {


                    //Release ticket on landing
                    if (ticket != null) {
                        ForgeChunkManager.releaseTicket(ticket);
                        ticket = null;
                    }
                    onOrbitReached();
                    return;
                }
            }


            this.move(MoverType.SELF, motionX, motionY, motionZ);
        }
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules;
        //If the rocket is flight don't load the interface
        modules = super.getModules(ID, player);

        Iterator<ModuleBase> itr = modules.iterator();
        while (itr.hasNext()) {
            ModuleBase module = itr.next();
            if (module instanceof ModuleButton && ((ModuleButton) module).buttonId == 1) {
                itr.remove();
                break;
            }
        }


        DimensionProperties props = DimensionManager.getEffectiveDimId(world, this.getPosition());
        if (props.isGasGiant()) {
            try {
                atmText.setText(props.getHarvestableGasses().get(gasId).getLocalizedName(new FluidStack(props.getHarvestableGasses().get(gasId), 1)));
            } catch (IndexOutOfBoundsException e) {
                gasId = 0;
                atmText.setText(props.getHarvestableGasses().get(gasId).getLocalizedName(new FluidStack(props.getHarvestableGasses().get(gasId), 1)));
            }
        } else {
            atmText.setText(LibVulpes.proxy.getLocalizedString("msg.entityDeployedRocket.notGasGiant"));
        }
        modules.add(new ModuleButton(170, 114, 1, "", this, zmaster587.libVulpes.inventory.TextureResources.buttonLeft, 5, 8));
        modules.add(atmText);
        modules.add(new ModuleButton(240, 114, 2, "", this, zmaster587.libVulpes.inventory.TextureResources.buttonRight, 5, 8));

        return modules;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onInventoryButtonPressed(int buttonId) {
        DimensionProperties props;
        switch (buttonId) {
            case 0:
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.DECONSTRUCT.ordinal()));
                break;
            case 1:
                props = DimensionManager.getEffectiveDimId(world, this.getPosition());
                if (props.isGasGiant()) {
                    gasId++;
                    if (gasId < 0)
                        gasId = (short) (props.getHarvestableGasses().size() - 1);
                    else if (gasId > props.getHarvestableGasses().size() - 1)
                        gasId = 0;
                    PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.MENU_CHANGE.ordinal()));
                }
                break;
            case 2:
                props = DimensionManager.getEffectiveDimId(world, this.getPosition());
                if (props.isGasGiant()) {
                    gasId--;
                    if (gasId < 0)
                        gasId = (short) (props.getHarvestableGasses().size() - 1);
                    else if (gasId > props.getHarvestableGasses().size() - 1)
                        gasId = 0;
                    PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.MENU_CHANGE.ordinal()));
                }
                break;
            default:
                super.onInventoryButtonPressed(buttonId);
        }
        //openGui(Minecraft.getMinecraft().player);
    }


    /**
     * Called when the rocket reaches orbit
     */
    @Override
    public void onOrbitReached() {
        if (world.isRemote) return;  // client should not run any of this
        // Emit the “reached orbit” event directly so monitors update.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
            new zmaster587.advancedRocketry.api.RocketEvent.RocketReachesOrbitEvent(this)
        );
        if (this.isDead) return;


        //Check again to make sure we are around a gas giant
        ISpaceObject spaceObj;
        setInOrbit(true);
        if (world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId && ((spaceObj = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.getPosition())) != null && spaceObj.getProperties().getParentProperties().isGasGiant())) { //Abort if destination is invalid
            this.setPosition(forwardDirection.getFrontOffsetX() * 64d + this.actualLaunchLocation.x, posY, forwardDirection.getFrontOffsetZ() * 64d + this.actualLaunchLocation.z);
        } else {
            setInOrbit(true);
            return;
        }


        DimensionProperties properties = (DimensionProperties) spaceObj.getProperties().getParentProperties();

        //Make sure gas id is valid, or abort
        if (gasId >= properties.getHarvestableGasses().size() || gasId < 0) {
            setInOrbit(true);
            return;
        }

        // --- Plan harvest & cap duration by what we can actually get ---
        final net.minecraftforge.fluids.Fluid targetFluid =
                properties.getHarvestableGasses().get(gasId);

        // (1) config harvest cap (mB)
        final boolean infinite = ARConfiguration.getCurrentConfig().gasHarvestInfinite;
        final double mult = Math.max(0.0, ARConfiguration.getCurrentConfig().gasHarvestAmountMultiplier);
        final long base64k = 64_000L;
        final int harvestCapMb = infinite
                ? Integer.MAX_VALUE
                : (int) Math.min(Integer.MAX_VALUE, Math.round(base64k * mult));

        // (2) free capacity for this gas across all rocket tanks (simulate)
        int freeMb = 0;
        for (TileEntity tile : this.storage.getFluidTiles()) {
            net.minecraftforge.fluids.capability.IFluidHandler h =
                tile.getCapability(net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
            if (h == null) continue;
            int couldTake = h.fill(new net.minecraftforge.fluids.FluidStack(targetFluid, Integer.MAX_VALUE), false);
            if (couldTake > 0) {
                freeMb = (int) Math.min((long) Integer.MAX_VALUE, (long) freeMb + (long) couldTake);
            }
        }

        // (3) final planned harvest for this mission
        this.plannedHarvestMb = Math.max(0, Math.min(harvestCapMb, freeMb));

        // (4) duration = min( baseCurveTime(capForTiming), ceil(plannedHarvest / rate) )
        // Keep your curve and denominator 25
        final int liquidCapacity = safeTagInt(stats, "liquidCapacity");
        final int intake        = safeTagInt(stats, "intakePower");
        final long rate         = DENOM_PER_INTAKE * (long) Math.max(1, intake); // mB/s

        final long durationSeconds;
        if (intake <= 0 || this.plannedHarvestMb <= 0) {
            durationSeconds = 180L; // safety default
        } else {
            // IMPORTANT: cap the capacity used by the curve to the harvest cap,
            // so durations match the table when harvest is smaller than tank size.
            final int capForTiming = infinite ? liquidCapacity : Math.min(liquidCapacity, harvestCapMb);

            double effCapMb  = computeEffectiveCapacityMb(capForTiming);
            long baseSeconds = (long) Math.floor(effCapMb / (double) rate);
            long capSeconds  = (long) Math.ceil((double) this.plannedHarvestMb / (double) rate);

            durationSeconds = Math.max(1L, Math.min(baseSeconds, capSeconds));
        }
        final long durationTicks = Math.max(1L, durationSeconds * 20L);


        MissionGasCollection miningMission =
            new MissionGasCollection(durationTicks, this, connectedInfrastructure, targetFluid);


        miningMission.setDimensionId(properties.getId());
        properties.addSatellite(miningMission);

        // broadcast
        if (!world.isRemote) {
            PacketHandler.sendToAll(new PacketSatellite(miningMission));
        }
        for (IInfrastructure i : connectedInfrastructure) {
            i.linkMission(miningMission);
        }

        this.setDead();
    }


    @Override
    protected void writeNetworkableNBT(NBTTagCompound nbt) {
        super.writeNetworkableNBT(nbt);

    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        super.readEntityFromNBT(nbt);

    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);

        if (id == PacketType.MENU_CHANGE.ordinal()) {
            out.writeShort(gasId);
        } else
            super.writeDataToNetwork(out, id);
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {


        if (packetId == PacketType.MENU_CHANGE.ordinal()) {
            nbt.setShort("gas", in.readShort());
        } else
            super.readDataFromNetwork(in, packetId, nbt);
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {


        if (id == PacketType.MENU_CHANGE.ordinal()) {

            DimensionProperties props = DimensionManager.getEffectiveDimId(world, this.getPosition());
            if (props.isGasGiant()) {

                gasId = nbt.getShort("gas");
                if (gasId < 0)
                    gasId = (short) (props.getHarvestableGasses().size() - 1);
                else if (gasId > props.getHarvestableGasses().size() - 1)
                    gasId = 0;

                if (!world.isRemote)
                    PacketHandler.sendToNearby(new PacketEntity(this, (byte) PacketType.MENU_CHANGE.ordinal()), world.provider.getDimension(), (int) posX, (int) posY, (int) posZ, 64d);
                else//index out of bounds somewhere here
                    atmText.setText(props.getHarvestableGasses().get(gasId).getLocalizedName(new FluidStack(props.getHarvestableGasses().get(gasId), 1)));
            }
        } else
            super.useNetworkData(player, side, id, nbt);
    }


    @Override
    public void writeMissionPersistentNBT(NBTTagCompound nbt) {
        super.writeMissionPersistentNBT(nbt);
        nbt.setInteger("fwd", forwardDirection.ordinal());

        nbt.setInteger("launchX", launchLocation.x);
        nbt.setInteger("launchY", launchLocation.y);
        nbt.setInteger("launchZ", launchLocation.z);

        nbt.setDouble("AlaunchX", actualLaunchLocation.x);
        nbt.setDouble("AlaunchY", actualLaunchLocation.y);
        nbt.setDouble("AlaunchZ", actualLaunchLocation.z);


        nbt.setShort("gas", gasId);
        nbt.setLong("plannedHarvestMb", Math.max(0L, this.plannedHarvestMb));
    }
    
    // handle possible bad data gracefully
    private static int safeTagInt(StatsRocket s, String key) {
        Object v = s.getStatTag(key);
        return (v instanceof Number) ? Math.max(0, ((Number) v).intValue()) : 0;
    }    

    // --- Nonlinear gas mission timing (alpha = 0.2) ---
    // effectiveCapacity = BASE_CAP * (liquidCapacity / BASE_CAP)^ALPHA
    // baseSeconds       = floor( effectiveCapacity / (DENOM_PER_INTAKE * intakePower) )
    // finalSeconds      = min(baseSeconds, ceil(plannedHarvestMb / (DENOM_PER_INTAKE * intakePower)))
    private static final long BASE_CAP = 64_000L;       // 64,000 mB (64 buckets)
    private static final double ALPHA = 0.2d;           // gentle sublinear scaling
    private static final long DENOM_PER_INTAKE = 25L;   // you picked "25 * intakePower"

    // Returns the effective capacity (mB) from your nonlinear curve.
    private static double computeEffectiveCapacityMb(int liquidCapacity) {
        double ratio = Math.max(1.0d, ((double) liquidCapacity) / (double) BASE_CAP);
        return (double) BASE_CAP * Math.pow(ratio, ALPHA);
    }

    private static long computeMissionDurationSeconds(int liquidCapacity, int intakePower) {
        // default fallback if bad data
        if (intakePower <= 0) return 180L; // 3 minutes safety default

        // scale in double to avoid precision loss, clamp ratio >= 1 to avoid shrinking below base
        double ratio = Math.max(1.0d, ((double) liquidCapacity) / (double) BASE_CAP);
        double effectiveCapacity = (double) BASE_CAP * Math.pow(ratio, ALPHA);

        long denom = DENOM_PER_INTAKE * (long) Math.max(1, intakePower);
        long secs = (long) Math.floor(effectiveCapacity / (double) denom);

        return Math.max(1L, secs); // never zero
    }

    // Consume ascent fuel exactly like the parent rocket does.
    // Returns true if fuel was consumed this tick (or fuel is not required by config).
    private boolean tryConsumeAscentFuel() {
        if (!ARConfiguration.getCurrentConfig().rocketRequireFuel)
            return true;

        final FuelRegistry.FuelType rt = getRocketFuelType();
        if (rt == null)
            return false;

        // current amounts
        int main = getFuelAmount(rt);
        final int mainRate = Math.max(1, getFuelConsumptionRate(rt)); // defensive

        if (rt == FuelRegistry.FuelType.LIQUID_BIPROPELLANT) {
            int ox = getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER);
            final int oxRate = Math.max(1, getFuelConsumptionRate(FuelRegistry.FuelType.LIQUID_OXIDIZER));

            // both-or-nothing
            if (main >= mainRate && ox >= oxRate) {
                setFuelAmount(rt, main - mainRate);
                setFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER, ox - oxRate);
            } else {
                return false; // not enough of one stream
            }

            // normalize + clear fluid names when empty
            setFuelAmount(rt, Math.max(0, getFuelAmount(rt)));
            setFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER, Math.max(0, getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER)));

            if (getFuelAmount(rt) == 0) {
                stats.setFuelFluid("null");
                stats.setWorkingFluid("null");
            }
            if (getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER) == 0) {
                stats.setOxidizerFluid("null");
            }
            return true;
        } else {
            if (main >= mainRate) {
                setFuelAmount(rt, main - mainRate);
            } else {
                return false;
            }

            // normalize + clear when empty
            setFuelAmount(rt, Math.max(0, getFuelAmount(rt)));
            if (getFuelAmount(rt) == 0) {
                stats.setFuelFluid("null");
                stats.setWorkingFluid("null");
            }
            return true;
        }
    }


    @Override
    public void readMissionPersistentNBT(NBTTagCompound nbt) {
        super.readMissionPersistentNBT(nbt);
        forwardDirection = EnumFacing.values()[nbt.getInteger("fwd")];

        launchLocation.x = nbt.getInteger("launchX");
        launchLocation.y = (short) nbt.getInteger("launchY");
        launchLocation.z = nbt.getInteger("launchZ");

        double ax = nbt.getDouble("AlaunchX");
        double ay = nbt.getDouble("AlaunchY");
        double az = nbt.getDouble("AlaunchZ");
        actualLaunchLocation = new Vec3d(ax, ay, az);

        gasId = nbt.getShort("gas");
    }

    // TOP integration
    @javax.annotation.Nullable
    public net.minecraftforge.fluids.Fluid getSelectedHarvestGas() {
        DimensionProperties props = DimensionManager.getEffectiveDimId(world, this.getPosition());

        if (props == null || !props.isGasGiant() || props.getHarvestableGasses().isEmpty()) {
            return null;
        }

        int idx = gasId;
        if (idx < 0 || idx >= props.getHarvestableGasses().size()) {
            idx = 0;
        }

        return props.getHarvestableGasses().get(idx);
    }
}
