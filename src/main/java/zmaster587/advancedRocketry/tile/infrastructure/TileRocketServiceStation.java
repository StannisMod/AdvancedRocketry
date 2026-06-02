package zmaster587.advancedRocketry.tile.infrastructure;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.api.IFuelTank;
import zmaster587.advancedRocketry.api.IInfrastructure;
import zmaster587.advancedRocketry.api.IMission;
import zmaster587.advancedRocketry.block.BlockBipropellantRocketMotor;
import zmaster587.advancedRocketry.block.BlockRocketMotor;
import zmaster587.advancedRocketry.block.BlockSeat;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.capability.CapabilityWear;
import zmaster587.advancedRocketry.api.capability.IPartWear;
import zmaster587.advancedRocketry.tile.TileBrokenPart;
import zmaster587.advancedRocketry.tile.multiblock.machine.TilePrecisionAssembler;
import zmaster587.advancedRocketry.util.IBrokenPartBlock;
import zmaster587.advancedRocketry.util.InventoryUtil;
import zmaster587.advancedRocketry.util.StorageChunk;
import zmaster587.advancedRocketry.util.nbt.NBTHelper;
import zmaster587.libVulpes.interfaces.IRecipe;
import zmaster587.libVulpes.recipe.RecipesMachine;
import zmaster587.libVulpes.util.EmbeddedInventory;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.block.BlockTile;
import zmaster587.libVulpes.interfaces.ILinkableTile;
import zmaster587.libVulpes.inventory.modules.*;
import zmaster587.libVulpes.items.ItemLinker;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.tile.IComparatorOverride;
import zmaster587.libVulpes.tile.TileEntityRFConsumer;
import zmaster587.libVulpes.util.IAdjBlockUpdate;
import zmaster587.libVulpes.util.INetworkMachine;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

public class TileRocketServiceStation extends TileEntityRFConsumer implements IModularInventory, ITickable, IAdjBlockUpdate, IInfrastructure, ILinkableTile, INetworkMachine, IButtonInventory, IProgressBar, IComparatorOverride {

    EntityRocketBase linkedRocket;

    ModuleText destroyProbText;
    ModuleText destroyProgressText;
    ModuleText wornMotorsText;
    ModuleText wornSeatsText;
    ModuleText wornTanksText;
    ModuleText wornMotorsCount;
    ModuleText wornSeatsCount;
    ModuleText wornTanksCount;
    boolean was_powered = false;

    List<TilePrecisionAssembler> assemblers = new ArrayList<>();
    List<BlockPos> assemblerPoses = new ArrayList<>();
    TileBrokenPart[] partsProcessing = new TileBrokenPart[0];
    IBlockState[] statesProcessing = new IBlockState[0];

    int initialPartToRepairCount;
    List<TileBrokenPart> partsToRepair = new LinkedList<>();
    List<IBlockState> statesToRepair = new LinkedList<>();

    // Input slots for the standalone (assembler-less) repair path.
    private static final int REPAIR_SLOTS = 6;
    private final EmbeddedInventory repairInventory = new EmbeddedInventory(REPAIR_SLOTS);

    public TileRocketServiceStation() {
        super(10000);

        destroyProbText = new ModuleText(90, 30, LibVulpes.proxy.getLocalizedString("msg.serviceStation.destroyProbNA"), 0x2b2b2b, true);
        wornMotorsText = new ModuleText(40, 30 + 30, LibVulpes.proxy.getLocalizedString("msg.serviceStation.wornMotorsText"), 0x2b2b2b, true);
        wornSeatsText = new ModuleText(90, 30 + 30, LibVulpes.proxy.getLocalizedString("msg.serviceStation.wornSeatsText"), 0x2b2b2b, true);
        wornTanksText = new ModuleText(140, 30 + 30, LibVulpes.proxy.getLocalizedString("msg.serviceStation.wornTanksText"), 0x2b2b2b, true);
        destroyProgressText = new ModuleText(90, 120, LibVulpes.proxy.getLocalizedString("msg.serviceStation.serviceProgressNA"), 0x2b2b2b, true);

        wornMotorsCount = new ModuleText(40, 30 + 30 + 10, "0", 0x2b2b2b, true);
        wornSeatsCount = new ModuleText(90, 30 + 30 + 10, "0", 0x2b2b2b, true);
        wornTanksCount = new ModuleText(140, 30 + 30 + 10, "0", 0x2b2b2b, true);
    }

    @Override
    public void invalidate() {
        super.invalidate();

        if (linkedRocket != null) {
            linkedRocket.unlinkInfrastructure(this);
            unlinkRocket();
        }
    }

    public boolean getEquivalentPower() {
        //if (state == RedstoneState.OFF)
        //    return false;

        boolean state2 = world.isBlockIndirectlyGettingPowered(pos) > 0;

        //if (state == RedstoneState.INVERTED)
        //    state2 = !state2;
        return state2;
    }

    @Override
    public void onAdjacentBlockUpdated() {

    }

    @Override
    public int getMaxLinkDistance() {
        return 3000;
    }

    public void updateRepairList() {
        updateRepairList(true);
    }

    private void updateRepairList(boolean initial) {
        EntityRocket rocket = (EntityRocket) linkedRocket;
        partsToRepair = new LinkedList<>();
        statesToRepair = new LinkedList<>();

        for (TileEntity te : rocket.storage.getTileEntityList()) {
            if (te instanceof TileBrokenPart) {
                TileBrokenPart part = (TileBrokenPart) te;
                if (part.getStage() > 0) {
                    partsToRepair.add(part);
                    statesToRepair.add(rocket.storage.getBlockState(te.getPos()));
                }
            }
        }

        if (initial) {
            initialPartToRepairCount = partsToRepair.size();
        }
    }

    private void scanForAssemblers() {
        this.assemblers = new ArrayList<>();

        int size = 5;

        for (int x = getPos().getX() - size; x < getPos().getX() + size; x++) {
            for (int y = getPos().getY() - size; y < getPos().getY() + size; y++) {
                for (int z = getPos().getZ() - size; z < getPos().getZ() + size; z++) {
                    TileEntity te = world.getTileEntity(new BlockPos(x, y, z));
                    if (te instanceof TilePrecisionAssembler) {
                        this.assemblers.add((TilePrecisionAssembler) te);
                    }
                }
            }
        }

        this.statesProcessing = new IBlockState[assemblers.size()];
        this.partsProcessing = new TileBrokenPart[assemblers.size()];
    }

    private boolean processAssemblerResult(int index) {
        StorageChunk storage = ((EntityRocket) linkedRocket).storage;
        TilePrecisionAssembler assembler = assemblers.get(index);

        if (InventoryUtil.hasItemInInventories(assembler.getItemOutPorts(), "rocket", true)) {
            IBlockState state = statesProcessing[index];
            TileBrokenPart te = partsProcessing[index];

            if (te == null) {
                AdvancedRocketry.logger.warn("Rocket service station at " + getPos()
                        + " is out of sync with connected assemblers! Repairing part lost");
                return false;
            }

            te.setStage(0);
            storage.addTileEntity(te);
            storage.setBlockState(te.getPos(), state);

            statesProcessing[index] = null;
            partsProcessing[index] = null;

            assembler.markDirty();

            return true;
        }
        return false;
    }

    private void syncRocket() {
        NBTTagCompound nbtdata = new NBTTagCompound();

        linkedRocket.writeToNBT(nbtdata);
        PacketHandler.sendToNearby(new PacketEntity((EntityRocket) linkedRocket, (byte) 0, nbtdata), linkedRocket.world.provider.getDimension(), this.pos, 64);
    }

    private void consumePartToRepair(int assemblerIndex) {
        StorageChunk storage = ((EntityRocket) linkedRocket).storage;

        TilePrecisionAssembler assembler = assemblers.get(assemblerIndex);
        TileBrokenPart part = partsToRepair.get(0);
        IBlockState state = statesToRepair.get(0);
        if (!(part.getBlockType() instanceof IBrokenPartBlock)) {
            AdvancedRocketry.logger.warn("Rocket part at " + part.getPos() + " is out of sync with its block! Removing");
            statesToRepair.remove(0);
            partsToRepair.remove(0);
            return;
        }
        IBrokenPartBlock partBlock = (IBrokenPartBlock) part.getBlockType();

        // add to processing list
        statesProcessing[assemblerIndex] = state;
        partsProcessing[assemblerIndex] = part;

        // add to the assembler
        ItemStack resultingStack = partBlock.getDropItem(statesToRepair.get(0), world, part);
        if (!InventoryUtil.addItemToOneOfTheInventories(assembler.getItemInPorts(), resultingStack)) {
            AdvancedRocketry.logger.error("Precision assembler at " + assembler.getPos() + " overflows. Repaired part lost");
        }
        statesToRepair.remove(0);
        partsToRepair.remove(0);

        // consume parts from the rocket
        storage.getTileEntityList().remove(part);
        storage.setBlockState(part.getPos(), Blocks.AIR.getDefaultState());
        assembler.onInventoryUpdated();
    }

    private void giveWorkToAssemblers() {
        boolean dirty = false;
        for (int i = 0; i < assemblers.size(); i++) {
            if (assemblers.get(i) == null || assemblers.get(i).isInvalid()) {
                // Assembler vanished mid-repair: re-queue the in-flight part so it
                // is not silently lost, then drop the dead assembler slot.
                if (partsProcessing[i] != null) {
                    partsToRepair.add(0, partsProcessing[i]);
                    statesToRepair.add(0, statesProcessing[i]);
                }
                assemblers.set(i, null);
                partsProcessing[i] = null;
                statesProcessing[i] = null;
                continue;
            }

            dirty = dirty || processAssemblerResult(i);

            TilePrecisionAssembler assembler = assemblers.get(i);

            if (InventoryUtil.hasItemInInventories(assembler.getItemInPorts(), "motor", false)) {
                // assembler already have a motor for work, skipping
                continue;
            }

            if (!this.partsToRepair.isEmpty() && statesProcessing[i] == null) {
                consumePartToRepair(i);
                dirty = true;
            }
        }
        if (dirty) {
            syncRocket();
        }
    }

    @Override
    public void performFunction() {
        if (linkedRocket instanceof EntityRocket) {
            // stay with the right blockstate
            IBlockState state = world.getBlockState(pos);
            if (!state.getValue(BlockTile.STATE)) {
                world.setBlockState(pos, state.withProperty(BlockTile.STATE, true));
            }

            if (getEquivalentPower() && linkedRocket != null) {
                if (!was_powered) {
                    scanForAssemblers();
                    was_powered = true;
                } else {
                    if (assemblerPoses != null) {
                        // lazy access to assembler list loaded from NBT
                        assemblers = assemblerPoses.stream().map(pos -> (TilePrecisionAssembler) world.getTileEntity(pos)).collect(Collectors.toList());
                        assemblerPoses = null;

                        this.statesProcessing = new IBlockState[assemblers.size()];
                        this.partsProcessing = new TileBrokenPart[assemblers.size()];

                        updateRepairList(false);
                    }
                }

                if (hasValidAssembler()) {
                    giveWorkToAssemblers();
                } else {
                    // No assembler nearby → repair one part from the station's own
                    // input slots at the configured resource penalty.
                    tryStandaloneRepair();
                }
            }
        }
        if (!getEquivalentPower()) {
            was_powered = false;
        }
    }

    /** The standalone-repair material input inventory (test/automation access). */
    public net.minecraftforge.items.IItemHandlerModifiable getRepairInventory() {
        return repairInventory;
    }

    private boolean hasValidAssembler() {
        for (TilePrecisionAssembler a : assemblers) {
            if (a != null && !a.isInvalid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Repair one worn part using the station's own input inventory, consuming the
     * part's PrecisionAssembler repair-recipe non-part ingredients times
     * {@code serviceStationStandaloneRepairMultiplier}. No-op (leaves the part
     * worn) if there is no repair recipe or the materials are missing.
     */
    private boolean tryStandaloneRepair() {
        if (partsToRepair.isEmpty()) {
            return false;
        }
        TileBrokenPart part = partsToRepair.get(0);
        IBlockState state = statesToRepair.get(0);
        if (!(part.getBlockType() instanceof IBrokenPartBlock)) {
            partsToRepair.remove(0);
            statesToRepair.remove(0);
            return false;
        }
        ItemStack worn = ((IBrokenPartBlock) part.getBlockType()).getDropItem(state, world, part);
        IRecipe recipe = findRepairRecipe(worn);
        if (recipe == null) {
            // Not standalone-repairable (no recipe) — skip so the queue advances.
            partsToRepair.remove(0);
            statesToRepair.remove(0);
            return false;
        }

        double mult = ARConfiguration.getCurrentConfig().serviceStationStandaloneRepairMultiplier;
        if (!consumeStandaloneMaterials(recipe, worn, mult, true)) {
            return false; // not enough materials yet; keep the part queued
        }
        consumeStandaloneMaterials(recipe, worn, mult, false);

        part.setStage(0);
        StorageChunk storage = ((EntityRocket) linkedRocket).storage;
        storage.setBlockState(part.getPos(), state);
        partsToRepair.remove(0);
        statesToRepair.remove(0);
        syncRocket();
        return true;
    }

    private IRecipe findRepairRecipe(ItemStack worn) {
        for (IRecipe recipe : RecipesMachine.getInstance().getRecipes(TilePrecisionAssembler.class)) {
            for (List<ItemStack> slot : recipe.getIngredients()) {
                for (ItemStack variant : slot) {
                    if (ItemStack.areItemsEqual(variant, worn)) {
                        return recipe;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Either check (simulate=true) or consume (simulate=false) the recipe's
     * non-part ingredients ×mult from the station inventory. The part slot (the
     * worn item itself) is skipped — only materials are charged.
     */
    private boolean consumeStandaloneMaterials(IRecipe recipe, ItemStack worn, double mult, boolean simulate) {
        for (List<ItemStack> slot : recipe.getIngredients()) {
            if (slot.isEmpty()) {
                continue;
            }
            boolean isPartSlot = slot.stream().anyMatch(s -> ItemStack.areItemsEqual(s, worn));
            if (isPartSlot) {
                continue;
            }
            int needed = (int) Math.ceil(slot.get(0).getCount() * mult);
            if (needed <= 0) {
                continue;
            }
            int remaining = needed;
            for (int i = 0; i < repairInventory.getSlots() && remaining > 0; i++) {
                ItemStack inSlot = repairInventory.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    continue;
                }
                boolean matches = slot.stream().anyMatch(
                        v -> net.minecraftforge.oredict.OreDictionary.itemMatches(v, inSlot, false));
                if (!matches) {
                    continue;
                }
                int take = Math.min(remaining, inSlot.getCount());
                if (!simulate) {
                    repairInventory.extractItem(i, take, false);
                }
                remaining -= take;
            }
            if (remaining > 0) {
                return false; // cannot satisfy this material
            }
        }
        return true;
    }

    @Override
    public boolean canPerformFunction() {
        if (world.isRemote || world.getWorldTime() % 20 != 0) {
            return false;
        }

        boolean hasWork = partsToRepair.size() > 0 || Arrays.stream(partsProcessing).anyMatch(Objects::nonNull);

        if (hasWork) {
            return true;
        }

        IBlockState state = world.getBlockState(pos);
        if (state.getValue(BlockTile.STATE)) {
            world.setBlockState(pos, state.withProperty(BlockTile.STATE, false));
        }
        return false;
    }

    @Override
    public int getPowerPerOperation() {
        return 10;
    }

    @Override
    public boolean onLinkStart(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        ItemLinker.setMasterCoords(item, getPos());
        if (linkedRocket != null) {
            linkedRocket.unlinkInfrastructure(this);
            unlinkRocket();
        }

        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("%s %s", new TextComponentTranslation("msg.serviceStation.link"), ": " + getPos().getX() + " " + getPos().getY() + " " + getPos().getZ()));
        return true;
    }

    @Override
    public boolean onLinkComplete(@Nonnull ItemStack item, TileEntity entity, EntityPlayer player, World world) {
        if (player.world.isRemote)
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.linker.error.firstMachine"));
        return false;
    }

    @Override
    public void unlinkRocket() {
        linkedRocket = null;

        dropRepairStats();
    }

    public void dropRepairStats() {
        partsToRepair = new LinkedList<>();
        statesToRepair = new LinkedList<>();
        initialPartToRepairCount = 0;
    }

    @Override
    public boolean disconnectOnLiftOff() {
        return true;
    }

    @Override
    public boolean linkRocket(EntityRocketBase rocket) {
        this.linkedRocket = rocket;
        if (rocket instanceof EntityRocket) {
            updateRepairList();
        }
        return true;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        was_powered = nbt.getBoolean("was_powered");
        initialPartToRepairCount = nbt.getInteger("initialPartToRepairCount");
        // Backward compatible: old saves lack these keys → empty inventory.
        if (nbt.hasKey("repairInv")) {
            repairInventory.readFromNBT(nbt.getCompoundTag("repairInv"));
        }

        assemblerPoses = NBTHelper.readCollection("assemblerPoses", nbt, ArrayList::new, NBTHelper::readBlockPos);
        partsProcessing = NBTHelper.readCollection("partsProcessing", nbt, ArrayList::new, NBTHelper::readTileEntity).toArray(new TileBrokenPart[0]);
        statesProcessing = NBTHelper.readCollection("statesProcessing", nbt, ArrayList::new, NBTHelper::readState).toArray(new IBlockState[0]);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setBoolean("was_powered", was_powered);
        nbt.setInteger("initialPartToRepairCount", initialPartToRepairCount);

        NBTTagCompound invTag = new NBTTagCompound();
        repairInventory.writeToNBT(invTag);
        nbt.setTag("repairInv", invTag);

        NBTHelper.writeCollection("assemblerPoses", nbt, this.assemblers, te -> NBTHelper.writeBlockPos(te.getPos()));
        NBTHelper.writeCollection("partsProcessing", nbt, Arrays.asList(this.partsProcessing), NBTHelper::writeTileEntity);
        NBTHelper.writeCollection("statesProcessing", nbt, Arrays.asList(this.statesProcessing), NBTHelper::writeState);

        return nbt;
    }

    @Override
    public void writeDataToNetwork(ByteBuf out, byte id) {

    }

    @Override
    public void readDataFromNetwork(ByteBuf in, byte packetId,
                                    NBTTagCompound nbt) {

    }

    @Override
    public List<ModuleBase> getModules(int ID, EntityPlayer player) {
        LinkedList<ModuleBase> modules = new LinkedList<>();

        modules.add(new ModulePower(10, 20, this.energy));
        modules.add(new ModuleButton(63 - 52 / 2, 100, 0, LibVulpes.proxy.getLocalizedString("msg.serviceStation.assemblerScan"),
                this, zmaster587.libVulpes.inventory.TextureResources.buttonBuild, 104, 16));

        updateText();

        modules.add(destroyProbText);
        modules.add(wornMotorsText);
        modules.add(wornSeatsText);
        modules.add(wornTanksText);
        modules.add(destroyProgressText);
        modules.add(wornMotorsCount);
        modules.add(wornSeatsCount);
        modules.add(wornTanksCount);

        modules.add(new ModuleProgress(32, 133, 0, TextureResources.progressToMission, this));

        // Input slots for the standalone repair path (materials when no assembler).
        modules.add(new ModuleSlotArray(8, 90, repairInventory, 0, REPAIR_SLOTS));

        if (!world.isRemote) {
            PacketHandler.sendToPlayer(new PacketMachine(this, (byte) 1), player);
        }

        return modules;
    }

    private void updateText() {
        if (linkedRocket != null) {
            if (!(linkedRocket instanceof EntityRocket)) {
//                System.out.println("Huh, error....");
                destroyProbText.setText(LibVulpes.proxy.getLocalizedString("msg.serviceStation.destroyProbNA"));
                return;
            }
            EntityRocket rocket = (EntityRocket) linkedRocket;
            destroyProbText.setText(LibVulpes.proxy.getLocalizedString("msg.serviceStation.destroyProb") + ": " + rocket.storage.getBreakingProbability());

            // Count worn parts via the wear capability so tanks/seats (which are
            // TileWearable, not TileBrokenPart) are reflected, not just motors.
            long motorsCount = 0, seatsCount = 0, tanksCount = 0;
            for (TileEntity te : rocket.storage.getTileEntityList()) {
                IPartWear wear = CapabilityWear.get(te);
                if (wear == null || wear.getStage() <= 0) {
                    continue;
                }
                if (te.getBlockType() instanceof BlockRocketMotor || te.getBlockType() instanceof BlockBipropellantRocketMotor) {
                    motorsCount++;
                } else if (te.getBlockType() instanceof BlockSeat) {
                    seatsCount++;
                } else if (te.getBlockType() instanceof IFuelTank) {
                    tanksCount++;
                }
            }

            this.wornMotorsCount.setText(String.valueOf(motorsCount));
            this.wornSeatsCount.setText(String.valueOf(seatsCount));
            this.wornTanksCount.setText(String.valueOf(tanksCount));
        } else {
            destroyProbText.setText(LibVulpes.proxy.getLocalizedString("msg.serviceStation.destroyProbNA"));
        }
    }

    @Override
    public void useNetworkData(EntityPlayer player, Side side, byte id,
                               NBTTagCompound nbt) {
        if (id == 0) {
            scanForAssemblers();
        }
    }

    @Override
    public void onInventoryButtonPressed(int buttonId) {
        if (buttonId == 0) {
            PacketHandler.sendToServer(new PacketMachine(this, (byte) 0));
        }
    }

    @Override
    public String getModularInventoryName() {
        return "container.servicestation";
    }

    @Override
    public float getNormallizedProgress(int id) {
        if (id == 1) {
            return Math.max(Math.min(0.5f + (getProgress(id) / (float) getTotalProgress(id)), 1), 0f);
        }

        //keep text updated
        if (world.isRemote)
            updateText();

        return Math.min(getProgress(id) / (float) getTotalProgress(id), 1.0f);
    }

    @Override
    public void setProgress(int id, int progress) {

    }

    @Override
    public int getProgress(int id) {
        //Try to keep client synced with server, this also allows us to put the monitor on a different world altogether
        if (world.isRemote)
            if (id == 0) {
                if (!(linkedRocket instanceof EntityRocket)) {
//                    System.out.println("Huh, error....");
                    return 0;
                }
                return initialPartToRepairCount - partsToRepair.size() - (int) Arrays.stream(partsProcessing).filter(Objects::nonNull).count();
            }

        return 0;
    }

    @Override
    public int getTotalProgress(int id) {
//        if (id == 0)
//            return ARConfiguration.getCurrentConfig().orbit;ё
//        else if (id == 1)
//            return 200;
        if (id == 0) {
            return initialPartToRepairCount;
        }
        return 0;
    }

    @Override
    public void setTotalProgress(int id, int progress) {
        //Should only become an issue if configs are desynced or fuel
//        if (id == 2)
//            maxFuelLevel = progress;
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return true;
    }

    @Override
    public boolean linkMission(IMission mission) {
        return false;
    }

    @Override
    public void unlinkMission() {
        updateText();
    }

    @Override
    public boolean canRenderConnection() {
        return false;
    }

    @Override
    public int getComparatorOverride() {
//        if (linkedRocket instanceof EntityRocket) {
//            return (int) (15 * ((EntityRocket) linkedRocket).getRelativeHeightFraction());
//        }
        return 0;
    }
}
