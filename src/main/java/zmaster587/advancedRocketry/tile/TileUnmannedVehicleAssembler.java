package zmaster587.advancedRocketry.tile;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import zmaster587.advancedRocketry.api.*;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;
import zmaster587.advancedRocketry.block.*;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.entity.EntityStationDeployedRocket;
import zmaster587.advancedRocketry.network.PacketInvalidLocationNotify;
import zmaster587.advancedRocketry.util.StorageChunk;
import zmaster587.advancedRocketry.util.WeightEngine;
import zmaster587.libVulpes.block.BlockFullyRotatable;
import zmaster587.libVulpes.block.RotatableBlock;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.util.HashedBlockPosition;
import zmaster587.libVulpes.util.ZUtils;

import javax.annotation.Nonnull;

public class TileUnmannedVehicleAssembler extends TileRocketAssemblingMachine {

    private final static int MAX_SIZE = 17, MAX_SIZE_Y = 17, MIN_SIZE = 3, MIN_SIZE_Y = 3;

    /**
     * Does not make sure the structure is complete, only gets max bounds!
     *
     * @param world the world
     * @param pos2  coords to evaluate from
     * @return AxisAlignedBB bounds of structure if valid  otherwise null
     */
    @Override
    public AxisAlignedBB getRocketPadBounds(World world, BlockPos pos2) {
        EnumFacing direction = RotatableBlock.getFront(world.getBlockState(pos2)).getOpposite();
        int xMin, zMin, xMax, zMax, yMax, yMin;
        int yCurrent = pos2.getY();
        int xCurrent = pos2.getX();
        int zCurrent = pos2.getZ();
        xMax = xMin = xCurrent;
        zMax = zMin = zCurrent;
        int xSize, zSize;

        yMax = ZUtils.getContinuousBlockLength(world, EnumFacing.UP, getPos().add(0, 1, 0), MAX_SIZE_Y, AdvancedRocketryBlocks.blockStructureTower);

        //Get min and maximum Z/X bounds
        if (direction.getFrontOffsetX() != 0) {
            xSize = ZUtils.getContinuousBlockLength(world, direction, pos2.add(0, yMax, 0), MAX_SIZE, AdvancedRocketryBlocks.blockStructureTower);
            zMin = ZUtils.getContinuousBlockLength(world, EnumFacing.NORTH, pos2.add(0, 0, -1), MAX_SIZE, AdvancedRocketryBlocks.blockStructureTower) + 1;
            zMax = ZUtils.getContinuousBlockLength(world, EnumFacing.SOUTH, pos2.add(0, 0, 1), MAX_SIZE - zMin, AdvancedRocketryBlocks.blockStructureTower);
            zSize = zMin + zMax;

            zMin = zCurrent - zMin + 1;
            zMax = zCurrent + zMax;

            if (direction.getFrontOffsetX() > 0) {
                xMax = xCurrent + xSize - 1;
                xMin++;
            }

            if (direction.getFrontOffsetX() < 0) {
                xMin = xCurrent - xSize + 1;
                xMax--;
            }
        } else {
            zSize = ZUtils.getContinuousBlockLength(world, direction, pos2.add(0, yMax, 0), MAX_SIZE, AdvancedRocketryBlocks.blockStructureTower);
            xMin = ZUtils.getContinuousBlockLength(world, EnumFacing.WEST, pos2.add(-1, 0, 0), MAX_SIZE, AdvancedRocketryBlocks.blockStructureTower) + 1;
            xMax = ZUtils.getContinuousBlockLength(world, EnumFacing.EAST, pos2.add(1, 0, 0), MAX_SIZE - xMin, AdvancedRocketryBlocks.blockStructureTower);
            xSize = xMin + xMax;

            xMin = xCurrent - xMin + 1;
            xMax = xCurrent + xMax;


            if (direction.getFrontOffsetZ() > 0) {
                zMax = zCurrent + zSize - 1;
                zMin++;
            }

            if (direction.getFrontOffsetZ() < 0) {
                zMin = zCurrent - zSize + 1;
                zMax--;
            }
        }

        //if tower does not meet criteria then reutrn null
        if (yMax < MIN_SIZE_Y || xSize < MIN_SIZE || zSize < MIN_SIZE) {
            return null;
        }

        return new AxisAlignedBB(xMin, yCurrent, zMin, xMax, yCurrent + yMax - 1, zMax);
    }

    @Override
    public void assembleRocket() {

        if (bbCache == null || world.isRemote) return;

        // 1) Rescan like the parent (may update stats/status and tighten AABB)
        AxisAlignedBB rocketBB = scanRocket(world, getPos(), bbCache);
        if (status != ErrorCodes.SUCCESS || rocketBB == null) return;

        // 2) Remove replaceables **inside the tight box**
        removeReplaceableBlocks(rocketBB);

        // 3) Cut the world using the **tight** AABB 
        final StorageChunk storageChunk;
        try {
            storageChunk = StorageChunk.cutWorldBB(world, rocketBB);
        } catch (Throwable t) { // covers NegativeArraySizeException, etc.
            return;
        }

        // 4) Spawn the SD rocket, centered from the *rescanned* bbox
        final double cx = rocketBB.minX + (rocketBB.maxX - rocketBB.minX) / 2f + 0.5f;
        final double cz = rocketBB.minZ + (rocketBB.maxZ - rocketBB.minZ) / 2f + 0.5f;
        final double cy = this.getPos().getY();

        EntityStationDeployedRocket rocket =
                new EntityStationDeployedRocket(world, storageChunk, stats.copy(), cx, cy, cz);

        // Orientations for SD rockets
        rocket.forwardDirection = RotatableBlock.getFront(world.getBlockState(getPos())).getOpposite();
        rocket.launchDirection = EnumFacing.DOWN;

        // 5) Rotate *all* engine types to match forwardDirection (defensive: only if block supports FACING)
        for (int x = 0; x < storageChunk.getSizeX(); x++) {
            for (int y = 0; y < storageChunk.getSizeY(); y++) {
                for (int z = 0; z < storageChunk.getSizeZ(); z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    IBlockState st = storageChunk.getBlockState(bp);
                    Block b = st.getBlock();

                    boolean isEngine = (b instanceof BlockRocketMotor)
                                    || (b instanceof BlockBipropellantRocketMotor)
                                    || (b instanceof BlockNuclearRocketMotor);

                    if (isEngine && st.getPropertyKeys().contains(BlockFullyRotatable.FACING)) {
                        storageChunk.setBlockState(bp, st.withProperty(BlockFullyRotatable.FACING, rocket.forwardDirection));
                    }
                }
            }
        }

        // 6) Spawn + sync
        world.spawnEntity(rocket);
        NBTTagCompound nbt = new NBTTagCompound();
        rocket.writeToNBT(nbt);
        PacketHandler.sendToNearby(new PacketEntity(rocket, (byte) 0, nbt),
                rocket.world.provider.getDimension(), this.pos, 64);

        // Link existing infrastructure (same order as parent)
        for (IInfrastructure infrastructure : getConnectedInfrastructure()) {
            rocket.linkInfrastructure(infrastructure);
        }

        // 7) Directly stamp tile stats from the entity we just created
        rocket.recalculateStats();
        this.stats = rocket.stats.copy();

        // Now finish up — and DO NOT reset after this
        this.status = ErrorCodes.FINISHED;
        this.markDirty();
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);

        // Rescan to immediately show fresh stats after build
        scanRocket(world, getPos(), bbCache);
    }



    @Override
    public AxisAlignedBB scanRocket(World world, BlockPos pos2, AxisAlignedBB bb) {
        // Always refresh local bounds first
        AxisAlignedBB fresh = getRocketPadBounds(world, getPos());
        if (fresh == null) {
            status = ErrorCodes.INCOMPLETESTRCUTURE; // upstream typo
            return null; // avoid using stale bb
        }
        bbCache = fresh;
        bb = fresh; // ensure loops below use the fresh bounds

        // fast-path: rocket entity already present?
        final AxisAlignedBB buffered = bb.grow(1.0e-4, 1.0e-4, 1.0e-4);
        java.util.List<EntityStationDeployedRocket> sdr =
            world.getEntitiesWithinAABB(EntityStationDeployedRocket.class, buffered);
        if (sdr.size() == 1) {
            EntityStationDeployedRocket r = sdr.get(0);
            r.recalculateStats();
            this.stats = r.stats.copy();
            this.status = ErrorCodes.ALREADY_ASSEMBLED;
            return null;
        }  
        int thrustMonopropellant = 0;
        int thrustBipropellant = 0;
        int thrustNuclearNozzleLimit = 0;
        int thrustNuclearReactorLimit = 0;
        int thrustNuclearTotalLimit = 0;
        int monopropellantfuelUse = 0;
        int bipropellantfuelUse = 0;
        int nuclearWorkingFluidUseMax = 0;
        int fuelCapacityMonopropellant = 0;
        int fuelCapacityBipropellant = 0;
        int fuelCapacityOxidizer = 0;
        int fuelCapacityNuclearWorkingFluid = 0;
        float weight = 0f;

        stats.reset();

        int actualMinX = (int) bb.maxX,
            actualMinY = (int) bb.maxY,
            actualMinZ = (int) bb.maxZ,
            actualMaxX = (int) bb.minX,
            actualMaxY = (int) bb.minY,
            actualMaxZ = (int) bb.minZ;

        // tighten AABB to non-air
        for (int xCurr = (int) bb.minX; xCurr <= bb.maxX; xCurr++) {
            for (int zCurr = (int) bb.minZ; zCurr <= bb.maxZ; zCurr++) {
                for (int yCurr = (int) bb.minY; yCurr <= bb.maxY; yCurr++) {
                    BlockPos p = new BlockPos(xCurr, yCurr, zCurr);
                    if (!world.isAirBlock(p)) {
                        if (xCurr < actualMinX) actualMinX = xCurr;
                        if (yCurr < actualMinY) actualMinY = yCurr;
                        if (zCurr < actualMinZ) actualMinZ = zCurr;
                        if (xCurr > actualMaxX) actualMaxX = xCurr;
                        if (yCurr > actualMaxY) actualMaxY = yCurr;
                        if (zCurr > actualMaxZ) actualMaxZ = zCurr;
                    }
                }
            }
        }

        boolean invalidBlock = false;
        boolean foundFluidTank = false;
        int fluidCapacity = 0;

        if (verifyScan(bb, world)) {
            for (int yCurr = (int) bb.minY; yCurr <= bb.maxY; yCurr++) {
                for (int xCurr = (int) bb.minX; xCurr <= bb.maxX; xCurr++) {
                    for (int zCurr = (int) bb.minZ; zCurr <= bb.maxZ; zCurr++) {

                        BlockPos currPos = new BlockPos(xCurr, yCurr, zCurr);
                        if (world.isAirBlock(currPos)) continue;

                        IBlockState state = world.getBlockState(currPos);
                        Block block = state.getBlock();

                        // blacklist guard
                        if (ARConfiguration.getCurrentConfig().blackListRocketBlocks.contains(block)) {
                            if (!block.isReplaceable(world, currPos)) {
                                invalidBlock = true;
                                if (!world.isRemote) {
                                    PacketHandler.sendToNearby(
                                        new PacketInvalidLocationNotify(new HashedBlockPosition(xCurr, yCurr, zCurr)),
                                        world.provider.getDimension(), getPos(), 64
                                    );
                                }
                            }
                            continue;
                        }

                        if (ARConfiguration.getCurrentConfig().advancedWeightSystem) {
                            weight += WeightEngine.INSTANCE.getWeight(world, currPos);
                        } else {
                            weight += 1f; // fallback: count blocks
                        }

                        // Engines + thrust/fuel use
                        if (block instanceof IRocketEngine) {
                            if (block instanceof BlockNuclearRocketMotor) {
                                nuclearWorkingFluidUseMax += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                thrustNuclearNozzleLimit += ((IRocketEngine) block).getThrust(world, currPos);
                            } else if (block instanceof BlockBipropellantRocketMotor) {
                                bipropellantfuelUse += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                thrustBipropellant += ((IRocketEngine) block).getThrust(world, currPos);
                            } else if (block instanceof BlockRocketMotor) {
                                monopropellantfuelUse += ((IRocketEngine) block).getFuelConsumptionRate(world, xCurr, yCurr, zCurr);
                                thrustMonopropellant += ((IRocketEngine) block).getThrust(world, currPos);
                            }

                            // center engine location for UI/particles
                            final float halfX = (actualMaxX - actualMinX + 1) / 2f;
                            final float halfZ = (actualMaxZ - actualMinZ + 1) / 2f;

                            final float ex = (xCurr - actualMinX + 0.5f) - halfX;
                            final float ez = (zCurr - actualMinZ + 0.5f) - halfZ;
                            final float ey = (yCurr - actualMinY); // <- no +0.5 here

                            stats.addEngineLocation(ex, ey, ez);
                        }

                        // Fuel tanks (family-specific capacities)
                        if (block instanceof IFuelTank) {
                            if (block instanceof BlockBipropellantFuelTank) {
                                fuelCapacityBipropellant += ((IFuelTank) block).getMaxFill(world, currPos, state) * ARConfiguration.getCurrentConfig().fuelCapacityMultiplier;
                            } else if (block instanceof BlockOxidizerFuelTank) {
                                fuelCapacityOxidizer += ((IFuelTank) block).getMaxFill(world, currPos, state) * ARConfiguration.getCurrentConfig().fuelCapacityMultiplier;
                            } else if (block instanceof BlockNuclearFuelTank) {
                                fuelCapacityNuclearWorkingFluid += ((IFuelTank) block).getMaxFill(world, currPos, state) * ARConfiguration.getCurrentConfig().fuelCapacityMultiplier;
                            } else if (block instanceof BlockFuelTank) {
                                fuelCapacityMonopropellant += ((IFuelTank) block).getMaxFill(world, currPos, state) * ARConfiguration.getCurrentConfig().fuelCapacityMultiplier;
                            }
                        }

                        // Nuclear core limits
                        if (block instanceof IRocketNuclearCore) {
                            thrustNuclearReactorLimit += ((IRocketNuclearCore) block).getMaxThrust(world, currPos);
                        }

                        // Intakes
                        if (block instanceof IIntake) {
                            stats.setStatTag("intakePower",
                                (int) stats.getStatTag("intakePower") + ((IIntake) block).getIntakeAmt(state));
                        }

                        // Generic fluid capability presence + capacity
                        TileEntity tile = world.getTileEntity(currPos);
                        if (tile != null) {
                            IFluidHandler handler = tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
                            if (handler != null) {
                                for (IFluidTankProperties info : handler.getTankProperties()) {
                                    if (info == null) continue;
                                    if (!foundFluidTank && info.getCapacity() > 0) foundFluidTank = true;
                                    fluidCapacity += info.getCapacity();
                                }
                            }
                        }
                    }
                }
            }

            // --- Nuclear working fluid scaling (guarded) ---
            int nuclearWorkingFluidUse = 0;
            if (thrustNuclearNozzleLimit > 0) {
                thrustNuclearTotalLimit = Math.min(thrustNuclearNozzleLimit, thrustNuclearReactorLimit);
                if (nuclearWorkingFluidUseMax > 0) {
                    nuclearWorkingFluidUse = (int) (nuclearWorkingFluidUseMax * (thrustNuclearTotalLimit / (float) thrustNuclearNozzleLimit));
                    thrustNuclearTotalLimit = (nuclearWorkingFluidUse * thrustNuclearNozzleLimit) / nuclearWorkingFluidUseMax;
                } else {
                    nuclearWorkingFluidUse = 0;
                    thrustNuclearTotalLimit = 0;
                }
            }

            // Write stats
            stats.setBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT, monopropellantfuelUse);
            stats.setBaseFuelRate(FuelType.LIQUID_BIPROPELLANT,   bipropellantfuelUse);
            stats.setBaseFuelRate(FuelType.LIQUID_OXIDIZER,       bipropellantfuelUse);
            stats.setBaseFuelRate(FuelType.NUCLEAR_WORKING_FLUID, nuclearWorkingFluidUse);

            stats.setFuelRate(FuelType.LIQUID_MONOPROPELLANT, monopropellantfuelUse);
            stats.setFuelRate(FuelType.LIQUID_BIPROPELLANT,   bipropellantfuelUse);
            stats.setFuelRate(FuelType.LIQUID_OXIDIZER,       bipropellantfuelUse);
            stats.setFuelRate(FuelType.NUCLEAR_WORKING_FLUID, nuclearWorkingFluidUse);

            stats.setFuelCapacity(FuelType.LIQUID_MONOPROPELLANT, fuelCapacityMonopropellant);
            stats.setFuelCapacity(FuelType.LIQUID_BIPROPELLANT,   fuelCapacityBipropellant);
            stats.setFuelCapacity(FuelType.LIQUID_OXIDIZER,       fuelCapacityOxidizer);
            stats.setFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID, fuelCapacityNuclearWorkingFluid);

            stats.setThrust(Math.max(Math.max(thrustMonopropellant, thrustBipropellant), thrustNuclearTotalLimit));
            stats.setWeight(weight);
            stats.setStatTag("liquidCapacity", fluidCapacity);

            // Cross-family checks
            int totalFuel    = fuelCapacityBipropellant + fuelCapacityNuclearWorkingFluid + fuelCapacityMonopropellant;
            int totalFuelUse = bipropellantfuelUse + nuclearWorkingFluidUse + monopropellantfuelUse;

            if (invalidBlock) {
                status = ErrorCodes.INVALIDBLOCK;
            } else if (((fuelCapacityBipropellant > 0 && totalFuel > fuelCapacityBipropellant)
                    || (fuelCapacityMonopropellant > 0 && totalFuel > fuelCapacityMonopropellant)
                    || (fuelCapacityNuclearWorkingFluid > 0 && totalFuel > fuelCapacityNuclearWorkingFluid))
                    ||
                    ((thrustBipropellant > 0 && totalFuelUse > bipropellantfuelUse)
                    || (thrustMonopropellant > 0 && totalFuelUse > monopropellantfuelUse)
                    || (thrustNuclearTotalLimit > 0 && totalFuelUse > nuclearWorkingFluidUse))) {
                status = ErrorCodes.COMBINEDTHRUST;
            } else if (getThrust() <= getNeededThrust()) {
                status = ErrorCodes.NOENGINES;
            } else if (((int) stats.getStatTag("intakePower")) <= 0) {
                status = ErrorCodes.NOINTAKE;
            } else if (!foundFluidTank) {
                status = ErrorCodes.NOTANK;
            } else if (thrustBipropellant > 0 && (fuelCapacityBipropellant <= 0 || fuelCapacityOxidizer <= 0)) {
                status = ErrorCodes.NOFUEL; // missing one of the required tanks
            } else if (((thrustBipropellant > 0)      && !hasEnoughFuelUnmanned(FuelType.LIQUID_BIPROPELLANT))
                    || ((thrustMonopropellant > 0)    && !hasEnoughFuelUnmanned(FuelType.LIQUID_MONOPROPELLANT))
                    || ((thrustNuclearTotalLimit > 0) && !hasEnoughFuelUnmanned(FuelType.NUCLEAR_WORKING_FLUID))) {
                status = ErrorCodes.NOFUEL;
            } else {
                status = ErrorCodes.SUCCESS;
            }
        }

        // Normalize bounds to avoid inverted AABBs on edge cases
        double minX = Math.min(actualMinX, actualMaxX);
        double minY = Math.min(actualMinY, actualMaxY);
        double minZ = Math.min(actualMinZ, actualMaxZ);
        double maxX = Math.max(actualMinX, actualMaxX);
        double maxY = Math.max(actualMaxY, actualMinY);
        double maxZ = Math.max(actualMinZ, actualMaxZ);
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }


    private boolean hasEnoughFuelUnmanned(@Nonnull FuelType family) {
        // SD flight: acceleration in entity code is ≈ 0.005 blocks/tick^2
        final float a_station = 0.005f;
        final float targetS   = 128f;  // SD rocket switches to orbit after ~128 blocks

        float t; // seconds (ticks) we can sustain full burn

        switch (family) {
            case LIQUID_MONOPROPELLANT: {
                final int cap  = stats.getFuelCapacity(FuelType.LIQUID_MONOPROPELLANT);
                final int rate = stats.getBaseFuelRate(FuelType.LIQUID_MONOPROPELLANT);
                if (cap <= 0 || rate <= 0) return false;
                t = cap / (float) rate;
                break;
            }

            case LIQUID_BIPROPELLANT: {
                // Both streams must exist; consume in lockstep at their own rates.
                final int capFuel = stats.getFuelCapacity(FuelType.LIQUID_BIPROPELLANT);
                final int capOx   = stats.getFuelCapacity(FuelType.LIQUID_OXIDIZER);
                final int rateFuel= stats.getBaseFuelRate(FuelType.LIQUID_BIPROPELLANT);
                final int rateOx  = stats.getBaseFuelRate(FuelType.LIQUID_OXIDIZER);
                if (capFuel <= 0 || capOx <= 0 || rateFuel <= 0 || rateOx <= 0) return false;

                final float tFuel = capFuel / (float) rateFuel;
                final float tOx   = capOx   / (float) rateOx;
                t = Math.min(tFuel, tOx);  // limiting stream dictates burn time
                break;
            }

            case NUCLEAR_WORKING_FLUID: {
                final int cap  = stats.getFuelCapacity(FuelType.NUCLEAR_WORKING_FLUID);
                final int rate = stats.getBaseFuelRate(FuelType.NUCLEAR_WORKING_FLUID);
                if (cap <= 0 || rate <= 0) return false;
                t = cap / (float) rate;
                break;
            }

            default:
                return false;
        }

        // distance under constant accel: s = 0.5 * a * t^2
        final float sCan = 0.5f * a_station * t * t;
        return sCan >= targetS;
    }

    @Override public void onLoad() { super.onLoad(); }

    @Override public void invalidate() { super.invalidate(); }

    @Override public void onChunkUnload() { super.onChunkUnload(); }
    
     
    @Override
    protected boolean verifyScan(AxisAlignedBB bb, World world) {
        return true;
    }

}
