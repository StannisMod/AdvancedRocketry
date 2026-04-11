package zmaster587.advancedRocketry.tile.multiblock.orbitallaserdrill;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.util.TerraformingHelper;
import zmaster587.advancedRocketry.world.provider.WorldProviderSpace;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.api.LibVulpesBlocks;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.multiblock.TileMultiPowerConsumer;
import zmaster587.libVulpes.util.MultiInventory;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;
import java.util.LinkedList;
import java.util.List;

public class TileOrbitalLaserDrill extends TileMultiPowerConsumer implements IGuiCallback, IButtonInventory {

    private static final int POWER_PER_OPERATION = (int) (10000 * ARConfiguration.getCurrentConfig().spaceLaserPowerMult);
    private AbstractDrill drill;
    private terraformingdrill terraformingDrill;
    private AbstractDrill miningDrill;
    public int laserX, laserZ, tickSinceLastOperation;
    protected boolean isRunning, finished, isJammed;
    private ModuleButton resetBtn;
    Object[][][] structure = new Object[][][]{
            {
                    {null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null},
                    {null, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null, null, null},
                    {LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null, null},
                    {null, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null},
                    {null, null, null, null, null, null, null, null, null, null, null}
            },
            {
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockStructureBlock, null, AdvancedRocketryBlocks.blockLens, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockLens, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockStructureBlock, null, AdvancedRocketryBlocks.blockLens, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null}
            },
            {
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {'O', 'c', 'O', null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null, AdvancedRocketryBlocks.blockLens, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, AdvancedRocketryBlocks.blockLens, LibVulpesBlocks.blockAdvStructureBlock, null, null, null, null, null, null},
                    {LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, LibVulpesBlocks.blockStructureBlock, null, AdvancedRocketryBlocks.blockLens, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockLens, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, 'P'},
                    {null, null, null, null, null, null, LibVulpesBlocks.blockAdvStructureBlock, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, AdvancedRocketryBlocks.blockVacuumLaser, null}
            },
    };
    public int radius, xCenter, yCenter, numSteps;
    private EnumFacing prevDir;
    private ModuleTextBox locationX, locationZ;
    private ModuleText updateText, positionText, xtext, ztext, no_targets_text;
    private MultiInventory inv;
    private MODE mode;

    private boolean terraformingstatus;
    boolean client_first_loop = true; // for render bug on client
    //private Ticket ticket; // this is useless anyway because it would not load the energy supply system and the laser would run out of energy
    
    // Performance tweaks
    private int lastTfDim = Integer.MIN_VALUE;
    private boolean voidCobble;
    private ModuleButton voidCobbleBtn;
    int last_orbit_dim;
    private final boolean voidMiningMode;

    TerraformingHelper t;
    WorldServer orbitWorld;


    public TileOrbitalLaserDrill() {
        super();

        this.voidMiningMode = !ARConfiguration.getCurrentConfig().laserDrillPlanet;

        terraformingstatus = false;
        client_first_loop = true;

        radius = 0;
        xCenter = 0;
        yCenter = 0;
        numSteps = 0;
        prevDir = null;

        resetBtn = new ModuleButton(
                40, 20, 2,
                LibVulpes.proxy.getLocalizedString("msg.spacelaser.reset"),
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                34, 20
        );

        // Only meaningful in void-mining mode (from config)
        voidCobbleBtn = new ModuleButton(
                50, 60,
                3,     // buttonId
                LibVulpes.proxy.getLocalizedString("msg.spacelaser.voidcobble"),
                this,
                zmaster587.libVulpes.inventory.TextureResources.buttonBuild,
                85, 20
        );

        positionText = new ModuleText(83, 63, "empty... shit!", 0x0b0b0b);
        updateText = new ModuleText(83, 63, "also empty...", 0x0b0b0b);
        xtext = new ModuleText(83, 33, "X:", 0x0b0b0b);
        ztext = new ModuleText(83, 43, "Z:", 0x0b0b0b);
        String ntLine1 = LibVulpes.proxy.getLocalizedString("msg.spacelaser.notarget1");
        String ntLine2 = LibVulpes.proxy.getLocalizedString("msg.spacelaser.notarget2");
        String ntText  = ntLine1 + "\n" + ntLine2;
        no_targets_text = new ModuleText(21, 43, ntText, 0x0b0b0b);
        locationX = new ModuleNumericTextbox(this, 93, 31, 50, 10, 16);
        locationZ = new ModuleNumericTextbox(this, 93, 41, 50, 10, 16);
        tickSinceLastOperation = 0;
        laserX = 0;
        laserZ = 0;
        inv = new MultiInventory(this.itemOutPorts);

        if (ARConfiguration.getCurrentConfig().laserDrillPlanet)
            this.miningDrill = new MiningDrill();
        else
            this.miningDrill = new VoidDrill();

        this.terraformingDrill = new terraformingdrill();
        this.drill = miningDrill;

        isRunning = false;
        finished = false;
        isJammed = false;
        mode = MODE.SINGLE;

        // If we ever need to set initial voidCobble from config, do it here
        updateVoidCobbleButtonVisuals();
    }



    @Override
    public Object[][][] getStructure() {
        return structure;
    }

    //Required so we see the laser
    @SideOnly(Side.CLIENT)
    @Override
    @Nonnull
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(this.pos.getX() - 5, this.pos.getY() - 1000, this.pos.getZ() - 5, this.pos.getX() + 5, this.pos.getY() + 50, this.pos.getZ() + 5);
    }

    @Override
    public boolean shouldHideBlock(World world, BlockPos pos, IBlockState tile) {
        return true;
    }

    @Override
    public String getMachineName() {
        return AdvancedRocketryBlocks.blockSpaceLaser.getLocalizedName();
    }

    /*
     * ID 2: sync whether the machine is running
     * ID 4: reset
     * ID 1: send changes back to client
     * ID 5 send only laser position to client
     * ID 3: client requests update
     */
    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {
        super.writeDataToNetwork(out, id);
        if (id == 15) {
            out.writeInt(this.laserX);
            out.writeInt(this.laserZ);
        }
        else if (id == 11){
            out.writeInt(mode.ordinal());
            out.writeInt(this.xCenter);
            out.writeInt(this.yCenter);
            out.writeInt(this.laserX);
            out.writeInt(this.laserZ);
            out.writeBoolean(this.isRunning);
            out.writeBoolean(terraformingstatus);
            out.writeBoolean(voidCobble);
        }
        else if (id == 12) {
            out.writeBoolean(isRunning);
        }
        else if (id == 14){
            out.writeInt(mode.ordinal());
            out.writeInt(this.xCenter);
            out.writeInt(this.yCenter);
        }else if (id == 16){
            out.writeBoolean(terraformingstatus);
        }
    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte id,
                                    NBTTagCompound nbt) {
        super.readDataFromNetwork(in, id, nbt);
        if (id == 15){
            nbt.setInteger("currentX", in.readInt());
            nbt.setInteger("currentZ", in.readInt());
        }
        else if (id == 11){
            nbt.setInteger("mode", in.readInt());
            nbt.setInteger("newX", in.readInt());
            nbt.setInteger("newZ", in.readInt());
            nbt.setInteger("currentX", in.readInt());
            nbt.setInteger("currentZ", in.readInt());
            nbt.setBoolean("isRunning", in.readBoolean());
            nbt.setBoolean("terraformingstatus", in.readBoolean());
            nbt.setBoolean("voidCobble", in.readBoolean());
        }
        else if (id == 12) {
            nbt.setBoolean("isRunning", in.readBoolean());
        }
        else if (id == 14){
            nbt.setInteger("mode", in.readInt());
            nbt.setInteger("newX", in.readInt());
            nbt.setInteger("newZ", in.readInt());
        }else if (id == 16){
            nbt.setBoolean("terraformingstatus", in.readBoolean());
        }

    }

    public void client_update_tf_info(){
        if (!terraformingstatus && this.mode == MODE.T_FORM){
            this.no_targets_text.setVisible(true);
        }else{
            this.no_targets_text.setVisible(false);
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                            NBTTagCompound nbt) {

        super.useNetworkData(player, side, id, nbt);

        if (id == 15) {
            laserZ = nbt.getInteger("currentZ");
            laserX = nbt.getInteger("currentX");
            positionText.setText("position:\n" + this.laserX + " : " + this.laserZ);
        } else if (id == 11) {
            resetSpiral();
            this.isRunning = nbt.getBoolean("isRunning");
            mode = MODE.values()[nbt.getInteger("mode")];
            if (voidMiningMode && mode != MODE.SINGLE) {
                mode = MODE.SINGLE;
            }            
            xCenter = nbt.getInteger("newX");
            yCenter = nbt.getInteger("newZ");
            laserZ = nbt.getInteger("currentZ");
            laserX = nbt.getInteger("currentX");
            positionText.setText("position:\n" + this.laserX + " : " + this.laserZ);
            updateText.setText(this.getMode().toString());
            locationX.setText(String.valueOf(this.xCenter));
            locationZ.setText(String.valueOf(this.yCenter));
            resetBtn.setColor(0xf0f0f0);
            check_is_terraforming_update_gui();

            this.terraformingstatus = nbt.getBoolean("terraformingstatus");
            this.voidCobble = nbt.getBoolean("voidCobble");
            client_update_tf_info();


            if (voidCobbleBtn != null) {
                updateVoidCobbleButtonVisuals();
            }

        } else if (id == 12) {
            this.isRunning = nbt.getBoolean("isRunning");
        } else if (id == 16) {
            this.terraformingstatus = nbt.getBoolean("terraformingstatus");
            client_update_tf_info();
        } else if (id == 14) {
            resetSpiral();
            mode = MODE.values()[nbt.getInteger("mode")];
            xCenter = nbt.getInteger("newX");
            yCenter = nbt.getInteger("newZ");
            laserZ = yCenter;
            laserX = xCenter;

            if (drill != null) {
                drill.deactivate();
            }
            finished = false;
            setRunning(false);

            if (mode == MODE.T_FORM) {
                this.drill = this.terraformingDrill;
            } else {
                this.drill = this.miningDrill;
            }

            checkjam();
            checkCanRun();
            PacketHandler.sendToNearby(new PacketMachine(this, (byte) 11),
                    this.world.provider.getDimension(), pos, 2048);

        } else if (id == 13) {
            PacketHandler.sendToNearby(new PacketMachine(this, (byte) 11),
                    this.world.provider.getDimension(), pos, 2048);

        } else if (id == 17) {
            // **IMPORTANT**: only act on server
            if (!world.isRemote) {
                this.voidCobble = !this.voidCobble;
                if (miningDrill instanceof VoidDrill) {
                    ((VoidDrill) miningDrill).setVoidCobble(voidCobble);
                }
                // push new state to clients
                PacketHandler.sendToNearby(new PacketMachine(this, (byte) 11),
                        this.world.provider.getDimension(), pos, 2048);
                markDirty();
            }
        }

        markDirty();
    }


    public void transferItems(IInventory inventorySource, IItemHandler inventoryTarget) {
        for (int i = 0; i < inventorySource.getSizeInventory(); i++) {
            ItemStack stack = inventorySource.getStackInSlot(i).copy();

            if (!stack.isEmpty()) {
                for (int j = 0; j < inventoryTarget.getSlots(); j++) {
                    // Try to insert the item
                    ItemStack remaining = inventoryTarget.insertItem(j, stack, false);

                    // If the entire stack was inserted, remove it from the source inventory
                    if (remaining.isEmpty()) {
                        inventorySource.decrStackSize(i, stack.getCount());
                        break;
                    }

                    // Otherwise, update the stack to the remaining amount
                    stack.setCount(remaining.getCount());
                }
            }
        }
    }




    public void outputItems() {
        // Loop over each output hatch in your inventory
        for (int ic = 0; ic < this.getItemOutPorts().size(); ic++) {

            IInventory inventory = itemOutPorts.get(ic);
            if (inventory instanceof TileEntity) {


                for (EnumFacing direction : EnumFacing.values()) {
                    TileEntity tileEntity = world.getTileEntity(((TileEntity) inventory).getPos().offset(direction));
                    if (tileEntity != null) {
                        if (tileEntity.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,null)){
                            transferItems(inventory, tileEntity.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY,null));
                        }
                    }
                }
            }
        }
    }
    private void resetSpiral() {
        radius = 0;
        prevDir = null;
        xCenter = 0;
        yCenter = 0;
        numSteps = 0;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean value) {
        if (world.isRemote){
            System.out.println("client should not call setRunning");
            return;
        }
        isRunning = value;
        // this needs to be sent to all to update players on ground of change
        // or you make 2 packages, one for space dim and one for ground dim at laser coords but this is more easy this way...
        PacketHandler.sendToAll(new PacketMachine(this, (byte) 12));
        //PacketHandler.sendToNearby(new PacketMachine(this, (byte) 12), this.world.provider.getDimension(), pos, 128);

        markDirty();
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean value) {
        finished = value;
    }

    public MODE getMode() {
        return mode;
    }

    public void setMode(MODE m) {
        mode = m;
    }

    public void incrementMode() {
        int num = mode.ordinal();
        num++;

        if (num >= MODE.values().length)
            num = 0;

        mode = MODE.values()[num];
    }

    public void decrementMode() {
        int num = mode.ordinal();
        num--;

        if (num < 0)
            num = MODE.values().length - 1;

        mode = MODE.values()[num];
    }

    @Override
    public void update() {


        //Freaky janky crap to make sure the multiblock loads on chunkload etc
        if (world.isRemote && client_first_loop) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 13));
            client_first_loop = false;
        }
        if (timeAlive == 0 && !world.isRemote) {
            if (isComplete())
                canRender = completeStructure = completeStructure(world.getBlockState(pos));
            timeAlive = 0x1;
            checkCanRun();

        }

        if (!this.world.isRemote) {
            tickSinceLastOperation++;


            if (mode != MODE.T_FORM && isJammed) {
                checkjam();
            }
            checkCanRun();
            if (this.hasPowerForOperation() && this.isReadyForOperation() && this.isRunning) {

                if (this.drill.needsRestart()) {
                    this.setRunning(false);
                    return;
                }

                ItemStack[] stacks = this.drill.performOperation();

                if (stacks != null) { // is null during terraforming
                    ZUtils.mergeInventory(stacks, this.inv);

                    if (!ZUtils.isInvEmpty(stacks)) {
                        //TODO: drop extra items
                        this.drill.deactivate();
                        this.isJammed = true;
                    }
                }

                this.batteries.setEnergyStored(this.batteries.getUniversalEnergyStored() - POWER_PER_OPERATION);
                this.tickSinceLastOperation = 0;
            }

            if (!this.inv.isEmpty()) {
                outputItems();
            }


            if (this.drill.isFinished()) {
                setRunning(false);
                this.drill.deactivate();

                if (!this.isJammed) {
                    if (this.mode == MODE.SINGLE)
                        this.finished = true;

                    if (this.world.getStrongPower(getPos()) != 0) {
                        if (this.mode == MODE.SPIRAL) {
                            this.numSteps++;
                            if (this.radius < this.numSteps) {
                                this.numSteps = 0;
                                if (prevDir == EnumFacing.NORTH)
                                    prevDir = EnumFacing.EAST;
                                else if (prevDir == EnumFacing.EAST) {
                                    prevDir = EnumFacing.SOUTH;
                                    radius++;
                                } else if (prevDir == EnumFacing.SOUTH)
                                    prevDir = EnumFacing.WEST;
                                else {
                                    prevDir = EnumFacing.NORTH;
                                    radius++;
                                }
                            }

                            this.laserX += 3 * prevDir.getFrontOffsetX();
                            this.laserZ += 3 * prevDir.getFrontOffsetZ();
                            PacketHandler.sendToNearby(new PacketMachine(this, (byte) 15), this.world.provider.getDimension(), pos, 128);

                        }
                    }
                }
            }
        }
    }

    public boolean isReadyForOperation() {

        if (mode == MODE.T_FORM) return true; // every tick on terraforming

        if (batteries.getUniversalEnergyStored() == 0)
            return false;

        return tickSinceLastOperation > (3 * this.batteries.getMaxEnergyStored() / (float) this.batteries.getUniversalEnergyStored());
    }

    public void onDestroy() {
        if (this.drill != null) {
            this.drill.deactivate();
        }
        clearVoidDrillCache();
        orbitWorld = null;
        t = null;
        last_orbit_dim = 0;
        lastTfDim = Integer.MIN_VALUE;
    }

    @Override
    public void onChunkUnload() {
        if (this.drill != null) {
            this.drill.deactivate();
        }
        clearVoidDrillCache();
        isRunning = false;
        orbitWorld = null;
        t = null;
        last_orbit_dim = 0;
        lastTfDim = Integer.MIN_VALUE;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = new NBTTagCompound();
        this.writeToNBT(nbt);
        return new SPacketUpdateTileEntity(getPos(), 0, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);


        nbt.setBoolean("voidCobble", voidCobble);
        nbt.setInteger("laserX", laserX);
        nbt.setInteger("laserZ", laserZ);
        nbt.setByte("mode", (byte) mode.ordinal());
        nbt.setBoolean("jammed", this.isJammed);

        nbt.setInteger("CenterX", xCenter);
        nbt.setInteger("CenterY", yCenter);

        if (mode == MODE.SPIRAL && prevDir != null) {
            nbt.setInteger("radius", radius);
            nbt.setInteger("numSteps", numSteps);
            nbt.setInteger("prevDir", prevDir.ordinal());
        }
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);


        laserX = nbt.getInteger("laserX");
        laserZ = nbt.getInteger("laserZ");
        mode = MODE.values()[nbt.getByte("mode")];
        // If config says we are in void-mining mode, force SINGLE
        if (voidMiningMode && mode != MODE.SINGLE) {
            mode = MODE.SINGLE;
        }
        this.isJammed = nbt.getBoolean("jammed");

        xCenter = nbt.getInteger("CenterX");
        yCenter = nbt.getInteger("CenterY");

        if (mode == MODE.SPIRAL && nbt.hasKey("prevDir")) {
            radius = nbt.getInteger("radius");
            numSteps = nbt.getInteger("numSteps");
            prevDir = EnumFacing.values()[nbt.getInteger("prevDir")];
        }

        if (mode == MODE.T_FORM){
            this.drill = this.terraformingDrill;
        }else {
            this.drill = this.miningDrill;
        }
        voidCobble = nbt.getBoolean("voidCobble");
        if (miningDrill instanceof VoidDrill) {
            ((VoidDrill) miningDrill).setVoidCobble(voidCobble);
        }
    }        
    
    /**
     * Take items from internal inventory
     */
    public void checkjam() {
        // Only called when isJammed == true
        if (this.one_hatch_empty()) {
            this.isJammed = false;
        }
    }

    private boolean one_hatch_empty() {
        for (int i = 1; i < this.inv.getSizeInventory(); ++i) {
            if (this.inv.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean canMachineSeeEarth() {
        return true;
    }

    private boolean unableToRun() {
        return !canMachineSeeEarth() ||
                batteries.getUniversalEnergyStored() == 0 ||
                !(this.world.provider instanceof WorldProviderSpace) ||
                !zmaster587.advancedRocketry.dimension.DimensionManager.getInstance().canTravelTo(((WorldProviderSpace) this.world.provider).getDimensionProperties(getPos()).getParentPlanet()) ||

                (mode != MODE.T_FORM && ARConfiguration.getCurrentConfig().laserBlackListDims.contains(((WorldProviderSpace) this.world.provider).getDimensionProperties(getPos()).getParentPlanet()));
    }

    /**
     * Checks to see if the situation for firing the laser exists... and changes the state accordingly
     */



    public void checkCanRun() {
        if (world.isRemote) return;

        // Read redstone once and reuse it
        final int redstonePower = world.isBlockIndirectlyGettingPowered(getPos());

        // Fast path for void-mining: if there is no redstone, don't even bother
        // with space station / dimension logic.
        if (voidMiningMode && redstonePower == 0) {
            if (isRunning) {
                drill.deactivate();
                setRunning(false);
            }
            return;
        }

        ISpaceObject spaceObject =  SpaceObjectManager.getSpaceManager().getSpaceStationFromBlockCoords(this.pos);
        if(spaceObject == null){
            if (isRunning) {
                drill.deactivate();
                setRunning(false);
            }
            return;
        }
        int orbitDimId = spaceObject.getOrbitingPlanetId();

        // Ensure orbitWorld exists when you might activate a drill
        if (!voidMiningMode && (orbitDimId != last_orbit_dim || orbitWorld == null)) {
            last_orbit_dim = orbitDimId;

            if (!DimensionManager.isDimensionRegistered(orbitDimId)) {
                if (isRunning) { drill.deactivate(); setRunning(false); }
                return;
            }

            orbitWorld = DimensionManager.getWorld(orbitDimId);
            if (orbitWorld == null) {
                DimensionManager.initDimension(orbitDimId);
                orbitWorld = DimensionManager.getWorld(orbitDimId);
                if (orbitWorld == null) {
                    if (isRunning) { drill.deactivate(); setRunning(false); }
                    return;
                }
            }
        }
        if (voidMiningMode) {
            if (terraformingstatus) {
                terraformingstatus = false;
                PacketHandler.sendToAll(new PacketMachine(this, (byte) 16));
            }

            if (miningDrill instanceof VoidDrill) {
                ((VoidDrill) miningDrill).setSourceDimId(orbitDimId);
            }
            if (this.finished
                    || this.isJammed
                    || redstonePower == 0
                    || unableToRun()) {
                if (isRunning) {
                    drill.deactivate();
                    setRunning(false);
                }
            } else if (orbitDimId != SpaceObjectManager.WARPDIMID && !isRunning) {
                // No getWorld()/initDimension() here on purpose.
                setRunning(drill.activate(null, laserX, laserZ));
            }

            return;
        }

        if (mode == MODE.T_FORM) {
            if (t == null || lastTfDim != orbitDimId) {
                t = terraformingDrill.get_my_helper(orbitWorld);
                lastTfDim = orbitDimId;
            }

            boolean hasQueue = t != null && t.has_blocks_in_tf_queue();
            if (terraformingstatus != hasQueue) {
                terraformingstatus = hasQueue;
                PacketHandler.sendToAll(new PacketMachine(this, (byte) 16));
            }
        } else {
            if (terraformingstatus) {
                terraformingstatus = false;
                PacketHandler.sendToAll(new PacketMachine(this, (byte) 16));
            }
        }



        //Laser  redstone power, not be jammed, and be in orbit and energy to function
        if ((mode == MODE.T_FORM && (t==null ||!t.has_blocks_in_tf_queue())) || this.finished || (this.isJammed && mode != MODE.T_FORM) || redstonePower == 0 || unableToRun()) {
            if (isRunning) {
                drill.deactivate();
                setRunning(false);
            }
        } else if (redstonePower > 0) {


            if (orbitDimId == SpaceObjectManager.WARPDIMID)
                return;




            //Laser will be on at this point
            if (!isRunning) {

                // load dimension i guess
                orbitWorld = DimensionManager.getWorld(orbitDimId);
                if (orbitWorld == null) {
                    DimensionManager.initDimension(orbitDimId);
                    orbitWorld = DimensionManager.getWorld(orbitDimId);
                    if (orbitWorld == null)
                        return;
                }

                setRunning(drill.activate(orbitWorld, laserX, laserZ));
            }
        }


    }

    public int getEnergyPercentScaled(int max) {
        return (int) (max * (batteries.getUniversalEnergyStored() / (float) batteries.getMaxEnergyStored()));
    }

    public boolean hasEnergy() {
        return batteries.getUniversalEnergyStored() != 0;
    }


    /**
     * @return returns whether enough power is stored for the next operation
     */
    public boolean hasPowerForOperation() {
        return POWER_PER_OPERATION <= batteries.getUniversalEnergyStored();
    }
    //InventoryHandling end

    //Redstone Flux start

    /**
     * DO NOT USE UNLESS YOU HAVE NO OTHER OPTION!!!
     *
     * @param amt amount to set energy to
     */
    public void setEnergy(int amt) {
        batteries.setEnergyStored(amt);
    }

    public boolean isJammed() {
        return this.isJammed;
    }

    //Redstone Flux end

    public void setJammed(boolean b) {
        this.isJammed = b;
    }
    @Override
    public void onModuleUpdated(ModuleBase module) {
        resetBtn.setColor(0x90ff90);
        if (module == locationX) {
            if (!((ModuleTextBox) module).getText().isEmpty() && !((ModuleTextBox) module).getText().contentEquals("-"))
                xCenter = Integer.parseInt(((ModuleTextBox) module).getText());
        } else if (module == locationZ) {
            if (!((ModuleTextBox) module).getText().isEmpty() && !((ModuleTextBox) module).getText().contentEquals("-"))
                yCenter = Integer.parseInt(((ModuleTextBox) module).getText());
        }

    }

    private void updateVoidCobbleButtonVisuals() {
        if (voidCobbleBtn == null) return;

        String key = voidCobble
                ? "msg.spacelaser.voidcobble.on"
                : "msg.spacelaser.voidcobble.off";

        voidCobbleBtn.setText(LibVulpes.proxy.getLocalizedString(key));
        voidCobbleBtn.setColor(voidCobble ? 0x90ff90 : 0xf0f0f0);
    }




    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        List<ModuleBase> modules = new LinkedList<>();

        // --- VOID-MINING SIMPLIFIED GUI ---
        if (voidMiningMode) {
            if (world.isRemote) {
                // Ask server for current state (mode, running, voidCobble, etc.)
                PacketHandler.sendToServer(new PacketMachine(this, (byte) 13));

                // Lore text: two lines, joined with '\n' in Java
                String line1 = LibVulpes.proxy.getLocalizedString("msg.spacelaser.voidmining.line1");
                String line2 = LibVulpes.proxy.getLocalizedString("msg.spacelaser.voidmining.line2");
                String lore  = line1 + "\n" + line2;

                modules.add(new ModuleText(35, 30, lore, 0x0b0b0b));

                // Void cobble toggle button
                updateVoidCobbleButtonVisuals();
                modules.add(voidCobbleBtn);
            }

            // Power bar is still useful
            modules.add(new ModulePower(11, 25, batteries));
            return modules;
        }

        // --- ORIGINAL PLANET-MINING GUI  ---
        if (world.isRemote) {
            //request update on information
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 13));

            modules.add(updateText = new ModuleText(110, 20, this.getMode().toString(), 0x0b0b0b, true));

            modules.add(locationX);
            modules.add(locationZ);

            modules.add(xtext);
            modules.add(ztext);

            modules.add(no_targets_text);
            modules.add(positionText);
            // modules.add(new ModuleImage(8, 16, TextureResources.laserGuiBG));
        }

        modules.add(new ModuleButton(83, 20, 0, "", this,
                zmaster587.libVulpes.inventory.TextureResources.buttonLeft, 5, 8));
        modules.add(new ModuleButton(137, 20, 1, "", this,
                zmaster587.libVulpes.inventory.TextureResources.buttonRight, 5, 8));
        modules.add(resetBtn);
        modules.add(new ModulePower(11, 25, batteries));

        return modules;
    }


    @Override
    public String getModularInventoryName() {
        return "tile.spaceLaser.name";
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    void check_is_terraforming_update_gui() {
        if (getMode() == MODE.T_FORM) {
            locationX.setVisible(false);
            locationZ.setVisible(false);
            xtext.setVisible(false);
            ztext.setVisible(false);
            positionText.setVisible(false);
            client_update_tf_info();
            //no_targets_text.setVisible(true);
        }else{
            locationX.setVisible(true);
            locationZ.setVisible(true);
            xtext.setVisible(true);
            ztext.setVisible(true);
            positionText.setVisible(true);
            no_targets_text.setVisible(false);
        }
    }
    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId!=2)
            resetBtn.setColor(0x90ff90);
        if (buttonId == 0) {
            this.decrementMode();
            updateText.setText(this.getMode().toString());
            check_is_terraforming_update_gui();
        } else if (buttonId == 1) {
            this.incrementMode();
            updateText.setText(this.getMode().toString());
            check_is_terraforming_update_gui();
        } else if (buttonId == 2) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 14));
            return;
        } else if (buttonId == 3) {
            // Ask server to toggle voidCobble (no payload needed)
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 17));
            return;
        } else
            return;
    }

    @Override
    public void invalidate() {
        super.invalidate();

        if (!world.isRemote) {
            if (drill != null) {
                drill.deactivate();
            }
            clearVoidDrillCache();
            isRunning = false;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return 320 * 320;
    }

    private void clearVoidDrillCache() {
        if (miningDrill instanceof VoidDrill) {
            ((VoidDrill) miningDrill).clearOreCache();
        }
    }

    public enum MODE {
        SINGLE,
        SPIRAL,
        T_FORM
    }
}
