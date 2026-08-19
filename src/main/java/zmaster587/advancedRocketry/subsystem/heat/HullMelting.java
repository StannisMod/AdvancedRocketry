package zmaster587.advancedRocketry.subsystem.heat;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;

import java.util.Set;

/**
 * The last rung of the failure ladder: past a point the ship stops being damaged and starts being
 * gone.
 *
 * <p><b>The subject is the block, and the driver is whichever of three things is hottest.</b> A block
 * can be cooked by the air of the compartment it stands in, by the coolant loop it is welded to, or
 * by what the outside is pouring onto it - and the rung takes the MAXIMUM of the three rather than
 * any one of them, because a hull plate inside a star does not care that the pipes behind it are
 * cold. That is the whole reason this is not simply "the loop got hot".</p>
 *
 * <p><b>What melts is decided by the material, never by a list of blocks.</b> The same table that
 * says how much heat a slug of iron carries says the temperature at which iron stops being iron, so
 * a modded metal nobody has heard of melts at its own point with no code written for it, and a block
 * whose substance is unknown melts at no temperature at all rather than at a guessed one.</p>
 *
 * <p><b>What it leaves behind.</b> Rock and metal leave lava - a melt is a melt, and the mess is the
 * consequence the design asks for. Everything else is simply gone: a molten pane of glass or a
 * carbonised crate leaving a pool of stone-lava would be a worse lie than leaving nothing.</p>
 */
public final class HullMelting {

    private HullMelting() {
    }

    /** How often a loop looks at what it is cooking, in ticks. */
    public static int checkIntervalTicks() {
        return Math.max(1, ARConfiguration.getCurrentConfig().shipHeatMeltCheckTicks);
    }

    /**
     * The temperature at which a surface radiates exactly as much as the outside is putting into it -
     * how hot the environment alone can drive a block, and no hotter.
     *
     * <p>It is {@link HeatNetwork#cellPowerAt} read backwards, which is what makes it comparable with
     * a loop's temperature and a room's on the same scale: the incident flux is quoted on the same
     * curve as everything a radiator sheds, so inverting it is a fourth root and not a new model.</p>
     */
    public static double equilibriumKelvin(double incidentFluxPerCell) {
        if (incidentFluxPerCell <= 0.0D) {
            return 0.0D;
        }
        double reference = Math.max(1.0D,
                ARConfiguration.getCurrentConfig().shipHeatRadiatorReferenceKelvin);
        double atReference = HeatNetwork.cellPowerAt(reference);
        if (atReference <= 0.0D) {
            return 0.0D;
        }
        return reference * Math.pow(incidentFluxPerCell / atReference, 0.25D);
    }

    /**
     * The hottest thing acting on this block: its compartment's air, the loop it is welded to, and
     * what the sky is delivering to it.
     *
     * <p>Zone air is asked of the atmosphere subsystem rather than guessed, and a block in no
     * compartment simply has no air term - which is the common case for a hull plate, and the reason
     * the maximum exists.</p>
     */
    public static double hottestActingOn(World world, BlockPos pos, double loopKelvin,
                                         HeatEnvironment environment) {
        double hottest = Math.max(0.0D, loopKelvin);
        AtmosphereHandler handler = world == null ? null
                : AtmosphereHandler.getOxygenHandler(world.provider.getDimension());
        if (handler != null) {
            AirState air = handler.getAirStateAt(pos);
            if (air != null) {
                hottest = Math.max(hottest, air.getTemperatureKelvin());
            }
        }
        if (environment != null) {
            hottest = Math.max(hottest, equilibriumKelvin(environment.incidentFluxPerCell(pos)));
        }
        return hottest;
    }

    /**
     * Melt the block at {@code pos} if what is acting on it has passed the point its substance
     * survives. Answers whether it did.
     *
     * <p>A substance the table does not know has no ceiling and therefore never melts: refusing to
     * invent one is what stops this rung eating a modded machine nobody described.</p>
     */
    public static boolean meltIfPast(World world, BlockPos pos, double actingKelvin) {
        if (world == null || world.isRemote || pos == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.AIR || block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) {
            return false;
        }
        // Anything the world refuses to let a player break is not something heat may take either:
        // bedrock and the barriers that hold a dimension together have no thermal story, and eating
        // them would be a bug that looks like a mechanic.
        if (block.getBlockHardness(state, world, pos) < 0.0F) {
            return false;
        }
        ThermalMaterial material = ThermalMaterials.INSTANCE.byVanillaMaterial(block);
        ThermalMaterial named = ThermalMaterials.INSTANCE.of(
                new net.minecraft.item.ItemStack(block));
        if (named != null) {
            material = named;
        }
        if (material == null || material.ceilingKelvin() <= 0
                || actingKelvin < material.ceilingKelvin()) {
            return false;
        }
        world.setBlockState(pos, leavesLavaBehind(block) ? Blocks.LAVA.getDefaultState()
                : Blocks.AIR.getDefaultState(), 3);
        return true;
    }

    /** Rock and metal melt into something; everything else is simply consumed. */
    private static boolean leavesLavaBehind(Block block) {
        @SuppressWarnings("deprecation")
        Material vanilla = block.getDefaultState().getMaterial();
        return vanilla == Material.ROCK || vanilla == Material.IRON || vanilla == Material.ANVIL;
    }

    /**
     * One sweep for one coolant loop: everything the loop touches, checked against everything acting
     * on it.
     *
     * <p><b>What this reaches, stated plainly.</b> The loop's own blocks and their immediate
     * neighbours - which is what a loop can cook, and, because a radiating cell is a member and sits
     * on the hull facing out, also what a star can cook through that surface. A hull plate far from
     * any coolant is not swept by this and will not melt until something walks the hull itself.</p>
     *
     * @return how many blocks were lost
     */
    public static int sweep(World world, Set<BlockPos> members, double loopKelvin,
                            HeatEnvironment environment) {
        if (world == null || world.isRemote || members == null || members.isEmpty()) {
            return 0;
        }
        int melted = 0;
        for (BlockPos member : members) {
            double acting = hottestActingOn(world, member, loopKelvin, environment);
            if (meltIfPast(world, member, acting)) {
                melted++;
            }
            for (EnumFacing facing : EnumFacing.VALUES) {
                BlockPos neighbour = member.offset(facing);
                if (members.contains(neighbour)) {
                    continue; // its own sweep will reach it
                }
                if (meltIfPast(world, neighbour,
                        hottestActingOn(world, neighbour, loopKelvin, environment))) {
                    melted++;
                }
            }
        }
        return melted;
    }
}
