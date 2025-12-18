package zmaster587.advancedRocketry.tile;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import zmaster587.advancedRocketry.network.PacketFluidParticle;
import zmaster587.libVulpes.cap.FluidCapability;
import zmaster587.libVulpes.inventory.modules.IModularInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.tile.TileEntityRFConsumer;




import java.util.*;

public class TilePump extends TileEntityRFConsumer implements IFluidHandler, IModularInventory {

    private final int RANGE = 64;
    private FluidTank tank;
    private List<BlockPos> cache;
    private Fluid lastFluidType = null;
    private int localTick = 0;

    public TilePump() {
        super(1000);
        tank = new FluidTank(16000);
        cache = new LinkedList<>();
    }

    private static final int PUMP_INTERVAL_TICKS  = 25; // ~1 Hz
    private static final int EJECT_INTERVAL_TICKS = 20; // 1 Hz
    private final IFluidHandler fluidCap = new FluidCapability(this);

    private boolean shouldRunThisTick(int interval) {
        return interval <= 1 || (localTick % interval) == 0;
    }



    public int getPowerPerOperation() {
        return 100;
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
            return true;
        return super.hasCapability(capability, facing);
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fluidCap);
        }
        return super.getCapability(capability, facing);
    }

    @Override
    public void update() {
        if (world == null || world.isRemote) return;

        localTick++;
        if (localTick == Integer.MIN_VALUE) localTick = 0;
        // Drop stale plan if accepted fluid changed
        Fluid cur = tank.getFluid() == null ? null : tank.getFluid().getFluid();
        if (cur != lastFluidType) {
            if (!cache.isEmpty()) cache.clear();
            lastFluidType = cur;
        }

        if (isRedstoneDisabled()) {
            return;
        }

        super.update();

        // Attempt fluid Eject (throttled; see section 3)
        if (shouldRunThisTick(EJECT_INTERVAL_TICKS) && tank.getFluid() != null) {
            final FluidStack src = tank.getFluid();
            final int toOffer = Math.min(src.amount, 1000);
            if (toOffer > 0) {
                for (EnumFacing dir : EnumFacing.values()) {
                    TileEntity te = world.getTileEntity(pos.offset(dir));
                    if (te == null || !te.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, dir.getOpposite()))
                        continue;

                    IFluidHandler out = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, dir.getOpposite());
                    if (out == null) continue;

                    int simAccepted = out.fill(new FluidStack(src, toOffer), false);
                    if (simAccepted > 0) {
                        FluidStack drained = tank.drain(simAccepted, true);
                        if (drained != null && drained.amount > 0) {
                            out.fill(drained, true);
                            if (tank.getFluid() == null) break; // ran out
                        }
                    }
                }
            }
        }
    }

    private boolean isVanillaLiquid(BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Material mat = state.getMaterial();
        return mat == Material.WATER || mat == Material.LAVA;
    }

    private boolean isVanillaSource(BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof BlockLiquid) {
            // 0 == source, 1..7 flowing (or up to 15, depending)
            Integer lvl = state.getValue(BlockLiquid.LEVEL);
            return lvl != null && lvl == 0;
        }
        return false;
    }

    private Fluid getVanillaFluid(BlockPos pos) {
        Material mat = world.getBlockState(pos).getMaterial();
        if (mat == Material.WATER) return FluidRegistry.WATER;
        if (mat == Material.LAVA)  return FluidRegistry.LAVA;
        return null;
    }



    @Override
    public void performFunction() {
        if (!world.isRemote) {
            if (tank.getCapacity() - 1000 < tank.getFluidAmount())
                return;

            BlockPos nextPos = getNextBlockLocation();
            if (nextPos != null && canFitFluid(nextPos)) {
                IBlockState state = world.getBlockState(nextPos);
                Block worldBlock = state.getBlock();
                Material mat = state.getMaterial();

                if (worldBlock instanceof IFluidBlock) {
                    FluidStack fStack = ((IFluidBlock) worldBlock).drain(world, nextPos, true);
                    if (fStack != null) tank.fill(fStack, true);

                    int colour = ((IFluidBlock) worldBlock).getFluid().getColor();
                    if (mat == Material.LAVA) colour = 0xFFbd3718;
                    PacketHandler.sendToNearby(new PacketFluidParticle(nextPos, this.pos, 200, colour), world.provider.getDimension(), this.pos, 128);
                } else if (isVanillaLiquid(nextPos) && isVanillaSource(nextPos)) {
                    Fluid f = getVanillaFluid(nextPos);
                    if (f != null) {
                        FluidStack stack = new FluidStack(f, 1000);
                        int filled = tank.fill(stack, true);
                        if (filled == 1000) {
                            world.setBlockToAir(nextPos); // remove the source
                            int colour = (mat == Material.LAVA) ? 0xFFbd3718 : 0xFF3F76E4; // MC-ish tint
                            PacketHandler.sendToNearby(new PacketFluidParticle(nextPos, this.pos, 200, colour), world.provider.getDimension(), this.pos, 128);
                        }
                    }
                }
            }
        }
    }


    private boolean canFitFluid(BlockPos pos) {
        Block worldBlock = world.getBlockState(pos).getBlock();
        if (worldBlock instanceof IFluidBlock) {
            return tank.getFluid() == null || tank.getFluid().getFluid() == ((IFluidBlock) worldBlock).getFluid();
        }
        if (isVanillaLiquid(pos)) {
            Fluid f = getVanillaFluid(pos);
            return f != null && (tank.getFluid() == null || tank.getFluid().getFluid() == f);
        }
        return false;
    }


    private BlockPos getNextBlockLocation() {
        if (!cache.isEmpty())
            return cache.remove(0);

        MutableBlockPos currentPos = new MutableBlockPos(pos);
        currentPos.move(EnumFacing.DOWN);

        while (currentPos.getY() > 0 && world.isAirBlock(currentPos)) {
            currentPos.move(EnumFacing.DOWN);
        }
        if (currentPos.getY() <= 0) return null; // nothing below
        if (!world.isBlockLoaded(currentPos)) return null;

        Block worldBlock = world.getBlockState(currentPos).getBlock();

        if (canFitFluid(currentPos)) {
            Fluid target = null;
            if (worldBlock instanceof IFluidBlock) {
                target = ((IFluidBlock) worldBlock).getFluid();
            } else if (isVanillaLiquid(currentPos)) {
                target = getVanillaFluid(currentPos);
            }
            findFluidAtOrAbove(currentPos, target);
        }
        if (!cache.isEmpty())
            return cache.remove(0);
        return null;
    }


    private void findFluidAtOrAbove(BlockPos pos, Fluid targetFluid) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(pos);

        while (!queue.isEmpty()) {
            BlockPos next = queue.poll();

            if (visited.contains(next) || next.getDistance(pos.getX(), pos.getY(), pos.getZ()) > RANGE)
                continue;

            // Robust: never force-load chunks during a flood fill
            if (!world.isBlockLoaded(next))
                continue;

            IBlockState state = world.getBlockState(next);
            Block block = state.getBlock();

            // Case 1: IFluidBlock (existing behavior)
            if (block instanceof IFluidBlock) {
                IFluidBlock fb = (IFluidBlock) block;
                Fluid f = fb.getFluid();
                if (targetFluid == null || f == targetFluid) {
                    if (fb.canDrain(world, next)) {
                        cache.add(0, next); // drainable
                    }
                    visited.add(next);
                    queue.add(next.west());
                    queue.add(next.east());
                    queue.add(next.north());
                    queue.add(next.south());
                    queue.add(next.up());
                }
                continue;
            }

            // Case 2: Vanilla BlockLiquid
            if (isVanillaLiquid(next)) {
                Fluid f = getVanillaFluid(next);
                if (f != null && (targetFluid == null || f == targetFluid)) {
                    // Only sources are drainable; but we still traverse through flowing
                    if (isVanillaSource(next)) {
                        cache.add(0, next);
                    }
                    visited.add(next);
                    queue.add(next.west());
                    queue.add(next.east());
                    queue.add(next.north());
                    queue.add(next.south());
                    queue.add(next.up());
                }
            }
        }
    }

    private boolean isRedstoneDisabled() {
        return world.isBlockPowered(pos);
    }

    @Override
    public boolean canPerformFunction() {
        if (isRedstoneDisabled()) return false;
        if (!shouldRunThisTick(PUMP_INTERVAL_TICKS)) return false;

        // must have at least 100 RF for one bucket operation
        if (energy.getUniversalEnergyStored() < getPowerPerOperation()) return false;

        // must be able to accept a full bucket
        if ((tank.getCapacity() - tank.getFluidAmount()) < 1000) return false;

        // must have a drainable source available; if not, try to populate cache now (cheap probe)
        if (cache.isEmpty()) {
            // very small, one-shot version of your getNextBlockLocation() to populate cache
            MutableBlockPos currentPos = new MutableBlockPos(pos);
            currentPos.move(EnumFacing.DOWN);

            while (currentPos.getY() > 0 && world.isAirBlock(currentPos)) {
                currentPos.move(EnumFacing.DOWN);
            }
            if (currentPos.getY() <= 0) return false; // nothing below
            if (!world.isBlockLoaded(currentPos)) return false;

            if (canFitFluid(currentPos)) {
                Fluid target = null;
                Block worldBlock = world.getBlockState(currentPos).getBlock();
                if (worldBlock instanceof IFluidBlock) {
                    target = ((IFluidBlock) worldBlock).getFluid();
                } else if (isVanillaLiquid(currentPos)) {
                    target = getVanillaFluid(currentPos);
                }
                findFluidAtOrAbove(currentPos, target);
            }
        }
        return !cache.isEmpty(); // only authorize (and thus spend 100 RF) if we have a source to drain
    }


    @Override
    public IFluidTankProperties[] getTankProperties() {
        return tank.getTankProperties();
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        // Don't fill
        return 0;
    }

    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        return tank.drain(resource, doDrain);
    }

    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return tank.drain(maxDrain, doDrain);
    }

    @Override
    public List<ModuleBase> getModules(int id, EntityPlayer player) {
        return new LinkedList<>();
    }

    @Override
    public String getModularInventoryName() {
        return "tile.pump.name";
    }

    @Override
    public void onLoad() {
        super.onLoad();
        lastFluidType = tank.getFluid() == null ? null : tank.getFluid().getFluid();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (!cache.isEmpty()) cache.clear();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!cache.isEmpty()) cache.clear();
    }    

    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
        return nbt;
    }
    @Override public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        tank.readFromNBT(nbt.getCompoundTag("tank"));
    }

    @Override
    public boolean canInteractWithContainer(EntityPlayer entity) {
        return false;
    }

}
