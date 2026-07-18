package zmaster587.advancedRocketry.entity;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSand;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import zmaster587.advancedRocketry.inventory.modules.ModuleItemSlotButton;
import net.minecraft.world.World;
import net.minecraft.network.play.server.SPacketEntityTeleport;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.common.ForgeChunkManager.Type;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.oredict.OreDictionary;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.advancements.ARAdvancements;
import zmaster587.advancedRocketry.api.*;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.RocketFlightMode;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;
import zmaster587.advancedRocketry.api.RocketEvent.RocketLaunchEvent;
import zmaster587.advancedRocketry.api.RocketEvent.RocketPreLaunchEvent;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.api.satellite.SatelliteBase;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.block.BlockRegolith;
import zmaster587.advancedRocketry.client.SoundRocketEngine;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.event.PlanetEventHandler;
import zmaster587.advancedRocketry.inventory.IPlanetDefiner;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.inventory.modules.ModuleBrokenPart;
import zmaster587.advancedRocketry.inventory.modules.ModulePlanetSelector;
import zmaster587.advancedRocketry.inventory.modules.ModuleStellarBackground;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.item.ItemPackedStructure;
import zmaster587.advancedRocketry.item.ItemPlanetIdentificationChip;
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.mission.MissionOreMining;
import zmaster587.advancedRocketry.network.PacketSatellite;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.tile.TileBrokenPart;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine;
import zmaster587.advancedRocketry.tile.hatch.TileSatelliteHatch;
import zmaster587.advancedRocketry.util.*;
import zmaster587.advancedRocketry.world.util.BasicTeleporter;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.client.util.ProgressBarImage;
import zmaster587.libVulpes.gui.CommonResources;
import zmaster587.libVulpes.interfaces.INetworkEntity;
import zmaster587.libVulpes.inventory.GuiHandler;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;



public class EntityRocket extends EntityRocketBase implements INetworkEntity, IModularInventory, IProgressBar, IButtonInventory, ISelectionNotify, IPlanetDefiner {

    // set to 2 seconds because keyboard event is not sent to server
    // might be a temporary solution. Better be stuck 2 seconds than 25 seconds. but it needs 1 second to load
    private static final int DESCENT_TIMER = 2*20;

    //client sync stuff
    private Vec3d poscorrection;
    private Vec3d velcorrection;
    boolean last_was_in_orbit = false;
    boolean        reset_position = true;
    boolean reset_motion = true;

    private static final int BUTTON_ID_OFFSET = 25;
    private static final int STATION_LOC_OFFSET = 50;
    private static final int ENGINE_IGNITION_CNT = 100;
    private static final DataParameter<Integer> fuelLevelMonopropellant = EntityDataManager.createKey(EntityRocket.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> fuelLevelBipropellant = EntityDataManager.createKey(EntityRocket.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> fuelLevelOxidizer = EntityDataManager.createKey(EntityRocket.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> fuelLevelNuclearWorkingFluid = EntityDataManager.createKey(EntityRocket.class, DataSerializers.VARINT);
    private static final DataParameter<Boolean> INFLIGHT = EntityDataManager.createKey(EntityRocket.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> INORBIT = EntityDataManager.createKey(EntityRocket.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> INSPACEFLIGHT = EntityDataManager.createKey(EntityRocket.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> RCS_MODE = EntityDataManager.createKey(EntityRocket.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Integer> LAUNCH_COUNTER = EntityDataManager.createKey(EntityRocket.class, DataSerializers.VARINT);
    // Flight Assist velocity setpoint, body frame (fwd/right/up),
    // blocks/tick. Server-authoritative; replicated so the HUD can render
    // setpoint-vs-actual bars.
    private static final DataParameter<Float> FA_SP_FWD   = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> FA_SP_RIGHT = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> FA_SP_UP    = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    /** FF body-frame attitude quaternion (w, x, y, z), body&rarr;world.
     *  Replicated as four full-precision floats — NOT the byte-quantised
     *  yaw/pitch tracker or a single roll float — so the client has the complete,
     *  pole-free orientation. This is the FF attitude source of truth: loops and
     *  inversions have no gimbal lock, and the camera/render/seat derive from it. */
    private static final DataParameter<Float> FF_QW = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> FF_QX = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> FF_QY = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> FF_QZ = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    /** FF engine power level [0,1], replicated so the client engine sound tracks
     *  actual thrust. Set each FF tick to the magnitude of the thrust the engines
     *  applied this tick (world-frame Δv minus gravity, normalised by
     *  MAX_THRUST_ACCEL) — non-zero whenever thrust is produced in ANY direction
     *  (climb, cruise, strafe, or just cancelling gravity in a hover), which the
     *  classic {@code areEnginesRunning} (motionY&gt;0) missed &rarr; intermittent sound. */
    private static final DataParameter<Float> FF_ENGINE_POWER = EntityDataManager.createKey(EntityRocket.class, DataSerializers.FLOAT);
    private static long ERROR_DISPLAY_TIME = 100;
    //Offset for buttons linking to the tileEntityGrid
    private final int tilebuttonOffset = 3;
    public StorageChunk storage;
    protected long lastWorldTickTicked;
    protected int destinationDimId;
    protected ModulePlanetSelector container;
    boolean acceptedPacket = false;
    SpacePosition spacePosition;
    //true if we have posted the landed event after loading from nbt
    private transient boolean postedLandedAfterLoad = false;
    //true if the rocket is on decent
    private boolean isInOrbit;
    //True if the rocket isn't on the ground
    private boolean isInFlight;
    //used in the rare case a player goes to a non-existant space station
    private int lastDimensionFrom = 0;
    private boolean turningLeft, turningRight, turningUp, turningDownforWhat;
    private String errorStr;
    private long lastErrorTime = Long.MIN_VALUE;
    private ModuleText landingPadDisplayText;
    private SatelliteBase satellite;
    private int autoDescendTimer; // Is this value even used?
    //0 to 100, 100 is fully rotated and ready to go, 0 is normal mode
    private int rcs_mode_counter = 0;
    // Used to most of the logic, determining if in RCS mode or not
    private boolean rcs_mode = false;

    // ----- Free Flight Mode (additive — default mode is CLASSIC_LAUNCH, behaviour
    //       below is opt-in and bypassed entirely when classic) -----
    private RocketFlightMode flightMode = RocketFlightMode.DEFAULT;
    private FreeFlightInput  currentFreeFlightInput = FreeFlightInput.zero();
    /** Backend that realizes each FF tick's desired state (see
     *  {@link IFlightBackend}). Legacy backend owns the entity transform
     *  exactly as before; a ship-physics backend would own displacement instead. */
    private final IFlightBackend flightBackend = new LegacyFlightBackend();
    /** FF attitude source of truth (body&rarr;world quaternion).
     *  Integrated by BODY rates on the server; on the client it is the smoothed
     *  local estimate (predict from input + slerp toward the replicated
     *  {@link #FF_QW}/QX/QY/QZ). prev tracks the last tick for render slerp. */
    private FreeFlightPhysics.Quat ffQuat     = FreeFlightPhysics.Quat.IDENTITY;
    private FreeFlightPhysics.Quat prevFfQuat = FreeFlightPhysics.Quat.IDENTITY;
    /** Euler view of {@link #ffQuat}, derived every tick — kept only for legacy
     *  consumers that still read yaw/pitch/roll (seat, HUD bars, probes, vanilla
     *  systems). NEVER the source of truth; the quaternion is (avoids gimbal lock
     *  through loops/inversions). */
    private float freeFlightPitch = 0f;
    private float freeFlightRoll = 0f;
    private float prevFreeFlightRoll = 0f;
    /** Latched once a FF tick lands the rocket so we don't re-fire the landed event each tick. */
    private transient boolean freeFlightLandedLatched = false;
    /** Ticks elapsed since the last startFreeFlight() — harness-only debug telemetry. */
    private transient int freeFlightTicksSinceStart = 0;
    /** Engine-start liftoff target: hover altitude the craft eases
     *  onto after the engines start; NaN once the pilot takes over translation. */
    private double ffLiftoffTargetY = Double.NaN;
    /** Arms the landing detector: false from engine start until the craft first
     *  leaves the ground, so the liftoff itself can't read as a touchdown. */
    private boolean freeFlightHasLeftGround = false;
    /** Ticks over which the FF client absorbs a server-position correction
     *  (~ the entity updateFrequency, so jitter is smoothed, not snapped). */
    private static final double FF_CLIENT_CORRECT_TICKS = 3.0;
    /** Client-side FF snapshot targets: the latest authoritative server pose,
     *  used by the predict-then-correct smoothing in {@link #onUpdate()}. Each
     *  client tick dead-reckons from local velocity/rates and pulls the RESIDUAL
     *  toward these — never the raw gap, which already contains this tick's motion
     *  (double-counting it left the client a full tick ahead of the server: a
     *  constant lead that shifted with velocity &rarr; the FA-off jitter). Position is
     *  null / angles are NaN until the first FF packet arrives; all transient
     *  (client-only, re-seeded on load). */
    private transient Vec3d ffServerPos = null;
    /** Flight Assist (Elite-style FA-on/FA-off). ON by default = legacy drag behaviour. */
    private boolean flightAssistOn = true;

    // Mirror PlanetSelector Progressbars
    private DimensionProperties dimCache;

    // Preload ticket for destination chunks on launch event should be enough time to get a warm dimension
    private Ticket destPreloadTicket = null;
    private int    destPreloadDim    = Integer.MIN_VALUE;
    private long   destPreloadExpire = Long.MIN_VALUE; // world time when we auto-release    
    
    // Only show an oxidizer bar when the rocket actually provides oxidizer capacity.
    public boolean shouldShowOxBar() {
        return getFuelCapacity(FuelRegistry.FuelType.LIQUID_OXIDIZER) > 0;
    }


    public EntityRocket(World p_i1582_1_) {
        super(p_i1582_1_);

        poscorrection = new Vec3d(0,0,0);
        velcorrection = new Vec3d(0,0,0);
        reset_position = true;
        reset_motion = true;

        isInOrbit = false;
        stats = new StatsRocket();
        isInFlight = false;
        connectedInfrastructure = new LinkedList<>();
        infrastructureCoords = new HashSet<>();

        lastWorldTickTicked = p_i1582_1_.getTotalWorldTime();
        autoDescendTimer = 5000; // Is this value even used?
        landingPadDisplayText = new ModuleText(256, 16, "", 0x00FF00, 2f);
        landingPadDisplayText.setColor(0x00ff00);

        spacePosition = new SpacePosition();
        spacePosition.star = DimensionManager.getInstance().getStar(0);
    }

    public EntityRocket(World world, StorageChunk storage, StatsRocket stats, double x, double y, double z) {
        this(world);
        this.stats = stats;
        this.setPosition(x, y, z);
        this.storage = storage;
        this.storage.setEntity(this);
        initFromBounds();
        isInFlight = false;
        lastWorldTickTicked = world.getTotalWorldTime();
        autoDescendTimer = 5000; // Is this value even used?
        landingPadDisplayText = new ModuleText(256, 16, "", 0x00FF00, 2f);
        landingPadDisplayText.setColor(0x00ff00);
    }

    // PlanetSelector fixing methods
    private void selectSystem(int id) {
        if (id == Constants.INVALID_PLANET) {
            dimCache = null;
        } else {
            dimCache = DimensionManager.getInstance().getDimensionProperties(id);
        }
        planetSelectorProgress.setProps(dimCache);
    }


    @Override
    public void onSelected(Object sender) {
        if (sender instanceof ModulePlanetSelector) {
            int id = ((ModulePlanetSelector) sender).getSelectedSystem();
            selectSystem(id);
        }
    }
    @Override
    public void onSystemFocusChanged(Object sender) {
        if (sender instanceof ModulePlanetSelector) {
            int id = ((ModulePlanetSelector) sender).getSelectedSystem();
            selectSystem(id);
        }
    }

    private void clearPlanetSelectorCache() {
        dimCache = null;
        planetSelectorProgress.setProps(null);

        // Optional but nice: drop GUI references so nothing keeps stale state
        container = null;
    }

    private final PlanetSelectorProgressAdapter planetSelectorProgress = new PlanetSelectorProgressAdapter();

    private static final class PlanetSelectorProgressAdapter implements IProgressBar {
        private DimensionProperties props;

        void setProps(DimensionProperties props) {
            this.props = props;
        }

        @Override
        public float getNormallizedProgress(int id) {
            int total = getTotalProgress(id);
            if (total <= 0) return 0f;
            return MathHelper.clamp(getProgress(id) / (float) total, 0f, 1f);
        }

        @Override public void setProgress(int id, int progress) {}
        @Override public void setTotalProgress(int id, int progress) {}

        @Override
        public int getProgress(int id) {
            if (props == null) return 0;
            // Placeholder style consistent with TilePlanetSelector
            if (id == 0 || id == 1 || id == 2) return 25;
            return 0;
        }

        @Override
        public int getTotalProgress(int id) {
            if (props == null) return 50;

            if (id == 0) return Math.max(1, props.getAtmosphereDensity() / 16);
            if (id == 1) return Math.max(1, props.orbitalDist / 16);
            if (id == 2) return Math.max(1, (int)(props.gravitationalMultiplier * 50));
            return 1;
        }
    }


    /**
     * @param blockState the blockstate to damage
     * @return the blockstate that the input blockstate turns into
     */
    private static IBlockState getDamagedBlock(IBlockState blockState) {
        ItemStack stack = new ItemStack(blockState.getBlock(), 1, blockState.getBlock().getMetaFromState(blockState));
        if (ZUtils.isItemInOreDict(stack, "stone") || blockState.getBlock() == Blocks.STONEBRICK || ZUtils.isItemInOreDict(stack, "bricksStone")) {
            return Blocks.COBBLESTONE.getDefaultState();
        } else if (ZUtils.isItemInOreDict(stack, "cobblestone") || ZUtils.isItemInOreDict(stack, "gravel")) {
            return AdvancedRocketryBlocks.blockBasalt.getDefaultState();
        } else if (blockState.getBlock() == AdvancedRocketryBlocks.blockBasalt) {
            return Blocks.MAGMA.getDefaultState();
        } else if (blockState.getBlock() == Blocks.NETHERRACK) {
            return Blocks.MAGMA.getDefaultState();
        } else if (blockState.getBlock() == Blocks.MAGMA) {
            return Blocks.LAVA.getDefaultState();
        } else if (blockState.getMaterial() == Material.GRASS) {
            return Blocks.DIRT.getDefaultState();
        } else if (blockState.getMaterial() == Material.GROUND && !(blockState.getBlock() instanceof BlockRegolith)) {
            return Blocks.SAND.getDefaultState();
        } else if (blockState.getBlock() instanceof BlockSand || blockState.getBlock() instanceof BlockRegolith || ZUtils.isItemInOreDict(stack, "regolith") || ZUtils.isItemInOreDict(stack, "sandstone")) {
            return Blocks.GLASS.getDefaultState();
        } else if (blockState.getMaterial() == Material.ICE || blockState.getMaterial() == Material.PACKED_ICE || ((blockState.getMaterial() == Material.SNOW || blockState.getMaterial() == Material.CRAFTED_SNOW) && blockState.getBlock() != Blocks.SNOW_LAYER)) {
            return Blocks.WATER.getDefaultState();
        } else if (blockState.getMaterial() == Material.WATER || blockState.getBlock() == Blocks.SNOW_LAYER) {
            return Blocks.AIR.getDefaultState();
        } else if (blockState.getMaterial() == Material.WOOD || blockState.getMaterial() == Material.LEAVES || blockState.getMaterial() == Material.PLANTS || blockState.getMaterial() == Material.GOURD || blockState.getMaterial() == Material.WEB || blockState.getMaterial() == Material.CLOTH || blockState.getMaterial() == Material.CARPET || blockState.getMaterial() == Material.CACTUS || blockState.getMaterial() == Material.SPONGE) {
            return Blocks.FIRE.getDefaultState();
        }
        return null;
    }

    private void preloadDestinationChunks(int dimId, double x, double z, int radiusChunks, int holdSeconds) {
        if (world.isRemote) return;

        // Clean any previous
        releaseDestinationPreload();

        MinecraftServer server = this.getServer();
        if (server == null) return;

        WorldServer target = server.getWorld(dimId);
        if (target == null) return; // dimension not available

        // Request a NORMAL ticket in the DESTINATION world (not bound to this entity)
        destPreloadTicket = ForgeChunkManager.requestTicket(AdvancedRocketry.instance, target, Type.NORMAL);
        if (destPreloadTicket == null) {
            AdvancedRocketry.logger.warn("[EntityRocket] Could not acquire destination preload ticket for dim {}", dimId);
            return;
        }

        int cx = ((int)Math.floor(x)) >> 4;
        int cz = ((int)Math.floor(z)) >> 4;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                ForgeChunkManager.forceChunk(destPreloadTicket, new ChunkPos(cx + dx, cz + dz));
            }
        }

        destPreloadDim    = dimId;
        // use *server* time base;  holdSeconds should be enough to cover ascent (~6s)
        destPreloadExpire = world.getTotalWorldTime() + holdSeconds * 20L;
        AdvancedRocketry.logger.debug("[EntityRocket] Preloaded 3x3 chunks at dim {} around {},{} for ~{}s",
                dimId, (cx<<4), (cz<<4), holdSeconds);
    }

    private void releaseDestinationPreload() {
        if (destPreloadTicket != null) {
            ForgeChunkManager.releaseTicket(destPreloadTicket);
            destPreloadTicket = null;
            destPreloadDim    = Integer.MIN_VALUE;
            destPreloadExpire = Long.MIN_VALUE;
        }
    }


    /**
     * Deprecated by Free Flight Mode (feature/true_rcs). The R-keybind path
     * still arrives here for save-compat, but instead of toggling RCS we
     * surface a redirect message pointing the pilot at FF mode (M-key).
     *
     * <p>The {@link #RCS_MODE} datawatcher field and {@link #setRCS} mutator
     * remain functional — solar-map deep-space navigation ({@code getInSpaceFlight()})
     * still flips RCS internally to drive its own steering branch. That path
     * is untouched until {@code solar-map flight} migrates to FF (see
     * design followup task).
     */
    public void toggleRCS() {
        // Server-side: report deprecation to the pilot via a plain message. No
        // mutation of RCS_MODE — legacy state remains, solar-map flight
        // unaffected. Uses messagePilot, NOT setError: setError carries
        // launch-abort semantics (LAUNCH_COUNTER = -1 + RocketAbortEvent), which
        // a deprecation notice must never trigger.
        if (!world.isRemote) {
            messagePilot("msg.entity.rocket.rcsDeprecated");
        }
    }

    public boolean getRCS() {
        return dataManager.get(RCS_MODE);
    }

    private void setRCS(boolean status) {
        dataManager.set(RCS_MODE, status);
    }

    public boolean getInSpaceFlight() {
        return dataManager.get(INSPACEFLIGHT);
    }

    private void setInSpaceFlight(boolean status) {
        dataManager.set(INSPACEFLIGHT, status);
    }

    public int getRCSRotateProgress() {
        return rcs_mode_counter;
    }

    @Override
    public AxisAlignedBB getEntityBoundingBox() {
        if (storage != null) {
            //MobileAABB aabb = new MobileAABB(super.getEntityBoundingBox());
            //aabb.setStorageChunk(storage);
            //aabb.setRemote(worldObj.isRemote);
            //return aabb;
            return super.getEntityBoundingBox();
        }
        return new AxisAlignedBB(0, 0, 0, 1, 1, 1);
    }

    @Override
    public void setEntityBoundingBox(AxisAlignedBB bb) {
        //if(storage != null)
        //	super.setEntityBoundingBox(bb.offset(0, storage.getSizeY(),0));
        //else
        super.setEntityBoundingBox(bb);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox() {
        return getEntityBoundingBox();
    }

    public SpacePosition getSpacePosition() {
        SpacePosition planetPosition;
        return spacePosition;
    }

    public void disconnectInfrastructure(IInfrastructure infrastructure) {
        infrastructure.unlinkRocket();
        infrastructureCoords.remove(new HashedBlockPosition(((TileEntity) infrastructure).getPos()));

        if (!world.isRemote) {
            int[] pos = {((TileEntity) infrastructure).getPos().getX(), ((TileEntity) infrastructure).getPos().getY(), ((TileEntity) infrastructure).getPos().getZ()};

            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setIntArray("pos", pos);
            //PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, (byte)PacketType.DISCONNECTINFRASTRUCTURE.ordinal(), nbt), this);
        }
    }

    @Override
    public void linkInfrastructure(IInfrastructure tile) {
        super.linkInfrastructure(tile);
        if (tile instanceof TileEntity)
            infrastructureCoords.add(new HashedBlockPosition(((TileEntity) tile).getPos()));
    }

    @Override
    public String getTextOverlay() {

        ERROR_DISPLAY_TIME = 100;
        if (this.world.getTotalWorldTime() < this.lastErrorTime + ERROR_DISPLAY_TIME)
            return errorStr;

        //Get destination string
        String displayStr = LibVulpes.proxy.getLocalizedString("msg.na");
        if (storage != null) {
            int dimid = storage.getDestinationDimId(this.world.provider.getDimension(), (int) posX, (int) posZ);

            if (dimid == ARConfiguration.getCurrentConfig().spaceDimId) {
                Vector3F<Float> vec = storage.getDestinationCoordinates(dimid, false);
                if (vec != null) {

                    ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(new BlockPos(vec.x, vec.y, vec.z));

                    if (spaceObject != null) {
                        displayStr = LibVulpes.proxy.getLocalizedString("msg.entity.rocket.station") + spaceObject.getId();

                        StationLandingLocation location = storage.getGuidanceComputer().getLandingLocation(spaceObject.getId());

                        if (location != null) {
                            displayStr = displayStr + "\n" + LibVulpes.proxy.getLocalizedString("msg.entity.rocket.pad") + location;
                        }
                    }
                }
            } else if (dimid != Constants.INVALID_PLANET && dimid != SpaceObjectManager.WARPDIMID) {

                boolean goingToOrbit = ARConfiguration.getCurrentConfig().experimentalSpaceFlight && storage.getGuidanceComputer().isEmpty();

                if (goingToOrbit)
                    displayStr = "Orbit";
                else {
                    displayStr = DimensionManager.getInstance().getDimensionProperties(dimid).getName();
                    Vector3F<Float> loc = storage.getDestinationCoordinates(dimid, false);
                    if (loc != null) {
                        String name = storage.getDestinationName(dimid);
                        if (!name.isEmpty())
                            displayStr += String.format("\n%s: %s", LibVulpes.proxy.getLocalizedString("msg.label.destName"), name);
                        displayStr += String.format("\n%s: %.0f, %.0f", LibVulpes.proxy.getLocalizedString("msg.label.coords"), loc.x, loc.z);
                    } else {
                        displayStr += "\nCoords: ???, ???";
                    }
                }
            }
        }

        if (dataManager.get(LAUNCH_COUNTER) >= 0) {
            return LibVulpes.proxy.getLocalizedString("msg.entity.rocket.launch") + (dataManager.get(LAUNCH_COUNTER) / 20) + "\n" +
                    LibVulpes.proxy.getLocalizedString("msg.entity.rocket.launch2");
        }

        if (DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).isAsteroid()) {
            if (getRCS())
                return LibVulpes.proxy.getLocalizedString("msg.entity.rocket.rcs") + ": " + getRCS();
            else
                displayStr += "\n" + LibVulpes.proxy.getLocalizedString("msg.entity.rocket.rcs") + ": " + getRCS();
        }

        if (isInOrbit() && !isInFlight()) {
            //return LibVulpes.proxy.getLocalizedString("msg.entity.rocket.descend.1") + "\n" + LibVulpes.proxy.getLocalizedString("msg.entity.rocket.descend.2") + ((DESCENT_TIMER - this.ticksExisted) / 20);
            return super.getTextOverlay();
        }
        else if (!isInFlight())
            return LibVulpes.proxy.getLocalizedString("msg.entity.rocket.ascend.1") + "\n" + LibVulpes.proxy.getLocalizedString("msg.entity.rocket.ascend.2") + displayStr;

        return super.getTextOverlay();
    }

    @Nullable
    private EntityPlayer getPilot() {
        for (Entity e : getPassengers()) {
            if (e instanceof EntityPlayer) return (EntityPlayer) e;
        }
        return null;
    }

    @Nonnull
    private ItemStack getGateArtifact(@Nullable DimensionProperties destProps) {
        if (destProps == null) return ItemStack.EMPTY;

        List<ItemStack> req = destProps.getRequiredArtifacts();
        if (req == null || req.isEmpty()) return ItemStack.EMPTY;

        // Contract: always exactly 1 artifact
        return req.get(0);
    }

    private boolean pilotHasArtifact(@Nullable EntityPlayer pilot, @Nonnull ItemStack req) {
        if (pilot == null || req.isEmpty()) return false;

        for (ItemStack have : pilot.inventory.mainInventory)  if (matchesRequirement(have, req)) return true;
        for (ItemStack have : pilot.inventory.armorInventory) if (matchesRequirement(have, req)) return true;
        for (ItemStack have : pilot.inventory.offHandInventory) if (matchesRequirement(have, req)) return true;

        return false;
    }

    private boolean matchesRequirement(@Nonnull ItemStack have, @Nonnull ItemStack req) {
        if (have.isEmpty()) return false;
        if (have.getItem() != req.getItem()) return false;

        // meta / wildcard
        int rMeta = req.getItemDamage();
        if (rMeta != OreDictionary.WILDCARD_VALUE && have.getItemDamage() != rMeta) return false;

        // OPTIONAL: require NBT match if your artifact uses NBT (uncomment if needed)
        // if (req.hasTagCompound() && !NBTTagCompound.areNBTEquals(req.getTagCompound(), have.getTagCompound())) return false;

        return have.getCount() >= req.getCount();
    }


    
    private static String packReason(String key, Object... args) {
        if (args == null || args.length == 0) return key;

        StringBuilder sb = new StringBuilder(key);
        for (Object a : args) {
            sb.append('|');
            String s = String.valueOf(a);
            // Avoid breaking the delimiter if an arg contains '|'
            sb.append(s.replace("|", "/"));
        }
        return sb.toString();
    }

    /** Send a translated informational message to the rocket's passengers (no abort). */
    private void messagePilot(String key, Object... args) {
        if (world.isRemote) {
            return;
        }
        for (Entity e : this.getPassengers()) {
            if (e instanceof EntityPlayerMP) {
                ((EntityPlayerMP) e).sendMessage(new net.minecraft.util.text.TextComponentTranslation(key, args));
            }
        }
    }

    private void setError(String key, Object... args) {
        this.errorStr = key;
        this.lastErrorTime = this.world.getTotalWorldTime();

        if (!world.isRemote) {
            for (Entity e : this.getPassengers()) {
                if (e instanceof EntityPlayerMP) {
                    ((EntityPlayerMP) e).sendMessage(
                        new net.minecraft.util.text.TextComponentTranslation(key, args)
                    );
                }
            }

            // send key + args to monitoring station
            String packed = packReason(key, args);
            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketAbortEvent(this, packed));

            this.dataManager.set(LAUNCH_COUNTER, -1);
        }
    }


    @Override
    public void setPosition(double x, double y,
                            double z) {
        super.setPosition(x, y, z);

        if (storage != null) {
            if (getRCS()) {
                float sizeX = storage.getSizeX() / 2.0f;
                float sizeY = storage.getSizeY() / 2.0f;
                float sizeZ = storage.getSizeZ();
                setEntityBoundingBox(new AxisAlignedBB(x - sizeX, y - this.getYOffset() + sizeZ * 0.5 + 0.5, z - sizeY, x + sizeX, y + sizeZ * 1.5 + .5 - this.getYOffset(), z + sizeY));
            } else {
                float sizeX = storage.getSizeX() / 2.0f;
                float sizeY = storage.getSizeY();
                float sizeZ = storage.getSizeZ() / 2.0f;
                setEntityBoundingBox(new AxisAlignedBB(x - sizeX, y - this.getYOffset(), z - sizeZ, x + sizeX, y + sizeY - this.getYOffset(), z + sizeZ));
            }
        }
    }

    @Override
    public void resetPositionToBB() {
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        if (storage != null && getRCS()) {
            float sizeX = storage.getSizeX() / 2.0f;
            float sizeY = storage.getSizeY() / 2.0f;
            float sizeZ = storage.getSizeZ();
            //setEntityBoundingBox(new AxisAlignedBB(x - sizeX, y - (double)this.getYOffset() + sizeZ*0.5 + 0.5, z - sizeY, x + sizeX, y + sizeZ*1.5 + .5 - (double)this.getYOffset(), z + sizeY));

            this.posX = (axisalignedbb.minX + axisalignedbb.maxX) / 2.0D;
            this.posY = axisalignedbb.minY - sizeZ * 0.5 - 0.5;
            this.posZ = (axisalignedbb.minZ + axisalignedbb.maxZ) / 2.0D;
        } else {
            super.resetPositionToBB();
        }
    }

    /**
     * @param fuelType
     * @return the amount of fuel stored in the rocket
     */
    public int getFuelAmount(@Nullable FuelType fuelType) {
        if (fuelType != null) {
            int amount;

            switch (fuelType) {
                case LIQUID_MONOPROPELLANT:
                    amount = dataManager.get(fuelLevelMonopropellant);
                    break;
                case LIQUID_BIPROPELLANT:
                    amount = dataManager.get(fuelLevelBipropellant);
                    break;
                case LIQUID_OXIDIZER:
                    amount = dataManager.get(fuelLevelOxidizer);
                    break;
                case NUCLEAR_WORKING_FLUID:
                    amount = dataManager.get(fuelLevelNuclearWorkingFluid);
                    break;
                default:
                    return 0;
            }

            stats.setFuelAmount(fuelType, amount);
            return amount;
        }

        return 0;
    }

    /**
     * Adds fuel and updates the datawatcher
     *
     * @param fuelType
     * @param amount   amount of fuel to add
     * @return the amount of fuel added
     */
    public int addFuelAmount(@Nonnull FuelType fuelType, int amount) {
        int ret = stats.addFuelAmount(fuelType, amount);
        setFuelAmount(fuelType, stats.getFuelAmount(fuelType));
        return ret;
    }

    /**
     * Updates the data option
     *
     * @param fuelType
     * @param amt      sets the amount of monopropellant fuel in the rocket
     */
    public void setFuelAmount(@Nonnull FuelType fuelType, int amt) {
        if (fuelType == FuelType.LIQUID_MONOPROPELLANT) {
            dataManager.set(fuelLevelMonopropellant, amt);
            dataManager.setDirty(fuelLevelMonopropellant);
        } else if (fuelType == FuelType.LIQUID_BIPROPELLANT) {
            dataManager.set(fuelLevelBipropellant, amt);
            dataManager.setDirty(fuelLevelBipropellant);
        } else if (fuelType == FuelType.LIQUID_OXIDIZER) {
            dataManager.set(fuelLevelOxidizer, amt);
            dataManager.setDirty(fuelLevelOxidizer);
        } else if (fuelType == FuelType.NUCLEAR_WORKING_FLUID) {
            dataManager.set(fuelLevelNuclearWorkingFluid, amt);
            dataManager.setDirty(fuelLevelNuclearWorkingFluid);
        }
    }

    /**
     * @param fuelType sets the type of fuel to set a rate for
     * @param rate     sets the rate of fuel in the rocket
     */
    public void setFuelConsumptionRate(@Nonnull FuelType fuelType, int rate) {
        stats.setFuelRate(fuelType, rate);
    }

    /**
     * @param fuelType is the fuel type to get
     * @return gets the fuel capacity of the rocket
     */
    public int getFuelCapacity(@Nullable FuelType fuelType) {
        return stats.getFuelCapacity(fuelType);
    }

    /**
     * @param fuelType is the fuel type to get
     * @return the rate of fuel consumption for the rocket
     */
    public int getFuelConsumptionRate(@Nullable FuelType fuelType) {
        return stats.getFuelRate(fuelType);
    }

    /**
     * @return the fuel type that this rocket uses, null if the rocket does not use any
     */
    @Nullable
    public FuelType getRocketFuelType() {
        if (getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT) > 0)
            return FuelType.LIQUID_MONOPROPELLANT;
        else if (getFuelCapacity(FuelType.LIQUID_BIPROPELLANT) > 0)
            return FuelType.LIQUID_BIPROPELLANT;
        else if (getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID) > 0)
            return FuelType.NUCLEAR_WORKING_FLUID;
        return null;
    }

    @Override
    public void setEntityId(int id) {
        super.setEntityId(id);
        //Ask server for nbt data
        if (world.isRemote) {
            PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.REQUESTNBT.ordinal()));
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }


    /**
     * If the rocket is in flight, ie the rocket has taken off and has not touched the ground
     *
     * @return true if in flight
     */
    public boolean isInFlight() {
        if (!world.isRemote) {
            return isInFlight;
        }
        return this.dataManager.get(INFLIGHT);
    }

    /**
     * Sets the the status of flight of the rocket and updates the datawatcher
     *
     * @param inFlight status of flight
     */
    public void setInFlight(boolean inFlight) {
        this.isInFlight = inFlight;
        this.dataManager.set(INFLIGHT, inFlight);
        this.dataManager.setDirty(INFLIGHT);
    }

    // ---- Free Flight Mode accessors / mutators ----

    public RocketFlightMode getFlightMode() {
        return flightMode == null ? RocketFlightMode.DEFAULT : flightMode;
    }

    /**
     * Set the rocket's flight mode. Persisted to NBT. Server-side mutation only
     * (callers from packet handlers must validate sender authority first).
     */
    public void setFlightMode(RocketFlightMode mode) {
        this.flightMode = (mode == null ? RocketFlightMode.DEFAULT : mode);
    }

    public boolean isFreeFlight() {
        return getFlightMode() == RocketFlightMode.FREE_FLIGHT;
    }

    public boolean isClassicLaunch() {
        return getFlightMode() == RocketFlightMode.CLASSIC_LAUNCH;
    }

    /**
     * Server-side application of a pilot input packet. The {@link FreeFlightInput}
     * constructor already clamps per-channel; callers may forward a freshly-deserialised
     * input without re-validating ranges.
     *
     * <p>No-op if the rocket is not in {@link RocketFlightMode#FREE_FLIGHT} —
     * input is dropped silently to keep the contract symmetric ("input is intent,
     * server decides").
     */
    public void applyFreeFlightInput(FreeFlightInput input) {
        if (input == null) return;
        if (!isFreeFlight()) return;
        this.currentFreeFlightInput = input;
        ffTrace("applyFreeFlightInput " + input);
    }

    public FreeFlightInput getCurrentFreeFlightInput() {
        return currentFreeFlightInput;
    }

    public float getFreeFlightPitch() {
        return freeFlightPitch;
    }

    public float getFreeFlightRoll() {
        return freeFlightRoll;
    }

    public float getPrevFreeFlightRoll() {
        return prevFreeFlightRoll;
    }

    /** Current FF attitude (body&rarr;world). Client render/camera slerp between
     *  {@link #getPrevFfQuat()} and this by partialTicks. */
    public FreeFlightPhysics.Quat getFfQuat() {
        return ffQuat == null ? FreeFlightPhysics.Quat.IDENTITY : ffQuat;
    }

    public FreeFlightPhysics.Quat getPrevFfQuat() {
        return prevFfQuat == null ? FreeFlightPhysics.Quat.IDENTITY : prevFfQuat;
    }

    public boolean isFlightAssistOn() {
        return flightAssistOn;
    }

    /** Persistent flight-assist toggle. Server-side authority only.
     *  Re-enabling FA mid-flight captures the CURRENT velocity (projected into
     *  the body frame) as the setpoint, so the toggle never jerks the craft
     *; disabling zeroes the setpoint. */
    public void setFlightAssistOn(boolean on) {
        if (!world.isRemote && on != this.flightAssistOn && isFreeFlight()) {
            if (on && isInFlight()) {
                double[] sp = FreeFlightPhysics.worldToBodyQ(
                        this.motionX, this.motionY, this.motionZ, this.ffQuat);
                setFaSetpoint(sp[0], sp[1], sp[2]);
                ffTrace("FA re-enabled: setpoint captured from velocity "
                        + sp[0] + "/" + sp[1] + "/" + sp[2]);
            } else {
                setFaSetpoint(0, 0, 0);
            }
        }
        this.flightAssistOn = on;
    }

    /**
     * Start the Free Flight engines. Sets isInFlight, resets the
     * latched-landed flag, zeros input so the rocket doesn't inherit stale
     * intent, and arms the liftoff assist: the craft eases ~1 block off the
     * pad and hovers there until the pilot takes over (no decaying takeoff
     * kick, no land-grace window — the landing detector is simply disarmed
     * until the craft has actually left the ground). Server-side only.
     */
    public void startFreeFlight() {
        if (world.isRemote) return;
        setInFlight(true);
        freeFlightLandedLatched = false;
        freeFlightHasLeftGround = false;
        freeFlightTicksSinceStart = 0;
        ffLiftoffTargetY = this.posY + 1.0;
        currentFreeFlightInput = FreeFlightInput.zero();
        setFaSetpoint(0, 0, 0); // fresh flight starts at hover intent
        this.ffQuat = FreeFlightPhysics.Quat.IDENTITY; // upright reference attitude
        this.prevFfQuat = FreeFlightPhysics.Quat.IDENTITY;
        this.dataManager.set(FF_QW, 1f);
        this.dataManager.set(FF_QX, 0f);
        this.dataManager.set(FF_QY, 0f);
        this.dataManager.set(FF_QZ, 0f);
        ffTrace("startFreeFlight mode=" + getFlightMode() + " thrust=" + stats.getThrust()
                + " weight=" + stats.getWeight() + " accel=" + stats.getAcceleration(1f)
                + " liftoffTargetY=" + ffLiftoffTargetY);
    }

    /**
     * Fuel availability for FF thrust — mirrors the classic isBurningFuel()
     * gate: bipropellant needs BOTH fuel and oxidizer; when fuel isn't
     * required by config, thrust is always available. Used by the per-tick
     * physics AND the engine-start validation.
     */
    private boolean hasFreeFlightThrustFuel() {
        if (!ARConfiguration.getCurrentConfig().rocketRequireFuel) return true;
        FuelType ft = getRocketFuelType();
        if (ft == null) return false;
        if (ft == FuelType.LIQUID_BIPROPELLANT)
            return getFuelAmount(ft) > 0 && getFuelAmount(FuelType.LIQUID_OXIDIZER) > 0;
        return getFuelAmount(ft) > 0;
    }

    /**
     * Shared gate for EVERY entry into {@link #startFreeFlight()}: the craft must
     * have fuel AND positive climb authority (TWR &gt; 1) to leave the pad. Without
     * this a fuel-less / underpowered FF rocket launched via {@link #prepareLaunch()}
     * (Space, or a redstone monitoring station calling prepareLaunch directly) would
     * set isInFlight but never thrust, never leave the ground, and thus never
     * re-land ({@code freeFlightHasLeftGround} stays false so the landing detector
     * never arms) — a permanent on-pad in-flight dead-state with no way to restart
     * the engine. Mirrors the classic-launch fuel gate. Server-side only.
     */
    private boolean canStartFreeFlight() {
        if (!hasFreeFlightThrustFuel()) {
            return false;
        }
        float gravMult = DimensionManager.getInstance()
                .getDimensionProperties(this.world.provider.getDimension())
                .getGravitationalMultiplier();
        return stats.getAcceleration(gravMult) > 0;
    }

    /** Harness-only ([FF-TRACE]) lifecycle log for the live FF path. Gated on the
     *  same flag as the /artest probes; pass -Dadvancedrocketry.tests=true. */
    private void ffTrace(String msg) {
        if (TestProbeCommandRegistration.isTestMode()) {
            AdvancedRocketry.logger.info("[FF-TRACE/" + (world.isRemote ? "C" : "S") + "] " + msg);
        }
    }

    /**
     * One server-side free-flight physics step. Pure delegation to
     * {@link FreeFlightPhysics#step}: this method exists so the test harness
     * can also single-step from the probe command without booting MC physics.
     */
    public void tickFreeFlight() {
        if (world.isRemote) return;
        if (!isFreeFlight() || !isInFlight()) return;

        freeFlightTicksSinceStart++;
        if (freeFlightTicksSinceStart == 1) ffTrace("tickFreeFlight first tick");

        FuelType ft = getRocketFuelType();
        boolean requireFuel = ARConfiguration.getCurrentConfig().rocketRequireFuel;
        boolean canThrust = hasFreeFlightThrustFuel();

        float gravMult = DimensionManager.getInstance()
                .getDimensionProperties(this.world.provider.getDimension())
                .getGravitationalMultiplier();
        // Per-tick gravity decrement scaled to existing space-flight convention.
        double gravity = 0.04 * gravMult;

        // Thrust authority comes from the SAME classic stat the launch path uses:
        // getAcceleration(g) is the net per-tick climb at full thrust and is
        // thrust-multiplier / weight-system / gravityAffectsFuel aware. Adding the
        // per-tick gravity back yields the gross thrust accel, so that at full
        // vertical throttle net == getAcceleration -> the FF climb gate is exactly
        // the classic thrust-to-weight gate (TWR > 1). No invented /10000 scale.
        double thrustMag = stats.getAcceleration(gravMult) + gravity;

        // Engine-start liftoff: until the pilot first gives any
        // translation input, ease onto the hover point ~1 block above the pad
        // (orientation channels stay live through the regular step below it).
        FreeFlightInput in = this.currentFreeFlightInput == null
                ? FreeFlightInput.zero() : this.currentFreeFlightInput;
        boolean translationIdle = Math.abs(in.throttleForward) < 1e-5f
                && Math.abs(in.throttleVertical) < 1e-5f
                && Math.abs(in.strafeInput) < 1e-5f
                && !in.cutActive;
        if (!translationIdle) {
            ffLiftoffTargetY = Double.NaN; // pilot took over — assist ends for this flight
        }
        boolean liftoffHover = !Double.isNaN(ffLiftoffTargetY) && canThrust;

        // Integrate the body-frame ATTITUDE by this tick's rotation rates: pitch
        // about the craft right axis, yaw about up, roll about the nose. A
        // quaternion (not a world-frame Euler triple) -> no gimbal lock, so loops
        // work and the controls never invert relative to the pilot when the craft
        // is rolled or inverted.
        double pitchRate = in.pitchInput * FreeFlightPhysics.MAX_PITCH_RATE;
        double yawRate   = in.yawInput   * FreeFlightPhysics.MAX_YAW_RATE;
        double rollRate  = in.rollInput  * FreeFlightPhysics.MAX_ROLL_RATE;
        FreeFlightPhysics.Quat newQuat = FreeFlightPhysics.integrateBodyRates(
                this.ffQuat, pitchRate, yawRate, rollRate);

        FreeFlightPhysics.Step result;
        if (liftoffHover) {
            // Ease onto the hover point ~1 block off the pad; orientation still
            // integrates (newQuat above), so the pilot can aim while lifting off.
            result = FreeFlightPhysics.liftoffStep(
                    this.posY, ffLiftoffTargetY,
                    this.motionX, this.motionY, this.motionZ,
                    0f, 0f, thrustMag);
        } else if (this.flightAssistOn) {
            // Flight Assist: translation keys edit the body-frame
            // velocity SETPOINT (release keeps it; X zeroes it); FA computes the
            // thrust that tracks it in the freshly-integrated attitude frame.
            double[] sp = FreeFlightPhysics.rampSetpoint(
                    getFaSetpointForward(), getFaSetpointRight(), getFaSetpointUp(), in);
            setFaSetpoint(sp[0], sp[1], sp[2]);
            result = FreeFlightPhysics.faStep(
                    this.motionX, this.motionY, this.motionZ, newQuat,
                    sp[0], sp[1], sp[2], thrustMag, gravity, canThrust);
        } else {
            // Newtonian (FA off): direct body-frame thrust, coast on release.
            result = FreeFlightPhysics.translateNewtonian(
                    this.motionX, this.motionY, this.motionZ, newQuat,
                    in, thrustMag, gravity, canThrust);
        }

        // Engine power = magnitude of the thrust the engines applied this tick,
        // i.e. the world-frame Δv MINUS gravity (gravity is not thrust): the
        // difference between the resulting motion and where the craft would have
        // coasted. Non-zero for thrust in ANY direction — climb, cruise, strafe,
        // or just holding a hover against gravity — so the client sound plays
        // whenever the engines actually fire, at a volume proportional to |thrust|.
        // Computed from the CURRENT (pre-application) motion, before the backend
        // overwrites it with result.motion*.
        double tdx = result.motionX - this.motionX;
        double tdy = result.motionY - (this.motionY - gravity);
        double tdz = result.motionZ - this.motionZ;
        float enginePow = (float) Math.min(1.0,
                Math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz) / FreeFlightPhysics.MAX_THRUST_ACCEL);

        // Fuel burn — classic semantics: while thrust is applied AND fuel is
        // required, drain getFuelConsumptionRate per tick (oxidizer too for
        // bipropellant), and null out the fluid when a tank empties — exactly
        // like the classic ascent burn in onUpdate(). A gameplay side-effect, not
        // part of the transform, so it stays here regardless of backend.
        if (requireFuel && result.thrustApplied && ft != null) {
            setFuelAmount(ft, getFuelAmount(ft) - getFuelConsumptionRate(ft));
            if (ft == FuelType.LIQUID_BIPROPELLANT)
                setFuelAmount(FuelType.LIQUID_OXIDIZER,
                        getFuelAmount(FuelType.LIQUID_OXIDIZER) - getFuelConsumptionRate(FuelType.LIQUID_OXIDIZER));

            if (getFuelAmount(ft) == 0) {
                stats.setFuelFluid("null");
                stats.setWorkingFluid("null");
            }
            if (ft == FuelType.LIQUID_BIPROPELLANT && getFuelAmount(FuelType.LIQUID_OXIDIZER) == 0) {
                stats.setOxidizerFluid("null");
            }
        }

        // Realize the desired flight state through the pluggable backend: commit
        // the attitude, derive the legacy Euler view, replicate FF_Q*/engine-power,
        // write motion* and displace the entity. The legacy backend owns the
        // transform exactly as before; a ship-physics backend would instead hand
        // the state to the ship and own displacement itself.
        flightBackend.applyFlightState(newQuat, result, enginePow);

        // Landing: ground contact + slow vertical motion -> engines off
        // (touchdown auto-shutdown). The detector arms only once the craft
        // has actually left the ground, so the engine-start hover can never
        // read as a touchdown — no timed grace window needed.
        if (!freeFlightHasLeftGround && !this.onGround) {
            freeFlightHasLeftGround = true;
            ffTrace("liftoff: craft left the ground, landing detector armed");
        }
        if (freeFlightHasLeftGround
                && FreeFlightPhysics.shouldLand(this.onGround, this.motionY)
                && !freeFlightLandedLatched) {
            // Harness-only diagnostics: explain WHY FF just switched off, with the
            // concrete climb-authority numbers so manual testers can tell an
            // out-of-thrust rocket apart from an over-eager auto-land. Gated on the
            // same flag as the /artest probes (-Dadvancedrocketry.tests=true), so
            // players never see it.
            if (TestProbeCommandRegistration.isTestMode()) {
                logFreeFlightLandReason(result.thrustApplied);
            }
            this.motionX = 0;
            this.motionY = 0;
            this.motionZ = 0;
            this.setInFlight(false); // touchdown = engines off
            this.setInOrbit(false);
            this.currentFreeFlightInput = FreeFlightInput.zero();
            this.freeFlightLandedLatched = true;
            this.ffLiftoffTargetY = Double.NaN;
            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLandedEvent(this));
            PacketHandler.sendToPlayersTrackingEntity(
                    new PacketEntity(this, (byte) PacketType.ROCKETLANDEVENT.ordinal()), this);
        }

        this.velocityChanged = true;
    }

    /**
     * Harness-only diagnostic for "Free Flight switched off". Recomputes the
     * climb-authority numbers the way {@link FreeFlightPhysics} does and logs
     * whether the rocket physically could not climb (thrust too low for the
     * local gravity) versus simply having re-landed without sustained vertical
     * input. Gated by the caller on {@link TestProbeCommandRegistration#isTestMode()}.
     *
     * @param thrustAppliedLastTick whether the final physics step actually applied thrust
     */
    private void logFreeFlightLandReason(boolean thrustAppliedLastTick) {
        int thrust = stats.getThrust();
        float weight = stats.getWeight();
        float gravMult = DimensionManager.getInstance()
                .getDimensionProperties(this.world.provider.getDimension())
                .getGravitationalMultiplier();
        double gravity = 0.04 * gravMult;

        // Climb authority is exactly the classic ascent acceleration: > 0 means
        // the rocket can gain altitude at full vertical thrust (i.e. TWR > 1).
        double netAccel = stats.getAcceleration(gravMult);
        double twr = weight > 0 ? (double) thrust / weight : Double.POSITIVE_INFINITY;
        boolean canClimb = netAccel > 0;
        String verdict = canClimb
                ? "thrust OK (getAcceleration > 0, TWR > 1) — re-landed without sustained vertical (Z) input, or auto-land fired near the pad"
                : "INSUFFICIENT THRUST: getAcceleration <= 0 (TWR <= effective gravity) — full vertical thrust cannot climb here";

        AdvancedRocketry.logger.info(String.format(
                "[FF-DEBUG] FreeFlight OFF (landed) after %d ticks: onGround=%b motionY=%.4f thrustAppliedLastTick=%b%n"
              + "           climb: thrust=%d weight=%.2f TWR=%.3f getAcceleration=%.5f gravity=%.5f canClimb=%b%n"
              + "           verdict: %s%n"
              + "           lastInput=%s",
                freeFlightTicksSinceStart, this.onGround, this.motionY, thrustAppliedLastTick,
                thrust, weight, twr, netAccel, gravity, canClimb,
                verdict, String.valueOf(currentFreeFlightInput)));
    }

    /**
     * If the rocket is in flight, ie the rocket has taken off and has not touched the ground
     *
     * @return true if in flight
     */
    public boolean isInOrbit() {
        if (!world.isRemote) {
            return isInOrbit;
        }
        return this.dataManager.get(INORBIT);
    }

    /**
     * Sets the status of orbit of the rocket and updates the datawatcher
     *
     * @param inOrbit status of orbit
     */
    public void setInOrbit(boolean inOrbit) {
        this.isInOrbit = inOrbit;
        this.dataManager.set(INORBIT, inOrbit);
        this.dataManager.setDirty(INORBIT);
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(INFLIGHT, false);
        this.dataManager.register(fuelLevelMonopropellant, 0);
        this.dataManager.register(fuelLevelBipropellant, 0);
        this.dataManager.register(fuelLevelOxidizer, 0);
        this.dataManager.register(fuelLevelNuclearWorkingFluid, 0);
        this.dataManager.register(INORBIT, false);
        this.dataManager.register(RCS_MODE, false);
        this.dataManager.register(LAUNCH_COUNTER, -1);
        this.dataManager.register(INSPACEFLIGHT, false);
        this.dataManager.register(FA_SP_FWD,   0f);
        this.dataManager.register(FA_SP_RIGHT, 0f);
        this.dataManager.register(FA_SP_UP,    0f);
        this.dataManager.register(FF_QW, 1f);
        this.dataManager.register(FF_QX, 0f);
        this.dataManager.register(FF_QY, 0f);
        this.dataManager.register(FF_QZ, 0f);
        this.dataManager.register(FF_ENGINE_POWER, 0f);
    }

    // ---- Flight Assist setpoint accessors -------------------

    public float getFaSetpointForward() { return this.dataManager.get(FA_SP_FWD); }
    public float getFaSetpointRight()   { return this.dataManager.get(FA_SP_RIGHT); }
    public float getFaSetpointUp()      { return this.dataManager.get(FA_SP_UP); }

    private void setFaSetpoint(double fwd, double right, double up) {
        this.dataManager.set(FA_SP_FWD,   (float) fwd);
        this.dataManager.set(FA_SP_RIGHT, (float) right);
        this.dataManager.set(FA_SP_UP,    (float) up);
    }

    //Set the size and position of the rocket from storage
    public void initFromBounds() {
        if (storage != null) {
            this.setSize(Math.max(storage.getSizeX(), storage.getSizeZ()), storage.getSizeY());
            this.setPosition(this.posX, this.posY, this.posZ);
        }
    }

    protected boolean interact(@Nonnull EntityPlayer player) {
        //Actual interact code needs to be moved to a packet receive on the server

        ItemStack heldItem = player.getHeldItem(EnumHand.MAIN_HAND);

        //Handle linkers and right-click with fuel
        boolean isHoldingFluidItemOrLinker = false;
        if (!heldItem.isEmpty()) {
            FluidStack fluidStack;

            if (heldItem.getItem() instanceof ItemLinker) {
                isHoldingFluidItemOrLinker = true;

                if (ItemLinker.isSet(heldItem)) {
                    TileEntity tile = this.world.getTileEntity(ItemLinker.getMasterCoords(heldItem));

                    if (tile instanceof IInfrastructure) {
                        IInfrastructure infrastructure = (IInfrastructure) tile;

                        if (this.getDistance(ItemLinker.getMasterCoords(heldItem).getX(), this.posY, ItemLinker.getMasterCoords(heldItem).getZ()) < infrastructure.getMaxLinkDistance() + Math.max(storage.getSizeX(), storage.getSizeZ())) {
                            if (!connectedInfrastructure.contains(tile)) {
                                linkInfrastructure(infrastructure);

                                // TODO Translate

                                if (!world.isRemote) {
                                    player.sendMessage(new TextComponentString("Linked successfully"));
                                }
                                ItemLinker.resetPosition(heldItem);

                                return true;
                            } else if (!world.isRemote)
                                player.sendMessage(new TextComponentString("Already linked!"));
                        } else if (!world.isRemote)
                            player.sendMessage(new TextComponentString("The object you are trying to link is too far away"));
                    } else if (!world.isRemote)
                        player.sendMessage(new TextComponentString("This cannot be linked to a rocket!"));
                } else if (!world.isRemote)
                    player.sendMessage(new TextComponentString("Nothing to be linked"));

                return false;
            } //End of if(heldItem.getItem() instanceof ItemLinker)
            else if ((FluidUtils.containsFluid(heldItem) && FluidUtils.getFluidForItem(heldItem) != null) && ARConfiguration.getCurrentConfig().canBeFueledByHand) {
                fluidStack = FluidUtils.getFluidForItem(heldItem);

                if ((canRocketFitFluid(fluidStack))) {
                    isHoldingFluidItemOrLinker = true;

                    FuelType type = getRocketFuelType();
                    if (type == null)
                        return false;

                    if (getRocketFuelType() == FuelType.LIQUID_BIPROPELLANT && FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, fluidStack.getFluid()))
                        type = FuelType.LIQUID_OXIDIZER;

                    stats.setFuelRate(type, (int) (stats.getBaseFuelRate(type) * FuelRegistry.instance.getMultiplier(type, fluidStack.getFluid())));
                    FluidTank rocketFakeTank = new FluidTank(getFuelCapacity(type) - getFuelAmount(type));
                    FluidUtil.interactWithFluidHandler(player, EnumHand.MAIN_HAND, rocketFakeTank);
                    this.addFuelAmount(type, rocketFakeTank.getFluidAmount());
                }
            }
        }

        //If player is holding shift open GUI
        if (player.isSneaking() || (!stats.hasSeat() && !isHoldingFluidItemOrLinker)) {
            openGui(player);
        } else if (stats.hasSeat()) { //If pilot seat is open mount entity there
            if (this.getPassengers().size() < stats.getNumPassengerSeats()) {
                if (!world.isRemote)
                    player.startRiding(this);
            }
        }
        return true;
    }

    protected boolean canFitPassenger(Entity passenger) {
        return this.getPassengers().size() < stats.getNumPassengerSeats();
    }


    // Check if we have enough fuel to reach orbit from our current position
    private boolean hasMissionFuelFor(int destDimId) {
        if (!ARConfiguration.getCurrentConfig().rocketRequireFuel) return true;

        final FuelRegistry.FuelType main = getRocketFuelType();
        if (main == null) return false; // no usable tanks

        if (isInOrbit()) return true;   // already at orbit

        if (stats.getThrust() <= stats.getWeight()) return false;

        final DimensionProperties src = DimensionManager.getInstance()
                .getDimensionProperties(this.world.provider.getDimension());
        final float gSrc = Math.max(0.01f, src.getGravitationalMultiplier()); 
        final double a = Math.max(0.0001d, stats.getAcceleration(gSrc));    
        final double h = Math.max(0.0, stats.orbitHeight - this.posY);

        long nTicks = (long)Math.ceil(Math.sqrt(2.0 * h / a));
        nTicks += 2L; // small safety buffer
        if (nTicks <= 0) nTicks = 1;

        int mainRate = Math.max(1, getFuelConsumptionRate(main));
        long mainNeeded = nTicks * (long)mainRate;
        long mainHave   = getFuelAmount(main);
        if (mainHave < mainNeeded) return false;

        if (main == FuelRegistry.FuelType.LIQUID_BIPROPELLANT) {
            int oxRate = Math.max(1, getFuelConsumptionRate(FuelRegistry.FuelType.LIQUID_OXIDIZER));
            long oxNeeded = nTicks * (long)oxRate;
            long oxHave   = getFuelAmount(FuelRegistry.FuelType.LIQUID_OXIDIZER);
            if (oxHave < oxNeeded) return false;
        }

        // Descent currently does not burn fuel in your code path.
        return true;
    }


    /**
     * @param fluidStack the stack to check whether the rocket can fit
     * @return boolean on whether said fluid stack can fit into the rocket's internal fuel point storage
     */
    public boolean canRocketFitFluid(FluidStack fluidStack) {
        if (FuelRegistry.instance.isFuel(FuelType.LIQUID_MONOPROPELLANT, fluidStack.getFluid())) {
            boolean isCorrectFluid = stats.getFuelFluid().equals("null") || fluidStack.getFluid() == FluidRegistry.getFluid(stats.getFuelFluid());
            if (stats.getFuelFluid().equals("null") && isCorrectFluid)
                stats.setFuelFluid(fluidStack.getFluid().getName());
            return isCorrectFluid;
        } else if (FuelRegistry.instance.isFuel(FuelType.LIQUID_BIPROPELLANT, fluidStack.getFluid())) {
            boolean isCorrectFluid = stats.getFuelFluid().equals("null") || fluidStack.getFluid() == FluidRegistry.getFluid(stats.getFuelFluid());
            if (stats.getFuelFluid().equals("null") && isCorrectFluid)
                stats.setFuelFluid(fluidStack.getFluid().getName());
            return isCorrectFluid;
        } else if (FuelRegistry.instance.isFuel(FuelType.LIQUID_OXIDIZER, fluidStack.getFluid())) {
            boolean isCorrectFluid = stats.getOxidizerFluid().equals("null") || fluidStack.getFluid() == FluidRegistry.getFluid(stats.getOxidizerFluid());
            if (stats.getOxidizerFluid().equals("null") && isCorrectFluid)
                stats.setOxidizerFluid(fluidStack.getFluid().getName());
            return isCorrectFluid;
        } else if (FuelRegistry.instance.isFuel(FuelType.NUCLEAR_WORKING_FLUID, fluidStack.getFluid())) {
            boolean isCorrectFluid = stats.getWorkingFluid().equals("null") || fluidStack.getFluid() == FluidRegistry.getFluid(stats.getWorkingFluid());
            if (stats.getWorkingFluid().equals("null") && isCorrectFluid)
                stats.setWorkingFluid(fluidStack.getFluid().getName());
            return isCorrectFluid;
        }
        return false;
    }

    public void openGui(EntityPlayer player) {
        player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULAR.ordinal(), player.world, this.getEntityId(), -1, 0);

        //Only handle the bypass on the server
        if (!world.isRemote)
            RocketInventoryHelper.addPlayerToInventoryBypass(player);
    }

    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) {
            //Due to forge's rigid handling of entities (NetHanlderPlayServer:866) needs to be handled differently for large rockets
            PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.SENDINTERACT.ordinal()));
            return interact(player);
        }
        return true;

    }


    /**
     * @return boolean on whether the rocket is burning any type of fuel at the current moment, including all fuel types
     */
    public boolean isBurningFuel() {
        FuelType fuelType = getRocketFuelType();
        return (((fuelType == FuelType.LIQUID_BIPROPELLANT) ? getFuelAmount(fuelType) > 0 && getFuelAmount(FuelType.LIQUID_OXIDIZER) > 0 : getFuelAmount(fuelType) > 0) || !ARConfiguration.getCurrentConfig().rocketRequireFuel) && ((!this.getPassengers().isEmpty() && getPassengerMovingForward() > 0) || !isInOrbit());
    }

    public float getPassengerMovingForward() {

        for (Entity entity : this.getPassengers()) {
            if (entity instanceof EntityPlayer) {
                return ((EntityPlayer) entity).moveForward;
            }
        }
        return 0f;
    }

    private boolean hasHumanPassenger() {

        for (Entity entity : this.getPassengers()) {
            if (entity instanceof EntityPlayer) {
                return true;
            }
        }
        return false;
    }

    public boolean isDescentPhase() {
        int ch = world.getHeight((int) posX, (int) posZ);
        return ARConfiguration.getCurrentConfig().automaticRetroRockets &&
                isInOrbit() &&
                (
                        (this.posY < ch + 300 && (this.motionY < -0.5f || world.isRemote)) ||
                                (this.posY < ch + 150 && (this.motionY < -0.4f || world.isRemote)) ||
                                (this.posY < ch + 100 && (this.motionY < -0.3f || world.isRemote)) ||
                                (this.posY < ch + 70 && (this.motionY < -0.2f || world.isRemote)) ||
                                (this.posY < ch + 50 && (this.motionY < -0.14f || world.isRemote)) ||
                                (this.posY < ch + 20 && (this.motionY < -0.5f || world.isRemote))||
                                (this.posY < ch + 10 && (this.motionY < -0.05f || world.isRemote))
                );
    }

    public boolean isStartupPhase() {
        return this.dataManager.get(LAUNCH_COUNTER) < ENGINE_IGNITION_CNT && this.dataManager.get(LAUNCH_COUNTER) != -1;
    }

    public float getEnginePower() {
        // Free Flight: thrust fires in any direction, so drive the sound off the
        // replicated per-tick thrust magnitude (set in tickFreeFlight) instead of
        // the classic motionY>0 gate — volume is proportional to |thrust vector|,
        // and it sounds whenever the engines actually work (incl. a hover).
        if (isFreeFlight())
            return isInFlight() ? this.dataManager.get(FF_ENGINE_POWER) : 0f;

        float mult = 1;
        int countdown = this.dataManager.get(LAUNCH_COUNTER);
        if (countdown > -1 && isStartupPhase()) {
            mult = (ENGINE_IGNITION_CNT - countdown) / (float) ENGINE_IGNITION_CNT;
        }

        if (this.areEnginesRunning())
            return mult * Math.max(DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).getAtmosphereDensityAtHeight(this.posY), 0.05f);
        else
            return 0;
    }

    public boolean areEnginesRunning() {
        return this.motionY > 0 || isDescentPhase() || (getPassengerMovingForward() > 0) || isStartupPhase();
    }



    @Override
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean teleport) {

        // Free Flight is fast/arcade. The classic poscorrection smoothing (closed
        // at only 1/ct=50 per tick) lags ~150 blocks behind; a hard snap on every
        // tracker update (every updateFrequency=3 ticks) is the opposite problem —
        // the rocket freezes between updates then jumps, which reads as violent
        // jitter. Instead we just record the position ERROR here and let the
        // client dead-reckon by velocity each tick, absorbing the (small) error
        // over a few ticks — see the FF branch in onUpdate().
        if (isFreeFlight() && isInFlight()) {
            // FF ATTITUDE replicates via the FF_Q* DataParameters (read directly in
            // onUpdate), NOT the byte-quantised yaw/pitch tracker — so the yaw/pitch
            // this vanilla path carries is ignored. Only POSITION is recorded, as
            // the authoritative target: the client predicts by velocity and corrects
            // the residual toward it each tick (see the FF branch in onUpdate()),
            // which removes the fast craft's sample-rate lag without a hard snap.
            this.ffServerPos = new Vec3d(x, y, z);
            return;
        }

        if(last_was_in_orbit != this.dataManager.get(INORBIT)){
            last_was_in_orbit = this.dataManager.get(INORBIT);
            reset_motion= true;
            reset_position = true;
        }

        if (reset_position){
            this.setPosition(x,y,z);
            reset_position = false;
        }else {
            Vec3d new_pos = new Vec3d(x, y, z);
            poscorrection = new_pos.subtract(posX, posY, posZ);
        }


        //Vec3d new_pos = new Vec3d(x, y, z);
        //poscorrection = new_pos.subtract(posX, posY, posZ);
    }



    private void runEngines() {
        //Spawn in the particle effects for the engines
        int max_engine_for_smoke = 16;
        int engineNum = stats.getEngineLocations().size();
        //System.out.println("engine locs:"+engineNum);


        if (world.isRemote && areEnginesRunning()) {
            for (Vector3F<Float> vec : stats.getEngineLocations()) {

                AtmosphereHandler handler = AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
                IAtmosphere atmosphere = null;

                if (handler != null)
                    atmosphere = handler.getAtmosphereType(this);


                boolean can_smoke = true;
                if (engineNum > max_engine_for_smoke) {
                    can_smoke = rand.nextInt(engineNum) <= max_engine_for_smoke;
                }

                if (Minecraft.getMinecraft().gameSettings.particleSetting < 2 && can_smoke && Minecraft.getMinecraft().gameSettings.particleSetting < 1 && (handler == null || (atmosphere != null && atmosphere.allowsCombustion()))) {
                    for (int i = 0; i < 3; i++) {


                        double yo = 1 + this.rand.nextFloat();
                        float xzv = 16f;
                        if (motionY > 0)
                            xzv = 32;

                        double motionz = (this.rand.nextFloat() - 0.5f);
                        double motionx = (this.rand.nextFloat() - 0.5f);
                        double speed = (this.rand.nextFloat()) / xzv;
                        double speedxz = Math.sqrt(motionx * motionx + motionz * motionz);
                        motionx *= speed / speedxz;
                        motionz *= speed / speedxz;


                        AdvancedRocketry.proxy.spawnDynamicRocketSmoke(world, this.posX + vec.x, this.posY + vec.y - yo, this.posZ + vec.z, motionx, -0.75 - this.rand.nextFloat() / 6.0, motionz, engineNum);
                    }
                }
                for (float i = 0; i < 15; i++) {
                        AdvancedRocketry.proxy.spawnDynamicRocketFlame(world, this.posX + vec.x, this.posY + vec.y - 0.9 - (i*0.1f), this.posZ + vec.z, (this.rand.nextFloat() - 0.5f) / 6f, -0.75, (this.rand.nextFloat() - 0.5f) / 6f, engineNum);

                }
            }
        }
    }

    private BlockPos getTopBlock(BlockPos pos) {
        //Yeah... because minecraft's World.getTopSolidOrLiquidBlock does not actually check for liquids like lava
        Chunk chunk = world.getChunkFromBlockCoords(pos);
        BlockPos blockpos;
        BlockPos blockpos1;

        for (blockpos = new BlockPos(pos.getX(), chunk.getTopFilledSegment() + 16, pos.getZ()); blockpos.getY() >= 0; blockpos = blockpos1) {
            blockpos1 = blockpos.down();

            if (!world.isAirBlock(blockpos)) {
                break;
            }
        }
        return blockpos;
    }

    private Vec3d calculatePullFromPlanets() {
        double x = 0;
        double y = 0;
        double z = 0;
        double gravityMultiplier = 0.01;
        if (this.spacePosition.world != null) {
            //Sun
            // This is totally cheesed because none of the input is in real values anyway
            SpacePosition planetSpacePosition = new SpacePosition();
            double acceleration = 100 * gravityMultiplier;
            double distanceSq = planetSpacePosition.distanceToSpacePosition2(this.spacePosition);

            double shipAcceleration = acceleration / distanceSq;

            Vec3d vector = this.spacePosition.getNormalVectorTo(planetSpacePosition);

            if (distanceSq > 0) {
                x += shipAcceleration * vector.x;
                y += shipAcceleration * vector.y;
                z += shipAcceleration * vector.z;
            }
        } else if (this.spacePosition.star != null) {
            for (IDimensionProperties planet : this.spacePosition.star.getPlanets()) {
                // This is totally cheesed because none of the input is in real values anyway
                SpacePosition planetSpacePosition = planet.getSpacePosition();
                double acceleration = planet.getGravitationalMultiplier() * 9.81f * gravityMultiplier;
                double distanceSq = planet.getSpacePosition().distanceToSpacePosition2(this.spacePosition);

                double shipAcceleration = acceleration / distanceSq;

                Vec3d vector = this.spacePosition.getNormalVectorTo(planetSpacePosition);

                x += shipAcceleration * vector.x;
                y += shipAcceleration * vector.y;
                z += shipAcceleration * vector.z;

            }

            //Sun
            // This is totally cheesed because none of the input is in real values anyway
            SpacePosition planetSpacePosition = new SpacePosition();
            double acceleration = 100 * gravityMultiplier;
            double distanceSq = planetSpacePosition.distanceToSpacePosition2(this.spacePosition);

            double shipAcceleration = acceleration / distanceSq;

            Vec3d vector = this.spacePosition.getNormalVectorTo(planetSpacePosition);

            if (distanceSq > 0) {
                x += shipAcceleration * vector.x;
                y += shipAcceleration * vector.y;
                z += shipAcceleration * vector.z;
            }
        }

        return new Vec3d(x, y, z);
    }

    @Override
    public void setFire(int seconds) {
    }

    private void syncRocket() {
        NBTTagCompound nbtdata = new NBTTagCompound();

        this.writeToNBT(nbtdata);
        PacketHandler.sendToNearby(new PacketEntity(this, (byte) 0, nbtdata), world.provider.getDimension(), new BlockPos(this), 64);
    }


    //stfu

    @Override
    public void setVelocity(double x, double y, double z) {

        // Free Flight: take the server velocity verbatim (see
        // setPositionAndRotationDirect) — no slow velcorrection blending.
        if (isFreeFlight() && isInFlight()) {
            this.motionX = x;
            this.motionY = y;
            this.motionZ = z;
            this.velcorrection = new Vec3d(0, 0, 0);
            return;
        }

        if (reset_motion){
            velcorrection = new Vec3d(0,0,0);
            this.motionX = x;
            this.motionY = y;
            this.motionZ = z;
            reset_motion = false;
        }else {
            Vec3d new_vel = new Vec3d(x, y, z);
            velcorrection = new_vel.subtract(motionX, motionY, motionZ);
        }


        //Vec3d new_vel = new Vec3d(x, y, z);
        //velcorrection = new_vel.subtract(motionX, motionY, motionZ);
    }



    @Override
    public void onUpdate() {
        super.onUpdate();
        long deltaTime = world.getTotalWorldTime() - lastWorldTickTicked;
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
        // Skip the classic poscorrection/velcorrection smoothing while in Free
        // Flight — FF snaps to the server transform in setPositionAndRotationDirect
        // / setVelocity, so applying stale corrections here would re-introduce the
        // lag/jerk this fix removes.
        if (world.isRemote && !(isFreeFlight() && isInFlight())) {

            double ct = 50;

            if (!this.dataManager.get(INORBIT) && poscorrection.y < -0.01) {
                // if this code runs, rocket is out of fuel and will have a hard crash. no smooth syncing!
                ct = 1;
            }


                double cx = poscorrection.x / ct;
                double cy = poscorrection.y / ct;
                double cz = poscorrection.z / ct;
                poscorrection = poscorrection.subtract(cx, cy, cz);
                this.setPosition(posX + cx, posY + cy, posZ + cz);


                double ct2 = 10;
                double vx = velcorrection.x / ct2;
                double vy = velcorrection.y / ct2;
                double vz = velcorrection.z / ct2;
                velcorrection = velcorrection.subtract(vx, vy, vz);

                motionX += vx;
                motionY += vy;
                motionZ += vz;

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
            else {
                int rocketSizeX = storage.getSizeX() / 2 + 1;
                int rocketSizeZ = storage.getSizeZ() / 2 + 1;
                final int bufferSize = 3;

                // Create float if needed

                //First check to see if anything at all will catch the rocket
                boolean safeLanding = false;
                for (int x = ((int) posX - rocketSizeX); x < (posX + rocketSizeX) && !safeLanding; x++) {
                    for (int z = ((int) posZ - rocketSizeZ); z < (posZ + rocketSizeZ) && !safeLanding; z++) {
                        BlockPos pos = new BlockPos(x, posY, z);
                        pos = getTopBlock(pos);

                        //water is considered unsafe too from now on
                        //safeLanding = !world.getBlockState(pos).getMaterial().isLiquid() || world.getBlockState(pos).getBlock() == Blocks.WATER || world.getBlockState(pos).getBlock() == AdvancedRocketryBlocks.blockRocketFire;
                        safeLanding = !world.getBlockState(pos).getMaterial().isLiquid() || world.getBlockState(pos).getBlock() == AdvancedRocketryBlocks.blockRocketFire;
                    }
                }

                // If nothing will catch the rocket, then create a float
                // If anyone asks, the dev thinks underwater rocket launch platforms are cool and players can swim anyway
                if (!safeLanding) {
                    for (int x = ((int) posX - rocketSizeX - bufferSize); x < (posX + rocketSizeX + bufferSize); x++) {
                        for (int z = ((int) posZ - rocketSizeZ - bufferSize); z < (posZ + rocketSizeZ + bufferSize); z++) {
                            BlockPos pos = new BlockPos(x, posY, z);
                            pos = getTopBlock(pos);
                            world.setBlockState(pos, AdvancedRocketryBlocks.blockLandingFloat.getDefaultState());
                        }
                    }
                }
            }
        }

        if (this.ticksExisted > DESCENT_TIMER && isInOrbit() && !isInFlight())
            setInFlight(true);

        //Hackish crap to make clients mount entities immediately after server transfer and fire events
        //Known race condition... screw me...
        if (!world.isRemote && (this.isInFlight() || this.isInOrbit()) && this.ticksExisted == 20) {
            //Deorbiting
            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketDeOrbitingEvent(this));
            PacketHandler.sendToNearby(new PacketEntity(this, (byte) PacketType.ROCKETLANDEVENT.ordinal()), world.provider.getDimension(), (int) posX, (int) posY, (int) posZ, 64);
            for (Entity riddenByEntity : getPassengers()) {
                if (riddenByEntity instanceof EntityPlayer) {
                    EntityPlayer player = (EntityPlayer) riddenByEntity;
                    PacketHandler.sendToPlayer(new PacketEntity(this, (byte) PacketType.FORCEMOUNT.ordinal()), player);
                }
            }
        }
        //Update RCS mode
        if (getRCS() && rcs_mode_counter < 100)
            rcs_mode_counter++;
        else if (!getRCS() && rcs_mode_counter > 0) {
            rcs_mode_counter--;
            this.rotationYaw = 0;
        }

        // ---- Free Flight Mode branch -------------------------------------------
        // Server runs the arcade physics; client smooths the render. Either way
        // we SKIP every classic-launch branch below.
        if (isFreeFlight() && isInFlight()) {
            if (!world.isRemote) {
                tickFreeFlight();
            } else {
                // Predict-then-correct smoothing. The craft is fast and the tracker
                // samples pose ~once per tick; freezing between samples then snapping
                // reads as violent jitter, so each CLIENT tick we PREDICT from the
                // synced velocity / local steering rates (dead-reckon) and pull the
                // RESIDUAL error toward the latest server target over
                // FF_CLIENT_CORRECT_TICKS. Correcting the residual (target − predicted)
                // rather than the raw gap (target − pos) is the whole point: the raw
                // gap already contains this tick's motion, so adding motion AND the gap
                // double-counts it and leaves the client a full tick ahead of the
                // server — a constant lead that shifts whenever velocity changes, which
                // is the FA-off sawtooth. Attitude uses the same predict-then-correct
                // shape below, on the quaternion (slerp) instead of Euler.
                FreeFlightInput in = currentFreeFlightInput == null
                        ? FreeFlightInput.zero() : currentFreeFlightInput;

                // Position: dead-reckon by synced velocity, then correct the residual
                // to the server target. e -> 0 in steady state (no lead, no lag).
                double px = this.posX + this.motionX;
                double py = this.posY + this.motionY;
                double pz = this.posZ + this.motionZ;
                if (this.ffServerPos != null) {
                    px += (this.ffServerPos.x - px) / FF_CLIENT_CORRECT_TICKS;
                    py += (this.ffServerPos.y - py) / FF_CLIENT_CORRECT_TICKS;
                    pz += (this.ffServerPos.z - pz) / FF_CLIENT_CORRECT_TICKS;
                }
                this.setPosition(px, py, pz);

                // Attitude: predict from the local input rates, then slerp a
                // fraction toward the replicated server quaternion (FF_Q*). For the
                // pilot the prediction ≈ the server, so the slerp barely moves ->
                // smooth; an observer has zero input, so the correction carries the
                // server attitude. Snapshot prev BEFORE the advance so the render and
                // the hard-locked FF camera slerp one tick's rotation per frame — the
                // source of the smooth 60 fps sweep despite the 20 Hz physics.
                double pr = in.pitchInput * FreeFlightPhysics.MAX_PITCH_RATE;
                double yr = in.yawInput   * FreeFlightPhysics.MAX_YAW_RATE;
                double rr = in.rollInput  * FreeFlightPhysics.MAX_ROLL_RATE;
                FreeFlightPhysics.Quat predicted =
                        FreeFlightPhysics.integrateBodyRates(this.ffQuat, pr, yr, rr);
                FreeFlightPhysics.Quat target = new FreeFlightPhysics.Quat(
                        this.dataManager.get(FF_QW), this.dataManager.get(FF_QX),
                        this.dataManager.get(FF_QY), this.dataManager.get(FF_QZ)).normalized();
                this.prevFfQuat = this.ffQuat;
                this.ffQuat = FreeFlightPhysics.slerp(predicted, target, 1.0 / FF_CLIENT_CORRECT_TICKS);

                // Mirror the Euler view for legacy consumers (seat, HUD bars, vanilla
                // rotationYaw/Pitch readers); render/camera read the quaternion.
                float[] e = FreeFlightPhysics.eulerFromQuat(this.ffQuat);
                this.prevRotationYaw    = this.rotationYaw;
                this.prevRotationPitch  = this.rotationPitch;
                this.prevFreeFlightRoll = this.freeFlightRoll;
                this.rotationYaw     = e[0];
                this.rotationPitch   = e[1];
                this.freeFlightPitch = e[1];
                this.freeFlightRoll  = e[2];
            }
            return;
        }
        // ------------------------------------------------------------------------

        //Count down
        int launchCount = this.dataManager.get(LAUNCH_COUNTER);
        if (launchCount >= 0) {
            if (launchCount == 0)
                launch();
            launchCount--;
            this.dataManager.set(LAUNCH_COUNTER, launchCount);
            //Just before launch, damage the ground. We'll do it again on the tick that we launch
            if (ARConfiguration.getCurrentConfig().launchingDestroysBlocks && launchCount <= 100 && launchCount != 0 && this.getFuelCapacity(getRocketFuelType()) > 0)
                damageGroundBelowRocket(world, (int) this.posX, (int) this.posY, (int) this.posZ, (int) Math.pow(stats.getThrust(), 0.4));
        }

        if(!world.isRemote){
            for(Entity entity : this.getPassengers()) {
                entity.fallDistance = 0;
            }
            this.fallDistance = 0;

            // Auto-release destination preload after timeout
            if (destPreloadTicket != null && world.getTotalWorldTime() >= destPreloadExpire) {
                releaseDestinationPreload();
            }
        }

        // When flying around in space
        if (getInSpaceFlight()) {
            double distanceFromPlanetToLeaveOrbitMult = 16.0;

            this.rotationYaw += (turningRight ? 5 : 0) - (turningLeft ? 5 : 0);
            double acc = 10 * this.getPassengerMovingForward() * 0.2;
            //RCS mode, steer like boat
            float yawAngle = (float) (this.rotationYaw * Math.PI / 180f);
            Vec3d planetPull = Vec3d.ZERO; //calculatePullFromPlanets();
            this.motionX += acc * MathHelper.sin(-yawAngle) + planetPull.x;
            this.motionY += (turningUp ? 0.02 : 0) - (turningDownforWhat ? 0.02 : 0) + planetPull.y;
            this.motionZ += acc * MathHelper.cos(-yawAngle) + planetPull.z;

            if (acc == 0) {
                this.motionX *= 0.98;
                this.motionY *= 0.98;
                this.motionZ *= 0.98;
            }


            spacePosition.x += this.motionX;
            spacePosition.y += this.motionY;
            spacePosition.z += this.motionZ;

            //Check if close to a world
            if (this.spacePosition.world == null && this.spacePosition.star != null) {
                for (IDimensionProperties properties : this.spacePosition.star.getPlanets()) {
                    SpacePosition worldSpacePosition = properties.getSpacePosition();
                    double distanceSq = this.spacePosition.distanceToSpacePosition2(worldSpacePosition);

                    if (distanceSq < properties.getRenderSizeSolarView() * properties.getRenderSizeSolarView() * 8) {
                        this.spacePosition.world = (DimensionProperties) properties;

                        //Radius to put the player
                        double radius = -properties.getRenderSizePlanetView() * 16;
                        //Assume planet centered at 0
                        SpacePosition planetPosition = new SpacePosition();
                        double theta = Math.atan2(this.motionZ, this.motionX);

                        this.spacePosition.x = planetPosition.x + Math.cos(theta) * radius;
                        this.spacePosition.y = planetPosition.y;
                        this.spacePosition.z = planetPosition.z + Math.sin(theta) * radius;
                        PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.SENDSPACEPOS.ordinal()));
                        break;
                    }
                }
            } else if (this.spacePosition.world != null) {
                double distanceSq = this.spacePosition.distanceToSpacePosition2(new SpacePosition());
                //Land, only handle on server
                if (!world.isRemote) {
                    this.storage.damageParts();
                    syncRocket();

                    if (distanceSq < 0.5f * spacePosition.world.getRenderSizePlanetView() * spacePosition.world.getRenderSizePlanetView()) {
                        this.destinationDimId = spacePosition.world.getId();
                        this.setRCS(false);
                        this.motionX = 0;
                        this.motionZ = 0;
                        this.motionY = 1; // +1 because it gets inverted later
                        this.rotationYaw = 0;
                        rcs_mode = false;
                        reachSpaceManned();
                        this.setInSpaceFlight(false);
                    } else {
                        // Land on moons?
                        for (int subId : spacePosition.world.getChildPlanets()) {
                            DimensionProperties subPlanetProperties = DimensionManager.getInstance().getDimensionProperties(subId);

                            distanceSq = this.spacePosition.distanceToSpacePosition2(subPlanetProperties.getSpacePosition());
                            if (distanceSq < 0.5f * subPlanetProperties.getRenderSizePlanetView() * subPlanetProperties.getRenderSizePlanetView()) {
                                this.destinationDimId = subPlanetProperties.getId();
                                this.setRCS(false);
                                rcs_mode = false;
                                this.rotationYaw = 0;
                                reachSpaceManned();
                                this.setInSpaceFlight(false);
                            }

                            //What about space stations?
                            List<ISpaceObject> stations = SpaceObjectManager.getSpaceManager().getSpaceStationsOrbitingPlanet(subId);

                            if (stations != null) {
                                for (ISpaceObject station : stations) {
                                    distanceSq = this.spacePosition.distanceToSpacePosition2(((SpaceStationObject) station).getSpacePosition());
                                    if (distanceSq < 100 * 100) {
                                        this.destinationDimId = ARConfiguration.getCurrentConfig().spaceDimId;
                                        this.storage.getGuidanceComputer().overrideLandingStation(station);
                                        this.setRCS(false);
                                        this.rotationYaw = 0;
                                        rcs_mode = false;
                                        reachSpaceManned();
                                        this.setInSpaceFlight(false);
                                    }
                                }
                            }
                        }
                    }

                    // Station orbiting main world?
                    List<ISpaceObject> stations = SpaceObjectManager.getSpaceManager().getSpaceStationsOrbitingPlanet(this.spacePosition.world.getId());

                    if (stations != null) {
                        for (ISpaceObject station : stations) {
                            distanceSq = this.spacePosition.distanceToSpacePosition2(((SpaceStationObject) station).getSpacePosition());
                            if (distanceSq < 100 * 100) {
                                this.destinationDimId = ARConfiguration.getCurrentConfig().spaceDimId;
                                this.storage.getGuidanceComputer().overrideLandingStation(station);
                                this.setRCS(false);
                                rcs_mode = false;
                                reachSpaceManned();
                                this.setInSpaceFlight(false);
                            }
                        }
                    }
                }
                // transition to solar navigation, this comes after, prevent NPE, client only
                else if (distanceSq > this.spacePosition.world.getRenderSizePlanetView() * this.spacePosition.world.getRenderSizePlanetView() * distanceFromPlanetToLeaveOrbitMult * distanceFromPlanetToLeaveOrbitMult) {
                    //Radius to put the player
                    double radius = this.spacePosition.world.getRenderSizeSolarView() * 10;

                    SpacePosition planetPosition = this.spacePosition.world.getSpacePosition();
                    this.spacePosition.world = null;

                    double theta = Math.atan2(this.motionZ, this.motionX);

                    this.spacePosition.x = planetPosition.x + Math.cos(theta) * radius;
                    this.spacePosition.y = planetPosition.y;
                    this.spacePosition.z = planetPosition.z + Math.sin(theta) * radius;

                    this.motionX *= 0.0;
                    this.motionY *= 0.0;
                    this.motionZ *= 0.0;
                }
            }
            // Update server of location
            if (this.world.isRemote && this.world.getTotalWorldTime() % 20 == 0)
                PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.SENDSPACEPOS.ordinal()));
        } else if (isInFlight()) {
            boolean burningFuel = isBurningFuel();

            boolean descentPhase = isDescentPhase();

            if (burningFuel || descentPhase) {
                //Burn the rocket fuel
                // TODO SHOULD WE BURN IN DECENT PHASE TOO???
                // TODO THIS COULD MAKE IT SO THAT OUT OF FUEL -> You crash -> rocket takes a lot of damage
                if (!world.isRemote && !descentPhase) {
                    setFuelAmount(getRocketFuelType(), getFuelAmount(getRocketFuelType()) - getFuelConsumptionRate(getRocketFuelType()));
                    if (getRocketFuelType() == FuelType.LIQUID_BIPROPELLANT)
                        setFuelAmount(FuelType.LIQUID_OXIDIZER, getFuelAmount(FuelType.LIQUID_OXIDIZER) - getFuelConsumptionRate(FuelType.LIQUID_OXIDIZER));

                    if (getFuelAmount(getRocketFuelType()) == 0) {
                        stats.setFuelFluid("null");
                        stats.setWorkingFluid("null");
                    }
                    if (getRocketFuelType() == FuelType.LIQUID_BIPROPELLANT && getFuelAmount(FuelType.LIQUID_OXIDIZER) == 0) {
                        stats.setOxidizerFluid("null");
                    }
                }

                runEngines();
            }
            if (!world.isRemote) {

                if (isInOrbit() && descentPhase) { //going down & slowing
                    this.motionY -= this.motionY / 120f;
                    this.velocityChanged = true;
                } else {
                    //If out of fuel or descending then accelerate downwards
                    if (isInOrbit() || !burningFuel) {
                        //this.motionY = Math.min(this.motionY - 0.001, 1);
                        this.motionY = this.motionY - 0.1f * 1 / 20f * 9.81 * (DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).getGravitationalMultiplier());
                        motionY = Math.max(-2, motionY);
                        this.velocityChanged = true;
                    } else {
                        //this.motionY = Math.min(this.motionY + 0.001, 1);
                        this.motionY += stats.getAcceleration(DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).getGravitationalMultiplier()) * deltaTime;
                        this.velocityChanged = true;
                    }
                }

                if (isInOrbit() && descentPhase) { //going down & slowing
                    this.motionY -= this.motionY / 120f;
                    this.velocityChanged = true;
                }


                double lastPosY = this.posY;
                double prevMotion = this.motionY;
                this.move(MoverType.SELF, 0, prevMotion * deltaTime, 0);


                boolean landedInSpace = DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).isAsteroid() && this.posY < 64;
                boolean landedOnGround = lastPosY + prevMotion != this.posY && this.posY < 256;
                //Check to see if it's landed
                if ((isInOrbit() || !burningFuel) && isInFlight() && (landedOnGround || landedInSpace)) {
                    //Did  sending this packet cause problems?
                    PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, (byte) PacketType.ROCKETLANDEVENT.ordinal()), this);
                    MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLandedEvent(this));
                    this.motionY = 0;
                    this.setInFlight(false);
                    this.setInOrbit(false);
                    releaseDestinationPreload();
                }


                //Checks heights to see how high the rocket should go
                //I cannot believe I am doing this but it's not like orbital mechanics exists anyway.... here, have an approximation for it being harder to get to farther moons
                if (!isInOrbit() && (this.posY > stats.orbitHeight)) {
                    onOrbitReached();
                }


                //If the rocket falls out of the world while in orbit either fall back to earth or die
                if (this.posY < 0) {
                    int dimId = world.provider.getDimension();

                    if (dimId == ARConfiguration.getCurrentConfig().spaceDimId) {

                        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(getPosition());

                        if (spaceObject != null) {
                            int targetDimID = spaceObject.getOrbitingPlanetId();

                            Vector3F<Float> pos = storage.getDestinationCoordinates(targetDimID, true);
                            if (pos != null) {
                                setInOrbit(true);
                                setInFlight(false);
                                this.changeDimension(targetDimID, pos.x, getEntryHeight(targetDimID), pos.z);
                            } else
                                this.setDead();
                        } else {
                            Vector3F<Float> pos = storage.getDestinationCoordinates(0, true);
                            if (pos != null) {
                                setInOrbit(true);
                                setInFlight(false);
                                this.changeDimension(lastDimensionFrom, pos.x, getEntryHeight(lastDimensionFrom), pos.z);
                            } else
                                this.setDead();
                        }
                    } else
                        this.setDead();
                }
            } else {
                this.move(MoverType.SELF, 0, this.motionY*deltaTime, 0);
                //this.setPosition(posX, posY + this.motionY * deltaTime, posZ);
            }
        } else if (DimensionManager.getInstance().getDimensionProperties(this.world.provider.getDimension()).isAsteroid() && getRCS()) {

            this.rotationYaw += (turningRight ? 5 : 0) - (turningLeft ? 5 : 0);
            double acc = this.getPassengerMovingForward() * .02;
            //RCS mode, steer like boat
            float yawAngle = (float) (this.rotationYaw * Math.PI / 180f);
            this.motionX += acc * MathHelper.sin(-yawAngle);
            this.motionY += (turningUp ? 0.02 : 0) - (turningDownforWhat ? 0.02 : 0);
            this.motionZ += acc * MathHelper.cos(-yawAngle);
            this.motionX *= 0.9;
            this.motionY *= 0.9;
            this.motionZ *= 0.9;

            this.move(MoverType.SELF, this.motionX, this.motionY, this.motionZ);
        } else if (isStartupPhase())
            runEngines();

        //When we're landing, we should also destroy the blocks below the rocket if they are valid to be destroyed - but overall we do it fewer times than on launch (once instead of twice)
        if (this.posY < getTopBlock(getPosition()).getY() + 5 && this.posX > getTopBlock(getPosition()).getY() && ARConfiguration.getCurrentConfig().launchingDestroysBlocks && motionY < -0.1) {
            damageGroundBelowRocket(world, (int) this.posX, (int) this.posY - 1, (int) this.posZ, (int) Math.pow(stats.getThrust(), 0.4));
        }

        //System.out.println("motiony:"+motionY);
    }

    public void onTurnRight(boolean state) {
        turningRight = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.TURNUPDATE.ordinal()));
    }

    public void onTurnLeft(boolean state) {
        turningLeft = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.TURNUPDATE.ordinal()));
    }

    public void onUp(boolean state) {
        turningUp = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.TURNUPDATE.ordinal()));
    }

    public void onDown(boolean state) {
        turningDownforWhat = state;
        PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.TURNUPDATE.ordinal()));
    }

    /**
     * @return a list of satellites stores in this rocket
     */
    public List<SatelliteBase> getSatellites() {
        List<SatelliteBase> satellites = new ArrayList<>();
        for (TileSatelliteHatch tile : storage.getSatelliteHatches()) {
            SatelliteBase satellite = tile.getSatellite();
            if (satellite != null)
                satellites.add(satellite);
        }
        return satellites;
    }

    /**
     * Called when the rocket reaches orbit
     */
    public void onOrbitReached() {
        super.onOrbitReached();

        long targetSatellite;
        if (storage.getGuidanceComputer() != null && (targetSatellite = storage.getGuidanceComputer().getTargetSatellite()) != -1) {
            SatelliteBase sat = DimensionManager.getInstance().getSatellite(targetSatellite);
            for (TileEntity tile : storage.getTileEntityList()) {
                if (tile instanceof TileSatelliteHatch && ((IInventory) tile).getStackInSlot(0).isEmpty()) {
                    ((IInventory) tile).setInventorySlotContents(0, sat.getItemStackFromSatellite());
                    DimensionManager.getInstance().getDimensionProperties(sat.getDimensionId()).removeSatellite(targetSatellite);
                    break;
                }
            }
            this.motionY = -2;
            setInOrbit(true);
        } else if (!stats.hasSeat()) {
            reachSpaceUnmanned();
        } else {
            reachSpaceManned();
        }
    }

    /**
     * @param entryLocationDimID the dimension ID for the dimension the rocket is entering
     * @return integer for world height in blocks the rocket will spawn in at when it reaches the dimension
     */
    private int getEntryHeight(int entryLocationDimID) {
        if (entryLocationDimID == ARConfiguration.getCurrentConfig().spaceDimId) {
            return ARConfiguration.getCurrentConfig().stationClearanceHeight;
        } else {
            return ARConfiguration.getCurrentConfig().orbit;
        }
    }

    private void reachSpaceUnmanned() {
        TileGuidanceComputer computer = storage.getGuidanceComputer();
        if (computer != null && computer.getStackInSlot(0).getItem() instanceof ItemAsteroidChip) {
            //make it 30 minutes with one drill
            float drillingPower = stats.getDrillingPower();

            float asteroidDrillingMult = 1f;

            ItemStack stack = storage.getGuidanceComputer().getStackInSlot(0);

            Asteroid asteroid = ARConfiguration.getCurrentConfig().asteroidTypes.get(((ItemAsteroidChip) stack.getItem()).getType(stack));

            if (asteroid != null) {
                asteroidDrillingMult = asteroid.timeMultiplier;
            }

            MissionOreMining miningMission = new MissionOreMining((long) (asteroidDrillingMult * ARConfiguration.getCurrentConfig().asteroidMiningTimeMult * (drillingPower == 0f ? 36000 : 360 / stats.getDrillingPower())), this, connectedInfrastructure);
            DimensionProperties properties = DimensionManager.getEffectiveDimId(world, getPosition());

            miningMission.setDimensionId(world);
            properties.addSatellite(miningMission, world);

            if (!world.isRemote)
                PacketHandler.sendToAll(new PacketSatellite(miningMission));

            for (IInfrastructure i : connectedInfrastructure) {
                i.linkMission(miningMission);
            }

            this.setDead();
            //TODO: Move tracking stations over to the mission handler
        } else {
            unpackSatellites();
        }

        destinationDimId = storage.getDestinationDimId(this.world.provider.getDimension(), (int) this.posX, (int) this.posZ);
        if (destinationDimId == this.world.provider.getDimension()) {
            Vector3F<Float> pos = storage.getDestinationCoordinates(destinationDimId, true);
            storage.setDestinationCoordinates(new Vector3F<>((float) this.posX, (float) this.posY, (float) this.posZ), this.world.provider.getDimension());
            if (pos != null) {
                this.setInOrbit(true);
                this.motionY = -2;

                //unlink any connected tiles
                Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();
                while (connectedTiles.hasNext()) {
                    connectedTiles.next().unlinkRocket();
                    connectedTiles.remove();
                }

                this.setPositionAndUpdate(pos.x, getEntryHeight(destinationDimId), pos.z);
            } else {

                //Make player confirm deorbit if a player is riding the rocket
                if (hasHumanPassenger()) {
                    setInFlight(false);
                    this.setPositionAndUpdate(this.posX, getEntryHeight(destinationDimId), this.posZ);

                }
                this.setInOrbit(true);
                this.motionY = -2;
                //unlink any connected tiles

                Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();
                while (connectedTiles.hasNext()) {
                    connectedTiles.next().unlinkRocket();
                    connectedTiles.remove();
                }

                this.setPositionAndUpdate(this.posX, getEntryHeight(destinationDimId), this.posZ);
            }

        } else if (DimensionManager.getInstance().canTravelTo(destinationDimId)) {
            Vector3F<Float> pos = storage.getDestinationCoordinates(destinationDimId, true);
            storage.setDestinationCoordinates(new Vector3F<>((float) this.posX, (float) this.posY, (float) this.posZ), this.world.provider.getDimension());
            if (pos != null) {
                this.setInOrbit(true);
                this.motionY = -2;
                this.changeDimension(destinationDimId, pos.x, getEntryHeight(destinationDimId), pos.z);
            } else {

                //Make player confirm deorbit if a player is riding the rocket
                if (hasHumanPassenger()) {
                    setInFlight(false);
                    this.setPositionAndUpdate(this.posX, getEntryHeight(destinationDimId), this.posZ);

                }
                this.setInOrbit(true);
                this.motionY = -2;

                this.changeDimension(destinationDimId, this.posX, getEntryHeight(destinationDimId), this.posZ);
            }
        } else {
            //Make rocket return semi nearby
            int offX = (world.rand.nextInt() % 256) - 128;
            int offZ = (world.rand.nextInt() % 256) - 128;
            this.setInOrbit(true);
            this.motionY = -2;
            this.setPosition(posX + offX, posY, posZ + offZ);

            //unlink any connected tiles
            Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();
            while (connectedTiles.hasNext()) {
                connectedTiles.next().unlinkRocket();
                connectedTiles.remove();
            }

            //this.setDead();
            //TODO: satellite event?
        }
    }

    private void reachSpaceManned() {
        unpackSatellites();
        Vector3F<Float> destPos = new Vector3F<>(0f, 0f, 0f);

        // Update space position
        if (ARConfiguration.getCurrentConfig().experimentalSpaceFlight && storage.getGuidanceComputer().isEmpty() && hasHumanPassenger() && !getInSpaceFlight()) {
            DimensionProperties currentDim = DimensionManager.getEffectiveDimId(world, getPosition());

            // Get top level planet
            while (currentDim.isMoon()) currentDim = currentDim.getParentProperties();

            SpacePosition planetSpacePos = currentDim.getSpacePosition();

            SpacePosition modifiedPosition = new SpacePosition().getFromSpherical(currentDim.getRenderSizePlanetView() * 1.1, 0);

            spacePosition.x = modifiedPosition.x;
            spacePosition.y = modifiedPosition.y;
            spacePosition.z = modifiedPosition.z;
            spacePosition.star = planetSpacePos.star;

            spacePosition.world = planetSpacePos.world;
            setInSpaceFlight(true);
            setRCS(true);
            setInOrbit(true);
            this.motionX = 0;
            this.motionY = 0;
            this.motionZ = 0;

            destinationDimId = ARConfiguration.getCurrentConfig().spaceDimId;
            destPos.x = 0f;
            destPos.y = (float) getEntryHeight(destinationDimId);
            destPos.z = 0f;

            for (Entity e : getPassengers()) {
                if (e instanceof EntityPlayer) {
                    PacketHandler.sendToPlayer(new PacketEntity(this, (byte) PacketType.SENDSPACEPOS.ordinal()), (EntityPlayer) e);
                }
            }

        } else {
            this.motionY = -2;
            setInOrbit(true);
            //If going to a station or something make sure to set coords accordingly
            //If in space land on the planet, if on the planet go to space
            if ((destinationDimId == ARConfiguration.getCurrentConfig().spaceDimId || (this.world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId && !getInSpaceFlight())) && this.world.provider.getDimension() != destinationDimId) {
                Vector3F<Float> pos = storage.getDestinationCoordinates(destinationDimId, true);
                storage.setDestinationCoordinates(new Vector3F<>((float) this.posX, (float) this.posY, (float) this.posZ), this.world.provider.getDimension());
                if (pos != null) {

                    //Make player confirm deorbit if a player is riding the rocket
                    if (hasHumanPassenger()) {
                        setInFlight(false);
                        pos.y = (float) getEntryHeight(destinationDimId);

                    }

                    this.changeDimension(destinationDimId, pos.x, pos.y, pos.z);
                    return;
                }
            }


            //if coordinates are overridden, make sure we grab them
            destPos = storage.getDestinationCoordinates(destinationDimId, true);
            if (destPos == null)
                destPos = new Vector3F<>((float) posX, (float) getEntryHeight(destinationDimId), (float) posZ);

            if (hasHumanPassenger()) {
                //Make player confirm deorbit if a player is riding the rocket
                setInFlight(false);

                if (DimensionManager.getInstance().getDimensionProperties(destinationDimId).getName().equals("Luna")) {
                    for (Entity player : this.getPassengers()) {
                        if (player instanceof EntityPlayer) {
                            ARAdvancements.MOON_LANDING.trigger((EntityPlayerMP) player);
                            if (!DimensionManager.hasReachedMoon)
                                ARAdvancements.ONE_SMALL_STEP.trigger((EntityPlayerMP) player);
                        }
                    }
                    DimensionManager.hasReachedMoon = true;
                }
            }
            destPos.y = (float) getEntryHeight(destinationDimId);
        }

        //Reset override coords
        setOverriddenCoords(-1, 0, 0, 0);

        if (destinationDimId != this.world.provider.getDimension())
            this.changeDimension(!DimensionManager.getInstance().isDimensionCreated(this.world.provider.getDimension()) ? 0 : destinationDimId, destPos.x, getEntryHeight(destinationDimId), destPos.z);
        else {
            List<Entity> eList = this.getPassengers();
            for (Entity e : eList) {
                e.dismountRidingEntity();
                e.setPositionAndUpdate(destPos.x, destPos.y, destPos.z);
            }
            this.setPositionAndUpdate(destPos.x, destPos.y, destPos.z);
            this.ticksExisted = 0;
            ((WorldServer) world).resetUpdateEntityTick();
            for (Entity e : eList) {
                e.startRiding(this, true);
            }
        }
    }

    private void unpackSatellites() {
        List<TileSatelliteHatch> satelliteHatches = storage.getSatelliteHatches();

        for (TileSatelliteHatch tile : satelliteHatches) {
            SatelliteBase satellite = tile.getSatellite();
            if (satellite == null) {
                ItemStack stack = tile.getStackInSlot(0);
                if (!stack.isEmpty() && stack.getItem() == AdvancedRocketryItems.itemSpaceStation) {
                    StorageChunk storage = ((ItemPackedStructure) stack.getItem()).getStructure(stack);
                    ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(ItemStationChip.getUUID(stack));

                    //in case of no NBT data or the like
                    if (spaceObject == null) {
                        tile.setInventorySlotContents(0, ItemStack.EMPTY);
                        continue;
                    }

                    SpaceObjectManager.getSpaceManager().moveStationToBody(spaceObject,
                            DimensionManager.getEffectiveDimId(this.world.provider.getDimension(), getPosition()).getId());

                    //Vector3F<Integer> spawn = spaceObject.getSpawnLocation();

                    spaceObject.onModuleUnpack(storage);
                    tile.setInventorySlotContents(0, ItemStack.EMPTY);
                }
            } else {
                int destinationId = storage.getDestinationDimId(world.provider.getDimension(), (int) posX, (int) posZ);
                DimensionProperties properties = DimensionManager.getEffectiveDimId_byID(destinationId, this.getPosition());
                int world2;
                if (destinationId == ARConfiguration.getCurrentConfig().spaceDimId || destinationId == Constants.INVALID_PLANET)
                    world2 = properties.getId();
                else
                    world2 = destinationId;

                properties.addSatellite(satellite, world2, world.isRemote);
                tile.setInventorySlotContents(0, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Called immediately before launch
     */
    @Override
    public void prepareLaunch() {

        if (this.dataManager.get(LAUNCH_COUNTER) > 0) {
            this.dataManager.set(LAUNCH_COUNTER, -1);
            if (world.isRemote)
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.ABORTLAUNCH.ordinal()));
            return;
        }

        // Free Flight Mode short-circuit: skip classic countdown + destination
        // validation. The pilot just enters arcade flight directly.
        ffTrace("prepareLaunch isFreeFlight=" + isFreeFlight() + " isInFlight=" + isInFlight());
        if (isFreeFlight()) {
            if (world.isRemote) {
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.LAUNCH.ordinal()));
            } else if (!isInFlight()) {
                // Same fuel/TWR gate as the ENGINE_START ritual. prepareLaunch is
                // reachable WITHOUT that ritual (Space, or a redstone monitoring
                // station calling prepareLaunch directly), so entering flight here
                // without lift authority would strand the craft in a thrustless
                // on-pad in-flight dead-state it can never leave. Report the
                // reason to any pilot and stay grounded instead.
                if (canStartFreeFlight()) {
                    startFreeFlight();
                } else {
                    ffTrace("prepareLaunch FF rejected: no fuel / no climb authority (TWR <= 1)");
                    messagePilot("msg.entity.rocket.ffNoLiftoff");
                }
            }
            return;
        }

        if (isInOrbit()) {
            setInFlight(true);
            return;
        }

        RocketPreLaunchEvent event = new RocketEvent.RocketPreLaunchEvent(this);
        MinecraftForge.EVENT_BUS.post(event);

        if (!event.isCanceled()) {
            if (world.isRemote)
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.LAUNCH.ordinal()));
            this.dataManager.set(LAUNCH_COUNTER, 200);
        }
    }

    private double gauss(double mean, double div) {
        Random rand = world.rand;
        return mean + (rand.nextDouble() - 0.5F) * 2F * div;
    }

    public void explode() {
        if (world.isRemote && Minecraft.getMinecraft().gameSettings.particleSetting < 2) {
            AxisAlignedBB bb = getCollisionBoundingBox();
            double meanX = (bb.maxX + bb.minX) / 2;
            double meanY = (bb.maxY + bb.minY) / 2;
            double meanZ = (bb.maxZ + bb.minZ) / 2;
            double divX = (bb.maxX - bb.minX) / 1.2;
            double divY = (bb.maxY - bb.minY) / 1.2;
            double divZ = (bb.maxZ - bb.minZ) / 1.2;

            if (Minecraft.getMinecraft().gameSettings.particleSetting < 1) {
                for (int i = 0; i < 10; i++) {
                    AdvancedRocketry.proxy.spawnParticle("rocketSmoke", world,
                            gauss(meanX, divX), gauss(meanY, divY), gauss(meanZ, divZ),
                            (this.rand.nextFloat() - 0.5f) / 4f, (this.rand.nextFloat() - 0.5f) / 4f, (this.rand.nextFloat() - 0.5f) / 4f);
                }
            }

            for (int i = 0; i < 50; i++) {
                AdvancedRocketry.proxy.spawnParticle("rocketFlame", world,
                        gauss(meanX, divX), gauss(meanY, divY), gauss(meanZ, divZ),
                        (this.rand.nextFloat() - 0.5f) / 4f, (this.rand.nextFloat() - 0.5f) / 4f, (this.rand.nextFloat() - 0.5f) / 4f);
            }
        }

        this.setDead();
    }

    public void recalculateStats(){
        this.storage.recalculateStats(this.stats);
    }

    /**
     * Launches the rocket post determining its height, checking whether it can launch to the selected planet and whether it can exist,
     * among other factors. Also handles orbital height calculations
     */
    @Override
    public void launch() {

        if(world.isRemote)return;

        if (isInFlight())
            return;

        boolean allowLaunch = false;

        this.storage.recalculateStats(this.stats);

        NBTTagCompound nbtdata = new NBTTagCompound();
        writeToNBT(nbtdata);
        // Can this be done without sending the entity packet again?
        // It causes rocket to skip rendering a few frames when launching
        PacketHandler.sendToNearby(new PacketEntity(this, (byte) 0, nbtdata), this.world.provider.getDimension(), this.getPosition(), 64);


        if (ARConfiguration.getCurrentConfig().advancedWeightSystem) {
            this.stats.setWeight(storage.recalculateWeight());
            for (HashedBlockPosition pos : this.infrastructureCoords) {
                TileEntity te = world.getTileEntity(pos.getBlockPos());
                if (te instanceof TileRocketAssemblingMachine) {
                    //this does not work: getWeight() returns weight + fuel. setWeight() should not include fuel weight because it is calculated on every getweight()
                    // so if you say setweight(getweight()) and next time I call getweight() it returns weight+fuel+fuel
                    // we do not need this anyway because the assembler has IDataSync interface and syncs itself
                    //((TileRocketAssemblingMachine) te).getRocketStats().setWeight(this.stats.getWeight());
                }
            }
        }

        if (ARConfiguration.getCurrentConfig().partsWearSystem) {
            ARConfiguration cfg = ARConfiguration.getCurrentConfig();

            // A worn seat is unsafe: refuse a CREWED launch (automated rockets fly).
            if (!this.getPassengers().isEmpty() && storage.hasCriticallyWornSeat(cfg.wearSeatBlockStageFraction)) {
                setError("error.rocket.seatWorn");
                return;
            }

            // Failure probability = motor wear + leak-ignition risk of worn tanks
            // that actually carry fuel/oxidizer. Computed without side effects so
            // the block decision below does not strand a half-leaked rocket.
            float failProb = storage.getBreakingProbability();
            for (StorageChunk.WornTank tank : storage.getWornTanks()) {
                if (getFuelAmount(tank.type) > 0) {
                    failProb += (float) cfg.wearTankLeakChanceMax * tank.wornFraction;
                }
            }
            failProb = Math.min(1f, failProb);

            if (failProb >= cfg.wearWarnProbability) {
                messagePilot("warning.rocket.worn", (int) (failProb * 100));
                if (cfg.wearCriticalBlocksLaunch) {
                    setError("error.rocket.tooWorn", (int) (failProb * 100));
                    return;
                }
            }

            if (failProb > 0 && world.rand.nextFloat() < failProb) {
                this.explode();
                return;
            }

            // Launch proceeds, but worn tanks bleed some of their fuel.
            for (StorageChunk.WornTank tank : storage.getWornTanks()) {
                int amt = getFuelAmount(tank.type);
                if (amt > 0 && world.rand.nextFloat() < cfg.wearTankLeakChanceMax * tank.wornFraction) {
                    setFuelAmount(tank.type, (int) (amt * (1 - cfg.wearTankLeakFuelLoss)));
                }
            }
        }

        if (ARConfiguration.getCurrentConfig().experimentalSpaceFlight && storage.getGuidanceComputer() != null && storage.getGuidanceComputer().isEmpty()) {
            allowLaunch = true;
        } else {

            //Get destination dimid and lock the computer
            //TODO: lock the computer
            destinationDimId = storage.getDestinationDimId(world.provider.getDimension(), (int) this.posX, (int) this.posZ);

            if (!(DimensionManager.getInstance().canTravelTo(destinationDimId) || (destinationDimId == Constants.INVALID_PLANET && storage.getSatelliteHatches().size() != 0))) {
                setError("error.rocket.cannotGetThere");
                return;
            }

            boolean destinationIsSpaceStation = false;
            int finalDest = destinationDimId;
            if (destinationDimId == ARConfiguration.getCurrentConfig().spaceDimId) {
                ISpaceObject spaceObject = null;
                Vector3F<Float> vec = storage.getDestinationCoordinates(destinationDimId, false);

                if (vec != null)
                    spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(new BlockPos(vec.x, vec.y, vec.z));

                if (spaceObject != null) {
                    destinationIsSpaceStation = true;
                    finalDest = spaceObject.getOrbitingPlanetId();
                } else {
                    setError("error.rocket.destinationNotExist");
                    return;
                }
            }


            //If we're on a space station get the id of the planet, not the station
            int thisDimId = this.world.provider.getDimension();
            if (this.world.provider.getDimension() == ARConfiguration.getCurrentConfig().spaceDimId) {
                ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.getPosition());
                if (spaceObject != null)
                    thisDimId = spaceObject.getProperties().getParentProperties().getId();
            }

            //Check to see if it's possible to reach (split failure modes)
            if (finalDest != Constants.INVALID_PLANET) {

                DimensionProperties destProps = DimensionManager.getInstance().getDimensionProperties(finalDest);
                DimensionProperties srcProps  = DimensionManager.getInstance().getDimensionProperties(thisDimId);

                boolean isNuclear = stats.isNuclear();
                boolean sameStar  = destProps.getStarId() == srcProps.getStarId();
                boolean outsidePlanetarySystem = !PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(finalDest, thisDimId);

                // Nuclear artifact gating only.
                // Normal rockets never care about artifacts; their range is limited separately.
                if (isNuclear && ARConfiguration.getCurrentConfig().nuclearRocketsRespectArtifactGating) {
                    ItemStack artifact = getGateArtifact(destProps);

                    // Stations orbiting gated planets might require artifact. (config Boolean)
                    boolean stationArtifactExempt =
                            destinationIsSpaceStation &&
                                    !ARConfiguration.getCurrentConfig().nuclearRocketsRequireArtifactForGatedStations;

                    if (!stationArtifactExempt && !artifact.isEmpty() && outsidePlanetarySystem) {
                        EntityPlayer pilot = getPilot();
                        if (!pilotHasArtifact(pilot, artifact)) {
                            setError("error.rocket.gatedArtifactMissingWithItem",
                                    artifact.getCount(),
                                    artifact.getDisplayName());
                            return;
                        }
                    }
                }


                // Nuclear cannot cross stars
                if (isNuclear && !sameStar) {
                    setError("error.rocket.outsideStarSystem");
                    return;
                }

                // Non-nuclear cannot go outside planetary system
                if (!isNuclear && outsidePlanetarySystem) {
                    setError("error.rocket.outsidePlanetarySystem");
                    return;
                }
            }
        }


        if (!this.stats.canLaunch()) {
            setError("error.rocket.tooHeavy");
            return; // hard stop; no silent fall-through
        }

        //Check to see what place we should be going to
        //This is bad but it works and is mostly intelligible so it's here for now
        stats.orbitHeight = (storage.getGuidanceComputer() == null) ? getEntryHeight(this.world.provider.getDimension()) : storage.getGuidanceComputer().getLaunchSequence(this.world.provider.getDimension(), this.getPosition());
        
        // Enough fuel for the mission?
        if (!hasMissionFuelFor(destinationDimId)) {
            setError("error.rocket.notEnoughMissionFuel");
            return;
        }
        
        //TODO: Clean this logic a bit?
        if (allowLaunch || !stats.hasSeat() || ((DimensionManager.getInstance().isDimensionCreated(destinationDimId)) || destinationDimId == ARConfiguration.getCurrentConfig().spaceDimId || destinationDimId == 0)) {
            setInFlight(true);
            Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();

            MinecraftForge.EVENT_BUS.post(new RocketLaunchEvent(this));

            // ---- PRELOAD DESTINATION 3x3 (server only) ----
            if (!world.isRemote) {
                boolean willTeleportAtAscent =
                    !(ARConfiguration.getCurrentConfig().experimentalSpaceFlight && storage.getGuidanceComputer().isEmpty());

                // Only preload when we know we’ll teleport off this world soon
                if (willTeleportAtAscent) {
                    int dimId = destinationDimId;

                    boolean canLoad =
                        DimensionManager.getInstance().isDimensionCreated(dimId) ||
                        dimId == ARConfiguration.getCurrentConfig().spaceDimId;

                    if (canLoad) {
                        Vector3F<Float> destVec = (storage != null) ? storage.getDestinationCoordinates(dimId, true) : null;
                        double dx = (destVec != null) ? destVec.x : this.posX;
                        double dz = (destVec != null) ? destVec.z : this.posZ;

                        preloadDestinationChunks(dimId, dx, dz, /*radiusChunks*/ 1, /*holdSeconds*/ 60);
                    }
                }
            }
            // -----------------------------------------------


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

    /**
     * Damages the ground beneath the rocket, depending on block type
     */
    private void damageGroundBelowRocket(World world, int x, int y, int z, int radius) {
        //Start on the same level as the bottom of the rocket
        BlockPos center = new BlockPos(x - 1, y, z);
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                for (int k = -3; k < radius / 12; k++) {
                    //Check for a circle, not a square
                    BlockPos position = center.add(i, k, j);
                    if (center.distanceSq(position) <= radius * radius) {
                        //Set blocks to their damaged variants
                        if (rand.nextInt(80) == 0 && getDamagedBlock(world.getBlockState(position)) != null) {
                            world.setBlockState(position, getDamagedBlock(world.getBlockState(position)));
                        }
                        //Always set fire above that
                        BlockPos blockAbove = position.add(0, 1, 0);
                        if (world.getBlockState(blockAbove).getBlock().isAir(world.getBlockState(blockAbove), world, blockAbove)) {
                            world.setBlockState(blockAbove, AdvancedRocketryBlocks.blockRocketFire.getDefaultState());
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the rocket is to be deconstructed
     */
    @Override
    public void deconstructRocket() {
        clearPlanetSelectorCache();
        super.deconstructRocket();

        for (IInfrastructure infrastructure : connectedInfrastructure) {
            infrastructure.unlinkRocket();
        }

        //paste the rocket into the world as blocks
        storage.pasteInWorld(this.world, (int) (this.posX - storage.getSizeX() / 2f), (int) this.posY, (int) (this.posZ - storage.getSizeZ() / 2f));

        this.setDead();
    }

    @Override
    public void setDead() {
        clearPlanetSelectorCache();
        super.setDead();
        releaseDestinationPreload();

        if (storage != null && storage.world.displayListIndex != -1)
            GLAllocation.deleteDisplayLists(storage.world.displayListIndex);

        //unlink any connected tiles
        Iterator<IInfrastructure> connectedTiles = connectedInfrastructure.iterator();
        while (connectedTiles.hasNext()) {
            connectedTiles.next().unlinkRocket();
            connectedTiles.remove();
        }

    }

    public void setOverriddenCoords(int dimId, float x, float y, float z) {
        TileGuidanceComputer tile = storage.getGuidanceComputer();
        if (tile != null) {
            tile.setFallbackDestination(dimId, new Vector3F<>(x, y, z));
        }
    }

    @Override
    public Entity changeDimension(int newDimId) {
        clearPlanetSelectorCache();

        return changeDimension(newDimId, this.posX, getEntryHeight(newDimId), this.posZ);
    }

    @Nullable
    public Entity changeDimension(int dimensionIn, double posX, double y, double posZ) {
        if (!this.world.isRemote && !this.isDead) {

            if (!DimensionManager.getInstance().canTravelTo(dimensionIn)) {
                AdvancedRocketry.logger.warn("Rocket trying to travel from Dim" + this.world.provider.getDimension() + " to Dim " + dimensionIn + ".  target not accessible by rocket from launch dim");
                return null;
            }

            lastDimensionFrom = this.world.provider.getDimension();

            List<Entity> passengers = getPassengers();
            int i = this.dimension;
            MinecraftServer minecraftserver = this.getServer();
            WorldServer worldserver = minecraftserver.getWorld(i);
            WorldServer worldserver1 = minecraftserver.getWorld(dimensionIn);
            this.setPosition(posX, y, posZ);

            ITeleporter teleporter = new BasicTeleporter(getPosition());
            Entity entity = changeDimension(dimensionIn, teleporter);

            if (entity == null)
                return null;

            entity.moveToBlockPosAndAngles(new BlockPos(posX, y, posZ), 0, 0);

            int timeOffset = 1;
            for (Entity e : passengers) {
                e.getEntityData().setLong("arRocketTransferGrace", worldserver.getTotalWorldTime() + 100L);
                PlanetEventHandler.addDelayedTransition(new TransitionEntity(
                        worldserver.getTotalWorldTime() + ++timeOffset,
                        e,
                        dimensionIn,
                        new BlockPos(posX, y, posZ),
                        entity
                ));
            }
            return entity;
        }
        return null;
    }

    /**
     * Prepares this entity in new dimension by copying NBT data from entity in old dimension
     */
    public void copyDataFromOld(Entity entityIn) {
        NBTTagCompound nbttagcompound = entityIn.writeToNBT(new NBTTagCompound());
        nbttagcompound.removeTag("Dimension");
        nbttagcompound.removeTag("Passengers");
        this.readFromNBT(nbttagcompound);
        this.timeUntilPortal = entityIn.timeUntilPortal;
        clearPlanetSelectorCache();
    }

    protected void readNetworkableNBT(NBTTagCompound nbt) {
        //Normal function checks for the existence of the data anyway
        readEntityFromNBT(nbt);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound nbt) {
        setInOrbit(isInOrbit = nbt.getBoolean("orbit"));
        rcs_mode_counter = nbt.getInteger("rcs_mode_cnt");
        setInSpaceFlight(nbt.getBoolean("inSpaceFlight"));
        rcs_mode = nbt.getBoolean("rcs_mode") || getInSpaceFlight();
        setRCS(rcs_mode);
        stats.readFromNBT(nbt);

        FuelType fuelType = getRocketFuelType();
        if (fuelType != null) {
            setFuelAmount(fuelType, stats.getFuelAmount(fuelType));
            if (getRocketFuelType() == FuelType.LIQUID_BIPROPELLANT)
                setFuelAmount(FuelType.LIQUID_OXIDIZER, stats.getFuelAmount(FuelType.LIQUID_OXIDIZER));
        }

        setInFlight(isInFlight = nbt.getBoolean("flight"));
        motionX = nbt.getDouble("motionX");
        motionY = nbt.getDouble("motionY");
        motionZ = nbt.getDouble("motionZ");

        // Free Flight Mode — backcompat: missing key -> CLASSIC_LAUNCH (DEFAULT).
        flightMode = RocketFlightMode.readFromNBT(nbt);
        // FF attitude quaternion. Missing key (older save /
        // never-flown rocket) -> upright identity; the pilot re-orients in flight.
        if (nbt.hasKey("ffQuatW")) {
            ffQuat = new FreeFlightPhysics.Quat(nbt.getFloat("ffQuatW"), nbt.getFloat("ffQuatX"),
                    nbt.getFloat("ffQuatY"), nbt.getFloat("ffQuatZ")).normalized();
        } else {
            ffQuat = FreeFlightPhysics.Quat.IDENTITY;
        }
        prevFfQuat = ffQuat;
        if (isFreeFlight()) {
            float[] eLoad = FreeFlightPhysics.eulerFromQuat(ffQuat);
            rotationYaw = eLoad[0];
            rotationPitch = eLoad[1];
            freeFlightPitch = eLoad[1];
            freeFlightRoll = eLoad[2];
            prevFreeFlightRoll = freeFlightRoll;
        }
        // Flight Assist default ON for missing-key (legacy) saves.
        flightAssistOn = nbt.hasKey("flightAssistOn") ? nbt.getBoolean("flightAssistOn") : true;
        // Engine-start liftoff state; missing keys (legacy saves)
        // -> assist inactive + landing detector armed, i.e. plain in-flight.
        ffLiftoffTargetY = nbt.hasKey("ffLiftoffTargetY")
                ? nbt.getDouble("ffLiftoffTargetY") : Double.NaN;
        freeFlightHasLeftGround = !nbt.hasKey("ffHasLeftGround") || nbt.getBoolean("ffHasLeftGround");
        // FA velocity setpoint; missing keys -> zero (hover intent).
        setFaSetpoint(nbt.getFloat("faSetpointFwd"),
                nbt.getFloat("faSetpointRight"),
                nbt.getFloat("faSetpointUp"));

        readMissionPersistentNBT(nbt);
        if (nbt.hasKey("data")) {
            if (storage == null)
                storage = new StorageChunk();

            storage.readFromNBT(nbt.getCompoundTag("data"));
            storage.setEntity(this);
            this.setSize(Math.max(storage.getSizeX(), storage.getSizeZ()), storage.getSizeY());
        }

        if (nbt.hasKey("infrastructure")) {
            NBTTagList tagList = nbt.getTagList("infrastructure", 10);
            for (int i = 0; i < tagList.tagCount(); i++) {
                int[] coords = tagList.getCompoundTagAt(i).getIntArray("loc");

                infrastructureCoords.add(new HashedBlockPosition(coords[0], coords[1], coords[2]));

            }
        }
        destinationDimId = nbt.getInteger("destinationDimId");

        lastDimensionFrom = nbt.getInteger("lastDimensionFrom");

        // TODO: Fix spelling
        //Satellite
        if (nbt.hasKey("satallite")) {
            NBTTagCompound satelliteNBT = nbt.getCompoundTag("satallite");
            satellite = SatelliteRegistry.createFromNBT(satelliteNBT);
        }

        spacePosition.readFromNBT(nbt);
    }

    protected void writeNetworkableNBT(NBTTagCompound nbt) {
        writeMissionPersistentNBT(nbt);
        nbt.setBoolean("orbit", isInOrbit());
        nbt.setBoolean("flight", isInFlight());
        nbt.setBoolean("rcs_mode", rcs_mode);
        nbt.setInteger("rcs_mode_cnt", rcs_mode_counter);
        nbt.setBoolean("inSpaceFlight", getInSpaceFlight());
        nbt.setDouble("motionX", motionX);
        nbt.setDouble("motionY", motionY);
        nbt.setDouble("motionZ", motionZ);
        // Free Flight Mode — written unconditionally so the field round-trips
        // even when toggled to CLASSIC_LAUNCH (avoids "is missing key == default" ambiguity).
        RocketFlightMode.writeToNBT(nbt, flightMode);
        // FF attitude quaternion — the source of truth; the
        // Euler freeFlightPitch/Roll are derived and no longer persisted.
        FreeFlightPhysics.Quat wq = ffQuat == null ? FreeFlightPhysics.Quat.IDENTITY : ffQuat;
        nbt.setFloat("ffQuatW", (float) wq.w);
        nbt.setFloat("ffQuatX", (float) wq.x);
        nbt.setFloat("ffQuatY", (float) wq.y);
        nbt.setFloat("ffQuatZ", (float) wq.z);
        nbt.setBoolean("flightAssistOn", flightAssistOn);
        if (!Double.isNaN(ffLiftoffTargetY))
            nbt.setDouble("ffLiftoffTargetY", ffLiftoffTargetY);
        nbt.setBoolean("ffHasLeftGround", freeFlightHasLeftGround);
        nbt.setFloat("faSetpointFwd",   getFaSetpointForward());
        nbt.setFloat("faSetpointRight", getFaSetpointRight());
        nbt.setFloat("faSetpointUp",    getFaSetpointUp());
        stats.writeToNBT(nbt);

        if (!infrastructureCoords.isEmpty()) {
            NBTTagList itemList = new NBTTagList();
            for (HashedBlockPosition inf : infrastructureCoords) {

                NBTTagCompound tag = new NBTTagCompound();
                tag.setIntArray("loc", new int[]{inf.x, inf.y, inf.z});
                itemList.appendTag(tag);

            }
            nbt.setTag("infrastructure", itemList);
        }

        nbt.setInteger("destinationDimId", destinationDimId);

        //Satellite
        if (satellite != null) {
            NBTTagCompound satelliteNBT = new NBTTagCompound();
            satellite.writeToNBT(satelliteNBT);
            satelliteNBT.setString("DataType", SatelliteRegistry.getKey(satellite.getClass()));

            nbt.setTag("satallite", satelliteNBT);
        }
        spacePosition.writeToNBT(nbt);
    }

    public void writeMissionPersistentNBT(NBTTagCompound nbt) {

    }

    public void readMissionPersistentNBT(NBTTagCompound nbt) {

    }

    @Override
    protected void writeEntityToNBT(@Nonnull NBTTagCompound nbt) {

        writeNetworkableNBT(nbt);
        if (storage != null) {
            NBTTagCompound blocks = new NBTTagCompound();
            storage.writeToNBT(blocks);
            nbt.setTag("data", blocks);
        }

        //TODO handle non tile Infrastructure
        nbt.setInteger("lastDimensionFrom", lastDimensionFrom);
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {
        //System.out.println("rocket read from network");
        if(packetId==(byte)9987){ // update tileentities
            if (storage != null)
                storage.readtiles(in);
        }
        if (packetId == PacketType.RECIEVENBT.ordinal()) {
            storage = new StorageChunk(); //this re-loading makes the rocket not render for a tick or two when launching
            storage.setEntity(this);
            storage.readFromNetwork(in);
        } else if (packetId == PacketType.SENDPLANETDATA.ordinal()) {
            nbt.setInteger("selection", in.readInt());
        } else if (packetId == PacketType.TURNUPDATE.ordinal()) {
            nbt.setBoolean("left", in.readBoolean());
            nbt.setBoolean("right", in.readBoolean());
            nbt.setBoolean("up", in.readBoolean());
            nbt.setBoolean("down", in.readBoolean());
        } else if (packetId == PacketType.SENDSPACEPOS.ordinal()) {
            SpacePosition position = new SpacePosition();
            position.x = in.readDouble();
            position.y = in.readDouble();
            position.z = in.readDouble();

            boolean hasWorld = in.readBoolean();
            if (hasWorld)
                position.world = DimensionManager.getInstance().getDimensionProperties(in.readInt());

            boolean hasStar = in.readBoolean();
            if (hasStar)
                position.star = DimensionManager.getInstance().getStar(in.readInt());

            position.writeToNBT(nbt);
        } else if (packetId == PacketType.SET_FLIGHT_MODE.ordinal()) {
            // Wire: 1 byte = ordinal of RocketFlightMode (-1 -> default).
            byte ord = in.readByte();
            RocketFlightMode[] all = RocketFlightMode.values();
            RocketFlightMode mode = (ord >= 0 && ord < all.length) ? all[ord] : RocketFlightMode.DEFAULT;
            nbt.setString("flightMode", mode.name());
        } else if (packetId == PacketType.FREE_FLIGHT_INPUT.ordinal()) {
            FreeFlightInput input = FreeFlightInput.read(in);
            nbt.setFloat("ffFwd",   input.throttleForward);
            nbt.setFloat("ffVert",  input.throttleVertical);
            nbt.setFloat("ffStrafe", input.strafeInput);
            nbt.setFloat("ffYaw",   input.yawInput);
            nbt.setFloat("ffPitch", input.pitchInput);
            nbt.setFloat("ffRoll",  input.rollInput);
            nbt.setFloat("ffBrake", input.brakeInput);
            nbt.setBoolean("ffCut", input.cutActive);
        } else if (packetId == PacketType.SET_FLIGHT_ASSIST.ordinal()) {
            nbt.setBoolean("flightAssistOn", in.readBoolean());
        }
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {

        if(id==(byte)9987){ // update tileentities
            if (storage != null)
                storage.writetiles(out);
        }

        if (id == PacketType.RECIEVENBT.ordinal()) {
            storage.writeToNetwork(out);
        } else if (id == PacketType.SENDPLANETDATA.ordinal()) {
            if (world.isRemote) {
                int sel = Constants.INVALID_PLANET;
                if (container != null) {
                    sel = container.getSelectedSystem();
                }
                out.writeInt(sel);
            } else {
                if (storage.getGuidanceComputer() != null) {
                    ItemStack stack = storage.getGuidanceComputer().getStackInSlot(0);
                    if (!stack.isEmpty() && stack.getItem() == AdvancedRocketryItems.itemPlanetIdChip) {
                        out.writeInt(((ItemPlanetIdentificationChip) AdvancedRocketryItems.itemPlanetIdChip).getDimensionId(stack));
                    }
                }
            }
        } else if (id == PacketType.TURNUPDATE.ordinal()) {
            out.writeBoolean(turningLeft);
            out.writeBoolean(turningRight);
            out.writeBoolean(turningUp);
            out.writeBoolean(turningDownforWhat);
        } else if (id == PacketType.SENDSPACEPOS.ordinal()) {
            out.writeDouble(this.spacePosition.x);
            out.writeDouble(this.spacePosition.y);
            out.writeDouble(this.spacePosition.z);
            boolean hasWorld = this.spacePosition.world != null;
            boolean hasStar = this.spacePosition.star != null;

            out.writeBoolean(hasWorld);
            if (hasWorld)
                out.writeInt(spacePosition.world.getId());
            out.writeBoolean(hasStar);
            if (hasStar)
                out.writeInt(spacePosition.star.getId());
        } else if (id == PacketType.SET_FLIGHT_MODE.ordinal()) {
            out.writeByte((byte) getFlightMode().ordinal());
        } else if (id == PacketType.FREE_FLIGHT_INPUT.ordinal()) {
            // Client->server send: client writes its current input intent.
            // The current intent on server is the latest applied input, so
            // re-broadcasting it (server->client mirror) is also coherent.
            getCurrentFreeFlightInput().write(out);
        } else if (id == PacketType.SET_FLIGHT_ASSIST.ordinal()) {
            out.writeBoolean(isFlightAssistOn());
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {

        if(id==(byte)9987){
            // F*ck you little bug
        }

        else if (id == PacketType.RECIEVENBT.ordinal()) {
            this.readEntityFromNBT(nbt);
            initFromBounds();
        } else if (id == PacketType.DECONSTRUCT.ordinal()) {
            deconstructRocket();
        } else if (id == PacketType.SENDINTERACT.ordinal()) {
            interact(player);
        } else if (id == PacketType.OPENGUI.ordinal()) { //Used in key handler
            if (player.getRidingEntity() == this) //Prevent cheating
                openGui(player);
        } else if (id == PacketType.REQUESTNBT.ordinal()) {
            if (storage != null) {
                NBTTagCompound nbtdata = new NBTTagCompound();

                this.writeNetworkableNBT(nbtdata);
                PacketHandler.sendToPlayer(new PacketEntity(this, (byte) PacketType.RECIEVENBT.ordinal(), nbtdata), player);

            }
        } else if (id == PacketType.FORCEMOUNT.ordinal()) { //Used for pesky dimension transfers
            //When dimensions are transferred make sure to remount the player on the client
            if (!acceptedPacket) {
                acceptedPacket = true;
                player.setPositionAndRotation(this.posX, this.posY, this.posZ, player.rotationYaw, player.rotationPitch);
                player.startRiding(this);
                MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketDeOrbitingEvent(this));
            }
        } else if (id == PacketType.LAUNCH.ordinal()) {
            if (this.getPassengers().contains(player))
                this.prepareLaunch();
        } else if (id == PacketType.CHANGEWORLD.ordinal()) {
            AdvancedRocketry.proxy.changeClientPlayerWorld(storage.world);
        } else if (id == PacketType.REVERTWORLD.ordinal()) {
            AdvancedRocketry.proxy.changeClientPlayerWorld(this.world);
        } else if (id == PacketType.OPENPLANETSELECTION.ordinal()) {
            player.openGui(LibVulpes.instance, GuiHandler.guiId.MODULARFULLSCREEN.ordinal(), player.world, this.getEntityId(), -1, 0);
        } else if (id == PacketType.SENDPLANETDATA.ordinal()) {
            ItemStack stack = storage.getGuidanceComputer().getStackInSlot(0);
            if (!stack.isEmpty() && stack.getItem() == AdvancedRocketryItems.itemPlanetIdChip) {
                ((ItemPlanetIdentificationChip) AdvancedRocketryItems.itemPlanetIdChip).setDimensionId(stack, nbt.getInteger("selection"));

                //Send data back to sync destination dims
                if (!world.isRemote) {
                    PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, (byte) PacketType.SENDPLANETDATA.ordinal()), this);
                }
            }
        } else if (id == PacketType.DISCONNECTINFRASTRUCTURE.ordinal()) {
            int[] pos = nbt.getIntArray("pos");

            connectedInfrastructure.remove(new HashedBlockPosition(pos[0], pos[1], pos[2]));

            TileEntity tile = world.getTileEntity(new BlockPos(pos[0], pos[1], pos[2]));
            if (tile instanceof IInfrastructure) {
                ((IInfrastructure) tile).unlinkRocket();
                connectedInfrastructure.remove(tile);
            }
        } else if (id == PacketType.ROCKETLANDEVENT.ordinal() && world.isRemote) {
            MinecraftForge.EVENT_BUS.post(new RocketEvent.RocketLandedEvent(this));
        } else if (id == PacketType.DISMOUNTCLIENT.ordinal() && world.isRemote) {
            player.dismountRidingEntity();
            //this.removePassenger(player);
        } else if (id == PacketType.TOGGLE_RCS.ordinal() && !world.isRemote) {
            this.toggleRCS();
        } else if (id == PacketType.TURNUPDATE.ordinal()) {
            this.turningLeft = nbt.getBoolean("left");
            this.turningRight = nbt.getBoolean("right");
            this.turningUp = nbt.getBoolean("up");
            this.turningDownforWhat = nbt.getBoolean("down");
        } else if (id == PacketType.ABORTLAUNCH.ordinal()) {
            this.dataManager.set(LAUNCH_COUNTER, -1);
            releaseDestinationPreload();
        } else if (id == PacketType.SENDSPACEPOS.ordinal()) {
            this.spacePosition.readFromNBT(nbt);
        } else if (id == PacketType.SET_FLIGHT_MODE.ordinal() && !world.isRemote) {
            // Authority: only a passenger of THIS rocket may change its mode,
            // and only while the rocket is not actively in flight (you don't
            // get to switch mid-ascent).
            if (this.isInFlight()) return;
            if (!this.getPassengers().contains(player)) return;
            String name = nbt.getString("flightMode");
            RocketFlightMode mode = RocketFlightMode.DEFAULT;
            for (RocketFlightMode m : RocketFlightMode.values()) {
                if (m.name().equals(name)) { mode = m; break; }
            }
            setFlightMode(mode);
            // Propagate to clients so the UI can react.
            PacketHandler.sendToPlayersTrackingEntity(
                    new PacketEntity(this, (byte) PacketType.SET_FLIGHT_MODE.ordinal()), this);
        } else if (id == PacketType.SET_FLIGHT_MODE.ordinal() && world.isRemote) {
            // Echo from server -> mutate local cache to keep client UI in sync.
            String name = nbt.getString("flightMode");
            for (RocketFlightMode m : RocketFlightMode.values()) {
                if (m.name().equals(name)) { this.flightMode = m; break; }
            }
        } else if (id == PacketType.FREE_FLIGHT_INPUT.ordinal() && !world.isRemote) {
            // Authority: only the active pilot (= a passenger) may push input.
            if (!this.getPassengers().contains(player)) return;
            if (!isFreeFlight()) return; // silent drop — mode mismatch
            FreeFlightInput input = new FreeFlightInput(
                    nbt.getFloat("ffFwd"),
                    nbt.getFloat("ffVert"),
                    nbt.getFloat("ffStrafe"),
                    nbt.getFloat("ffYaw"),
                    nbt.getFloat("ffPitch"),
                    nbt.getFloat("ffRoll"),
                    nbt.getFloat("ffBrake"),
                    nbt.getBoolean("ffCut"));
            applyFreeFlightInput(input);
        } else if (id == PacketType.SET_FLIGHT_ASSIST.ordinal() && !world.isRemote) {
            // Authority: passenger only. Allowed in-flight (unlike SET_FLIGHT_MODE).
            if (!this.getPassengers().contains(player)) return;
            setFlightAssistOn(nbt.getBoolean("flightAssistOn"));
            PacketHandler.sendToPlayersTrackingEntity(
                    new PacketEntity(this, (byte) PacketType.SET_FLIGHT_ASSIST.ordinal()), this);
        } else if (id == PacketType.SET_FLIGHT_ASSIST.ordinal() && world.isRemote) {
            this.flightAssistOn = nbt.getBoolean("flightAssistOn");
        } else if (id == PacketType.ENGINE_START.ordinal() && !world.isRemote) {
            // Engine-start ritual. Authority: a passenger of THIS
            // rocket, FF mode, not already flying. Gate mirrors the classic
            // launch (fuel available AND positive climb authority, TWR > 1) via
            // the shared canStartFreeFlight() check — a craft that can't lift
            // just doesn't start.
            if (!this.getPassengers().contains(player)) return;
            if (!isFreeFlight() || isInFlight()) return;
            if (!canStartFreeFlight()) {
                ffTrace("ENGINE_START rejected: no fuel / no climb authority (TWR <= 1)");
                return;
            }
            ffTrace("ENGINE_START accepted");
            startFreeFlight();
        } else if (id >= STATION_LOC_OFFSET + BUTTON_ID_OFFSET) {
            int id2 = id - (STATION_LOC_OFFSET + BUTTON_ID_OFFSET) - 1;
            setDestLandingPad(id2);

            //propagate change back to the clients
            if (!world.isRemote)
                PacketHandler.sendToPlayersTrackingEntity(new PacketEntity(this, id), this);
        } else if (id > BUTTON_ID_OFFSET) {
            TileEntity tile = storage.getGUITiles().get(id - BUTTON_ID_OFFSET - tilebuttonOffset);

            RocketGuiNavigation.rememberIfRocketGuiReturnTile(player, this, tile);
            //Welcome to super hack time with packets
            //Due to the fact the client uses the player's current world to open the gui, we have to move the client between worlds for a bit
            PacketHandler.sendToPlayer(new PacketEntity(this, (byte) PacketType.CHANGEWORLD.ordinal()), player);
            storage.getBlockState(tile.getPos()).getBlock().onBlockActivated(storage.world, tile.getPos(), storage.getBlockState(tile.getPos()), player, EnumHand.MAIN_HAND, EnumFacing.DOWN, 0, 0, 0);
            PacketHandler.sendToPlayer(new PacketEntity(this, (byte) PacketType.REVERTWORLD.ordinal()), player);
        }
    }

    private void setDestLandingPad(int padIndex) {
        ItemStack slot0 = storage.getGuidanceComputer().getStackInSlot(0);
        int uuid;
        //Station location select
        if (!slot0.isEmpty() && slot0.getItem() instanceof ItemStationChip && (uuid = ItemStationChip.getUUID(slot0)) != 0) {
            ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(uuid);

            if (spaceObject instanceof SpaceStationObject) {

                if (padIndex == -1) {
                    storage.getGuidanceComputer().setLandingLocation(uuid, null);
                } else {

                    StationLandingLocation location = ((SpaceStationObject) spaceObject).getLandingPads().get(padIndex);
                    if (location != null && !location.getOccupied())
                        storage.getGuidanceComputer().setLandingLocation(uuid, location);
                }
            }

            StationLandingLocation location = storage.getGuidanceComputer().getLandingLocation(uuid);
            String noneLabel = LibVulpes.proxy.getLocalizedString("msg.entity.rocket.none");
            landingPadDisplayText.setText(location != null ? location.toString() : noneLabel);
        }
    }

    @Override
    public void updatePassenger(@Nonnull Entity entity) {
        // Free Flight: seat the passenger with the SAME body-frame transform the
        // renderer uses (yaw about world up, pitch about the lateral axis, the
        // +90 nose mapping) so the camera sits IN the seat block and rotates
        // rigidly with the craft — see RendererRocket's FF branch.
        if (isFreeFlight() && isInFlight() && this.storage != null) {
            updateFreeFlightPassenger(entity);
            return;
        }
        //Bind player to the seat
        if (this.storage != null) {
            try {
                HashedBlockPosition seatPos = stats.getPassengerSeat(this.getPassengers().indexOf(entity));
                //Conditional b/c for some reason client/server positions do not match
                float xOffset = this.storage.getSizeX() % 2 == 0 ? 0.5f : 0f;
                float zOffset = this.storage.getSizeZ() % 2 == 0 ? 0.5f : 0f;
                //float halfy = storage.getSizeY() / 2f;
                //float halfx = storage.getSizeX() / 2f;
                //float halfz = storage.getSizeZ() / 2f;

                double xPos = seatPos.x + xOffset;// - halfx+0.5;
                double yPos = seatPos.y - 0.5f;// - 0.5f; // this does not work :(
                double zPos = seatPos.z + zOffset;// - halfz+0.5;
                float angle = (float) (getRCSRotateProgress() * 0.9f * Math.PI / 180f);

                double yNew = (yPos) * MathHelper.cos(angle) + (-zPos - 0.5) * MathHelper.sin(angle);
                double zNew = zPos * MathHelper.cos(angle) + (yPos + 1) * MathHelper.sin(angle);
                yPos = yNew + this.posY;
                zPos = zNew;

                //Now do yaw
                float yawAngle = (float) (this.rotationYaw * Math.PI / 180f);
                double xNew = (xPos) * MathHelper.cos(-yawAngle) + (zPos) * MathHelper.sin(-yawAngle);
                zNew = zPos * MathHelper.cos(yawAngle) + (xPos) * MathHelper.sin(yawAngle);
                xPos = this.posX + xNew;
                zPos = this.posZ + zNew;

                entity.setPosition(xPos, yPos, zPos);
            } catch (IndexOutOfBoundsException e) {
                entity.setPosition(this.posX, this.posY, this.posZ);
            }
        } else
            entity.setPosition(this.posX, this.posY, this.posZ);
    }

    /**
     * Seat a Free-Flight passenger at the seat block, transformed by the exact
     * same body-frame rotation the renderer applies to the model
     * ({@code Ry(-yaw) · Rx(freeFlightPitch + 90)} about the render pivot). The
     * camera then sits IN the cockpit and yaws/pitches rigidly with the craft,
     * instead of hanging at the entity origin while the model rotates away.
     */
    private void updateFreeFlightPassenger(@Nonnull Entity entity) {
        try {
            HashedBlockPosition seatPos = stats.getPassengerSeat(this.getPassengers().indexOf(entity));
            float halfx = storage.getSizeX() / 2f;
            float halfy = storage.getSizeY() / 2f;
            float halfz = storage.getSizeZ() / 2f;

            // Seat block CENTRE in model space (+0.5 on every axis, the same way
            // RendererRocket's display list places a block), re-based onto the
            // render pivot (model centre, vertical origin at +halfy) so this
            // matches the rendered model exactly.
            double cx = (seatPos.x + 0.5) - halfx;
            double cy = (seatPos.y + 0.5) - halfy;
            double cz = (seatPos.z + 0.5) - halfz;

            // Transform the model-space seat offset by the exact render orientation
            // (attitude quaternion ∘ the Rx(90) build offset that maps the
            // vertically-built model's +Y nose onto the body forward axis), so the
            // camera sits IN the cockpit and banks/pitches/loops rigidly with it —
            // pole-safe, unlike the derived Euler.
            double bx = cx;      // model->body via Rx(90): (x, -z, y)
            double by = -cz;
            double bz = cy;
            double[] w = getFfQuat().rotate(bx, by, bz);

            // World seat position (eye height), then drop to the passenger's feet
            // so the camera lands at the seat.
            double seatWorldY = this.posY + halfy + w[1];
            entity.setPosition(this.posX + w[0], seatWorldY - entity.getEyeHeight(), this.posZ + w[2]);
        } catch (IndexOutOfBoundsException e) {
            entity.setPosition(this.posX, this.posY, this.posZ);
        }
    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        List<ModuleBase> modules;
        //If the rocket is flight don't load the interface
        modules = new LinkedList<>();

        if (ID == GuiHandler.guiId.MODULAR.ordinal()) {
            //Backgrounds
            if (world.isRemote) {
                modules.add(new ModuleImage(173, 0, new IconResource(128, 0, 48, 86, CommonResources.genericBackground)));
                modules.add(new ModuleImage(173, 86, new IconResource(98, 0, 78, 83, CommonResources.genericBackground)));
                modules.add(new ModuleImage(173, 168, new IconResource(98, 168, 78, 3, CommonResources.genericBackground)));
            }

            // Worn parts damage view — gated on a service monitor in the rocket.
            if (storage.hasServiceMonitor()) {
                List<ModuleBase> serviceMonitorList = new ArrayList<>();

                int ii = 0;
                for (ItemStack worn : storage.getWornPartDisplayStacks()) {
                    serviceMonitorList.add(new ModuleBrokenPart(1 + (ii % 5) * 18, 1 + (ii / 5) * 18, worn));
                    ii++;
                }

                modules.add(new ModuleContainerPanYOnly(8 + 80, 17, serviceMonitorList, new ArrayList<>(), null, 50, 45));
                modules.add(new ModuleText(80, 5, LibVulpes.proxy.getLocalizedString("msg.serviceStation.destroyProb")
                        + ": " + (int)(this.storage.getBreakingProbability() * 100) + "%", 0x000000));
            }

            //TODO DEBUG tiles!
            //Render TEs in a pan-able list y-axis only
            List<TileEntity> tiles = storage.getGUITiles();
            List<ModuleBase> panModules = new ArrayList<>();
            for (int i = 0; i < tiles.size(); i++) {
                TileEntity tile = tiles.get(i);
                IBlockState state = storage.getBlockState(tile.getPos());
                try {
                    Block block = state.getBlock();
                    ItemStack display = new ItemStack(block, 1, block.damageDropped(state));

                    if (!display.isEmpty()) {
                        panModules.add(new ModuleSlotButton(
                                1 + 18 * (i % 4),
                                1 + 18 * (i / 4),
                                i + tilebuttonOffset,
                                this,
                                display,
                                world
                        ));
                    }
                } catch (NullPointerException e) {

                }
            }
            modules.add(new ModuleContainerPanYOnly(8, 17, panModules, new LinkedList<>(), null, 65, 45, 0, 0));

            //Fuel
            modules.add(new ModuleProgress(192, 7, 0, new ProgressBarImage(2, 173, 12, 71, 17, 6, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud), this));
            // Conditional oxidizer bar
            if (shouldShowOxBar()) {
                // Add a second, distinct bar for oxidizer (reuse the monitoring station’s UVs)
                modules.add(new ModuleProgress(
                    198, 7, 6, // position offset to avoid overlap; ID=6 matches monitoring station semantics
                    new ProgressBarImage(2, 173, 12, 71, 17, 75, 3, 69, 1, 1, EnumFacing.UP, TextureResources.rocketHud),
                    this
                ));
            }


            //Add buttons
            modules.add(new ModuleButton(180, 140, 0, LibVulpes.proxy.getLocalizedString("msg.entity.rocket.disass"), this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));

            //modules.add(new ModuleButton(180, 95, 1, "", this, TextureResources.buttonLeft, 10, 16));
            //modules.add(new ModuleButton(202, 95, 2, "", this, TextureResources.buttonRight, 10, 16));

            modules.add(new ModuleButton(180, 114, 1, LibVulpes.proxy.getLocalizedString("msg.entity.rocket.seldst"), this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 64, 20));
            //modules.add(new ModuleText(180, 114, "Inventories", 0x404040));
        } else {
            ItemStack slot0 = storage.getGuidanceComputer() != null ? storage.getGuidanceComputer().getStackInSlot(0) : ItemStack.EMPTY;
            int uuid;
            //Station location select
            if (!slot0.isEmpty() && slot0.getItem() instanceof ItemStationChip && (uuid = ItemStationChip.getUUID(slot0)) != 0) {
                ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(uuid);

                modules.add(new ModuleStellarBackground(0, 0, zmaster587.libVulpes.inventory.TextureResources.starryBG));
                //modules.add(new ModuleImage(0, 0, icon));

                if (spaceObject == null)
                    return modules;

                List<ModuleBase> list2 = new LinkedList<>();
                ModuleButton button = new ModuleButton(0, 0, STATION_LOC_OFFSET, LibVulpes.proxy.getLocalizedString("msg.entity.rocket.clear"), this, TextureResources.buttonGeneric, 72, 18);
                list2.add(button);

                int i = 1;
                for (StationLandingLocation pos : ((SpaceStationObject) spaceObject).getLandingPads()) {
                    button = new ModuleButton(0, i * 18, i + STATION_LOC_OFFSET, pos.toString(), this, TextureResources.buttonGeneric, 72, 18);
                    list2.add(button);

                    if (pos.getOccupied())
                        button.setColor(0xFF0000);

                    i++;
                }

                ModuleContainerPan pan = new ModuleContainerPan(25, 25, list2, new LinkedList<>(), null, 256, 256, 0, -48, 258, 256);
                modules.add(pan);

                StationLandingLocation location = storage.getGuidanceComputer().getLandingLocation(uuid);

                landingPadDisplayText.setText(location != null ? location.toString() : LibVulpes.proxy.getLocalizedString("msg.entity.rocket.none"));
                modules.add(landingPadDisplayText);
            } else {
                DimensionProperties properties = DimensionManager.getEffectiveDimId(world, this.getPosition());
                while (properties.getParentProperties() != null) properties = properties.getParentProperties();

                if (stats.isNuclear())
                    container = new ModulePlanetSelector(
                            properties.getStarId(),
                            zmaster587.libVulpes.inventory.TextureResources.starryBG,
                            this,                       // selection notify
                            planetSelectorProgress,     // progress source
                            this,                       // planet definer
                            true
                    );
                else
                    container = new ModulePlanetSelector(
                            properties.getId(),
                            zmaster587.libVulpes.inventory.TextureResources.starryBG,
                            this,                       // selection notify
                            planetSelectorProgress,     // progress source
                            false
                    );

                container.setOffset(1000, 1000);
                modules.add(container);
            }
        }
        return modules;
    }


    @Override
    public String getModularInventoryName() {
        return "";
    }

    @Override
    public float getNormallizedProgress(int id) {
        FuelType fuelType = getRocketFuelType();

        if (id == 0 && fuelType != null) {
            switch (fuelType) {
                case LIQUID_BIPROPELLANT:
                case LIQUID_MONOPROPELLANT:
                case NUCLEAR_WORKING_FLUID:
                    int amt = getFuelAmount(fuelType);
                    int cap = getFuelCapacity(fuelType);
                    return (cap > 0) ? (amt / (float) cap) : 0f;
            }
        }

        // oxidizer bar matches monitoring station’s ID=6 semantics
        if (id == 6) {
            int oxAmt = getFuelAmount(FuelType.LIQUID_OXIDIZER);
            int oxCap = getFuelCapacity(FuelType.LIQUID_OXIDIZER);
            return (oxCap > 0) ? (oxAmt / (float) oxCap) : 0f;
        }

        return 0f;
    }


    public double getRelativeHeightFraction() {
        return (posY - getTopBlock(getPosition()).getY()) / (getEntryHeight(dimension) - getTopBlock(getPosition()).getY());
    }

    public double getPreviousRelativeHeightFraction() {
        return (prevPosY - getTopBlock(getPosition()).getY()) / (getEntryHeight(dimension) - getTopBlock(getPosition()).getY());
    }

    @Override
    public void setProgress(int id, int progress) {

    }

    @Override
    public int getProgress(int id) {
        if (id == 0) {
            FuelType ft = getRocketFuelType();
            return (ft != null) ? getFuelAmount(ft) : 0;
        } else if (id == 6) {
            return getFuelAmount(FuelType.LIQUID_OXIDIZER);
        }
        return 0;
    }

    @Override
    public int getTotalProgress(int id) {
        if (id == 0) {
            FuelType ft = getRocketFuelType();
            return (ft != null) ? getFuelCapacity(ft) : 1; // never 0
        } else if (id == 6) {
            int cap = getFuelCapacity(FuelType.LIQUID_OXIDIZER);
            return (cap > 0) ? cap : 1; // never 0
        }
        return 1;
    }


    @Override
    public void setTotalProgress(int id, int progress) {
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void onInventoryButtonPressed(int buttonId) {
        switch (buttonId) {
            case 0:
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.DECONSTRUCT.ordinal()));
                break;
            case 1:
                PacketHandler.sendToServer(new PacketEntity(this, (byte) EntityRocket.PacketType.OPENPLANETSELECTION.ordinal()));
                break;
            default:
                if (buttonId < STATION_LOC_OFFSET) {
                    TileEntity tile = storage.getGUITiles().get(buttonId - tilebuttonOffset);

                    PacketHandler.sendToServer(new PacketEntity(this, (byte) (buttonId + BUTTON_ID_OFFSET)));

                    storage.getBlockState(tile.getPos()).getBlock().onBlockActivated(
                            storage.world,
                            tile.getPos(),
                            storage.getBlockState(tile.getPos()),
                            Minecraft.getMinecraft().player,
                            EnumHand.MAIN_HAND,
                            EnumFacing.DOWN,
                            0,
                            0,
                            0
                    );
                } else {
                    PacketHandler.sendToServer(new PacketEntity(this, (byte) (buttonId + BUTTON_ID_OFFSET)));
                }
        }
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        boolean ret = !this.isDead && this.getDistance(entity) < 64;
        if (!ret)
            RocketInventoryHelper.removePlayerFromInventoryBypass(entity);

        RocketInventoryHelper.updateTime(entity, world.getWorldTime());

        return ret;
    }

    @Override
    public StatsRocket getRocketStats() {
        return stats;
    }


    @Override
    public void onSelectionConfirmed(Object sender) {
        PacketHandler.sendToServer(new PacketEntity(this, (byte) PacketType.SENDPLANETDATA.ordinal()));
    }



    public LinkedList<IInfrastructure> getConnectedInfrastructure() {
        return connectedInfrastructure;
    }

    @Override
    public boolean isPlanetKnown(IDimensionProperties properties) {
        return !ARConfiguration.getCurrentConfig().planetsMustBeDiscovered || DimensionManager.getInstance().isPlanetKnown(properties.getId());
    }

    @Override
    public boolean isStarKnown(StellarBody body) {
        return true;
    }




    public enum PacketType {
        RECIEVENBT,
        SENDINTERACT,
        REQUESTNBT,
        FORCEMOUNT,
        LAUNCH,
        DECONSTRUCT,
        OPENGUI,
        CHANGEWORLD,
        REVERTWORLD,
        OPENPLANETSELECTION,
        SENDPLANETDATA,
        DISCONNECTINFRASTRUCTURE,
        CONNECTINFRASTRUCTURE,
        ROCKETLANDEVENT,
        MENU_CHANGE,
        UPDATE_ATM,
        UPDATE_ORBIT,
        UPDATE_FLIGHT,
        DISMOUNTCLIENT,
        TOGGLE_RCS,
        TURNUPDATE,
        ABORTLAUNCH,
        SENDSPACEPOS,
        // Free Flight Mode (TASK: feature/true_rcs) — APPEND-ONLY so wire IDs stay stable.
        SET_FLIGHT_MODE,
        FREE_FLIGHT_INPUT,
        SET_FLIGHT_ASSIST,
        /** Client&rarr;server: the pilot completed the 3 s engine-start hold. No payload. */
        ENGINE_START
    }

    /**
     * Legacy Free-Flight backend: owns the entity transform exactly as the inline
     * FF v2 code did before {@link IFlightBackend} was introduced — commit
     * the attitude, derive the legacy Euler view, replicate the FF_Q and
     * engine-power data, write {@code motion*} and displace via {@code Entity.move()}. Used when no
     * ship-physics backend is present; {@link #ownsTransform()} is {@code false},
     * so Free Flight keeps running its own transform, replication and client
     * dead-reckoning.
     *
     * <p>Bound to its enclosing {@link EntityRocket} (a non-static inner class), so
     * it applies to {@code EntityRocket.this} directly — the interface no longer
     * passes the craft, so a ship-physics backend can bind to a ship handle instead.</p>
     */
    private final class LegacyFlightBackend implements IFlightBackend {
        @Override
        public void applyFlightState(FreeFlightPhysics.Quat attitude,
                                     FreeFlightPhysics.Step step,
                                     float enginePower) {
            // Commit the attitude; snapshot prev for render/camera slerp. Derive the
            // Euler view for legacy consumers (seat, HUD bars, probes, vanilla
            // rotationYaw/Pitch readers), and replicate the quaternion. The client
            // reads FF_Q* directly, so the old yaw/pitch tracker drift-resync
            // teleport is gone — the full attitude is authoritative every tick.
            prevFfQuat = ffQuat;
            ffQuat = attitude;
            float[] e = FreeFlightPhysics.eulerFromQuat(attitude);
            prevRotationYaw = rotationYaw;
            prevRotationPitch = rotationPitch;
            prevFreeFlightRoll = freeFlightRoll;
            rotationYaw     = e[0];
            rotationPitch   = e[1];
            freeFlightPitch = e[1];
            freeFlightRoll  = e[2];
            dataManager.set(FF_QW, (float) attitude.w);
            dataManager.set(FF_QX, (float) attitude.x);
            dataManager.set(FF_QY, (float) attitude.y);
            dataManager.set(FF_QZ, (float) attitude.z);
            dataManager.set(FF_ENGINE_POWER, enginePower);

            motionX = step.motionX;
            motionY = step.motionY;
            motionZ = step.motionZ;

            // Apply motion to the world.
            move(MoverType.SELF, motionX, motionY, motionZ);
        }

        @Override
        public boolean ownsTransform() {
            return false;
        }
    }
}
