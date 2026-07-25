package com.github.stannismod.affs.world.shield;

import com.github.stannismod.affs.te.TileEntityFieldGenerator;
import com.github.stannismod.affs.util.CodeUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The control surface every console (and the test probe) drives — D134-6 Layer 4. A console owns no
 * state of its own: it calls these operations against the domain's authoritative
 * {@link ShieldDomainConfig}, so two consoles on one hull are interchangeable and destroying either
 * changes nothing. Server-side only.
 *
 * <p>Two distinct layers live here and must not be conflated (D134-6): <b>grouping</b> (which emitters
 * share a redistribution priority — Layer 2/4) and the <b>access credential</b> (the rotatable carried
 * code that decides who may pass the field — Layer 3). Rotating the code never touches grouping, and
 * regrouping never touches the code.</p>
 */
public final class ShieldControl {

    private ShieldControl() {
    }

    /** The domain config for the block at {@code pos}, creating an empty one on first use. */
    public static ShieldDomainConfig configFor(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return null;
        }
        String domainId = ShieldDomains.forBlock(world, pos);
        ShieldControlData data = ShieldControlData.get(world);
        return data == null ? null : data.getOrCreate(domainId);
    }

    /**
     * The block's domain config <em>without</em> creating one — for read-only callers (status panels,
     * probes), so merely looking at an emitter never writes persistent state. Null when the player has
     * never made a group in that domain, which is the floor case.
     */
    public static ShieldDomainConfig peekConfig(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return null;
        }
        ShieldControlData data = ShieldControlData.get(world);
        return data == null ? null : data.peek(ShieldDomains.forBlock(world, pos));
    }

    /** Creates (or returns) a named priority group in the block's domain and applies it. */
    public static ShieldPriorityGroup createGroup(World world, BlockPos pos, String name, int priority) {
        ShieldDomainConfig config = configFor(world, pos);
        if (config == null) {
            return null;
        }
        ShieldPriorityGroup group = config.createGroup(name, priority);
        markDirty(world);
        applyGroups(world, pos);
        return group;
    }

    public static boolean deleteGroup(World world, BlockPos pos, String name) {
        ShieldDomainConfig config = configFor(world, pos);
        if (config == null || !config.deleteGroup(name)) {
            return false;
        }
        markDirty(world);
        return true;
    }

    /**
     * Sets a group's priority and pushes it into every member emitter — "all power to the rear shields"
     * as one edit instead of one slider per emitter (D134-5).
     */
    public static boolean setGroupPriority(World world, BlockPos pos, String name, int priority) {
        ShieldDomainConfig config = configFor(world, pos);
        if (config == null) {
            return false;
        }
        ShieldPriorityGroup group = config.getGroup(name);
        if (group == null) {
            return false;
        }
        group.setPriority(priority);
        markDirty(world);
        applyGroups(world, pos);
        return true;
    }

    /** Moves an emitter into a group (an emitter belongs to at most one) and applies the group's priority. */
    public static boolean assignEmitter(World world, BlockPos pos, String name, BlockPos emitterPos) {
        ShieldDomainConfig config = configFor(world, pos);
        if (config == null || config.getGroup(name) == null) {
            return false;
        }
        config.assignMember(name, emitterPos);
        markDirty(world);
        applyGroups(world, pos);
        return true;
    }

    /**
     * Pushes every group's priority into its member emitters. The emitter keeps owning the setting
     * (D134-5) — this only writes it, which is why losing the console leaves the shield tuned as it was.
     */
    public static int applyGroups(World world, BlockPos pos) {
        ShieldDomainConfig config = configFor(world, pos);
        if (config == null) {
            return 0;
        }
        String domainId = config.getDomainId();
        int applied = 0;
        for (TileEntityFieldGenerator emitter : emittersInDomain(world, domainId)) {
            ShieldPriorityGroup group = config.findGroupOf(emitter.getPos());
            if (group == null) {
                continue;
            }
            if (emitter.getShieldPriority() != group.getPriority()) {
                emitter.setPriority(group.getPriority());
            }
            applied++;
        }
        return applied;
    }

    /**
     * Regenerates the domain's access credential (D134-6 Layer 3) and writes it to every emitter in the
     * domain. This is the leak response: the old code stops working immediately, while identity (the
     * ship) and grouping (the priority groups) are untouched. Returns the new code.
     */
    public static String rotateAccessCode(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return "";
        }
        String domainId = ShieldDomains.forBlock(world, pos);
        String newCode = generateCode();
        for (TileEntityFieldGenerator emitter : emittersInDomain(world, domainId)) {
            emitter.applyAccessCode(newCode);
        }
        return newCode;
    }

    /** Every loaded emitter belonging to the given domain. */
    public static List<TileEntityFieldGenerator> emittersInDomain(World world, String domainId) {
        List<TileEntityFieldGenerator> result = new ArrayList<>();
        if (world == null || domainId == null) {
            return result;
        }
        for (TileEntityFieldGenerator emitter : TileEntityFieldGenerator.getActiveGenerators()) {
            if (emitter == null || emitter.isInvalid() || emitter.getWorld() == null) {
                continue;
            }
            if (domainId.equals(ShieldDomains.forBlock(emitter.getWorld(), emitter.getPos()))) {
                result.add(emitter);
            }
        }
        return result;
    }

    private static String generateCode() {
        return CodeUtils.normalize(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private static void markDirty(World world) {
        ShieldControlData data = ShieldControlData.get(world);
        if (data != null) {
            data.markDirty();
        }
    }
}
