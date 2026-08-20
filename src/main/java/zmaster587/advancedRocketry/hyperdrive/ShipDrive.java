package zmaster587.advancedRocketry.hyperdrive;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;
import zmaster587.advancedRocketry.tile.TileShipComponent;
import zmaster587.advancedRocketry.tile.hyperdrive.TileGravityDampener;
import zmaster587.advancedRocketry.tile.hyperdrive.TileHyperdriveGenerator;
import zmaster587.advancedRocketry.tile.hyperdrive.TileJumpCapacitor;
import zmaster587.advancedRocketry.tile.hyperdrive.TileJumpFieldEmitter;

/**
 * One real ship's drive: which machines it actually has aboard, and what they are worth together.
 *
 * <p>Everything is resolved the way the rest of the ship layer resolves things — from the flight
 * computer outward. A machine belongs to this ship when it was assembled into it (it carries the
 * offset back to THIS computer) and it is standing inside this ship's own claim. Both halves matter:
 * the link alone would let a machine that was never assembled claim membership, and the claim alone
 * would let a second ship parked alongside lend its capacitor.</p>
 *
 * <p>Nothing here is cached. A drive is asked about when a pilot presses a key, and a number that
 * was true at assembly time is a number that will eventually be a lie — a coil pulled out mid-flight
 * has to make the ship slower the moment it is pulled.</p>
 */
public final class ShipDrive {

    private final World world;
    private final BlockPos flightComputerPos;

    public ShipDrive(World world, BlockPos flightComputerPos) {
        this.world = world;
        this.flightComputerPos = flightComputerPos;
    }

    // ─── The machines ──────────────────────────────────────────────────────────

    /** This ship's field generator, or {@code null}. One ship, one generator. */
    public TileHyperdriveGenerator generator() {
        List<TileHyperdriveGenerator> found = componentsOfType(TileHyperdriveGenerator.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The capacitors feeding this ship's generator. "Feeding" is adjacency: a bank has to stand
     * against the drive it is dumping into, which is what makes where a player puts it a decision
     * rather than a formality. Several are allowed, and they add up.
     */
    public List<TileJumpCapacitor> capacitors() {
        List<TileJumpCapacitor> connected = new ArrayList<>();
        TileHyperdriveGenerator gen = generator();
        if (gen == null) {
            return connected;
        }
        List<BlockPos> footprint = gen.footprint();
        for (TileJumpCapacitor capacitor : componentsOfType(TileJumpCapacitor.class)) {
            if (touches(capacitor.getPos(), footprint)) {
                connected.add(capacitor);
            }
        }
        return connected;
    }

    /** This ship's hull emitters. */
    public List<TileJumpFieldEmitter> emitters() {
        return componentsOfType(TileJumpFieldEmitter.class);
    }

    /** This ship's gravity dampeners, powered or not. */
    public List<TileGravityDampener> dampeners() {
        return componentsOfType(TileGravityDampener.class);
    }

    /** The positions of every dampener that has the power to protect anybody right now. */
    public List<BlockPos> poweredDampenerPositions() {
        List<BlockPos> powered = new ArrayList<>();
        for (TileGravityDampener dampener : dampeners()) {
            if (dampener.isPowered()) {
                powered.add(dampener.getPos());
            }
        }
        return powered;
    }

    // ─── What they are worth ───────────────────────────────────────────────────

    /** The drive's stats, or {@link ShipDriveStats#NONE} when there is no generator aboard. */
    public ShipDriveStats stats() {
        TileHyperdriveGenerator gen = generator();
        return gen == null ? ShipDriveStats.NONE : gen.stats();
    }

    /** Charge available across every connected capacitor. */
    public long capacitorCharge() {
        long total = 0L;
        for (TileJumpCapacitor capacitor : capacitors()) {
            total += capacitor.charge();
        }
        return total;
    }

    /** Combined capacity of every connected capacitor — what the ship could hold when full. */
    public long capacitorCapacity() {
        long total = 0L;
        for (TileJumpCapacitor capacitor : capacitors()) {
            total += capacitor.capacity();
        }
        return total;
    }

    /**
     * Ticks until the bank can open a window again <b>if the ship feeds it at the bank's full accept
     * rate</b>, or {@code -1} when it never can. A BEST CASE: the energy comes from the ship's own
     * generation, so a pilot who has under-built his reactors waits longer than this says. It is still
     * entirely a consequence of what the player built — now of two things he built rather than one.
     */
    public long cooldownTicks() {
        long needed = stats().burstCost();
        if (needed <= 0L) {
            return -1L;
        }
        long best = -1L;
        long charge = capacitorCharge();
        if (charge >= needed) {
            return 0L;
        }
        for (TileJumpCapacitor capacitor : capacitors()) {
            long ticks = capacitor.ticksUntilAtFullInflow(needed);
            if (ticks < 0L) {
                continue;
            }
            if (best < 0L || ticks < best) {
                best = ticks;
            }
        }
        return best;
    }

    /**
     * Take the burst out of the bank, or take nothing. Returns whether the window opened — the
     * commit point of a jump, and the first moment the pilot has spent anything at all.
     */
    public boolean fireBurst(long now) {
        long needed = stats().burstCost();
        if (needed <= 0L || capacitorCharge() < needed) {
            return false;
        }
        long remaining = needed;
        for (TileJumpCapacitor capacitor : capacitors()) {
            if (remaining <= 0L) {
                break;
            }
            long available = capacitor.charge();
            long take = Math.min(available, remaining);
            if (take > 0L && capacitor.discharge(take) == take) {
                remaining -= take;
            }
        }
        return remaining <= 0L;
    }

    /** The window this ship's drive can hold open, in world coordinates. */
    public JumpWindow window() {
        TileHyperdriveGenerator gen = generator();
        if (gen == null) {
            return JumpWindow.of(null, new ArrayList<BlockPos>());
        }
        List<BlockPos> emitterPositions = new ArrayList<>();
        for (TileJumpFieldEmitter emitter : emitters()) {
            emitterPositions.add(emitter.getPos());
        }
        return JumpWindow.of(gen.getPos(), emitterPositions);
    }

    /**
     * How much of the hull the window encloses, or {@code null} when the hull's own extent was never
     * recorded — a craft that was never assembled has no hull to measure.
     */
    public JumpWindow.Coverage coverage() {
        int[] box = hullBox();
        if (box == null) {
            return null;
        }
        return window().cover(new JumpWindow.Envelope(box[0], box[1], box[2], box[3], box[4], box[5]));
    }

    /** Every unit of Forge Energy stored aboard this ship, wherever it is stored. */
    public long storedEnergy() {
        AxisAlignedBB yard = yardBounds();
        long total = 0L;
        if (world == null) {
            return 0L;
        }
        for (TileEntity te : world.loadedTileEntityList.toArray(new TileEntity[0])) {
            if (te == null || te.isInvalid() || !withinXZ(yard, te.getPos())) {
                continue;
            }
            if (!te.hasCapability(CapabilityEnergy.ENERGY, null)) {
                continue;
            }
            IEnergyStorage storage = te.getCapability(CapabilityEnergy.ENERGY, null);
            if (storage != null) {
                total += storage.getEnergyStored();
            }
        }
        return total;
    }

    /** The hull box in world coordinates, or {@code null} when no assembly recorded one. */
    public int[] hullBox() {
        if (world == null || flightComputerPos == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(flightComputerPos);
        return te instanceof TileAdvancedFlightComputer
                ? ((TileAdvancedFlightComputer) te).hullBox()
                : null;
    }

    // ─── Finding what belongs to this ship ─────────────────────────────────────

    private <T extends TileShipComponent> List<T> componentsOfType(Class<T> type) {
        List<T> found = new ArrayList<>();
        if (world == null || flightComputerPos == null) {
            return found;
        }
        AxisAlignedBB yard = yardBounds();
        for (TileEntity te : world.loadedTileEntityList.toArray(new TileEntity[0])) {
            if (!type.isInstance(te) || te.isInvalid()) {
                continue;
            }
            T component = type.cast(te);
            if (!withinXZ(yard, component.getPos())) {
                continue; // some other ship's machine, or one standing on a planet
            }
            // A component standing INSIDE this ship's own subspace claim but linked to nothing was
            // built onto an already-assembled ship: the assembler is what normally binds the pair,
            // and it only ever runs once, on the pad. Without adopting it here a drive block added
            // to a finished ship is invisible forever - the ship reports "no field generator
            // aboard" while the player is looking straight at one, and the only cure is rebuilding
            // the whole craft. The claim is the ownership test; a block outside it was already
            // skipped above, so nothing on a planet or on a neighbouring ship can be adopted.
            if (!component.isLinked() && !world.isRemote) {
                component.linkToFlightComputer(flightComputerPos);
            }
            if (component.belongsTo(flightComputerPos)) {
                found.add(component);
            }
        }
        return found;
    }

    private AxisAlignedBB yardBounds() {
        return VSIntegration.shipyardBoundsAt(world,
                flightComputerPos.getX() + 0.5, flightComputerPos.getY() + 0.5,
                flightComputerPos.getZ() + 0.5);
    }

    /** A ship's blocks never leave their own claim's footprint; a null claim constrains nothing. */
    private static boolean withinXZ(AxisAlignedBB yard, BlockPos pos) {
        return yard == null
                || (pos.getX() >= yard.minX && pos.getX() <= yard.maxX
                        && pos.getZ() >= yard.minZ && pos.getZ() <= yard.maxZ);
    }

    private static boolean touches(BlockPos pos, List<BlockPos> blocks) {
        for (EnumFacing face : EnumFacing.VALUES) {
            if (blocks.contains(pos.offset(face))) {
                return true;
            }
        }
        return false;
    }
}
