package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraft.world.DimensionType;

import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.space.HyperspaceWorld;
import zmaster587.advancedRocketry.space.SpaceSlotPool;
import zmaster587.advancedRocketry.space.WorldProviderSpaceSlot;
import zmaster587.libVulpes.network.BasePacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Server&rarr;client sync of the space-subsystem slot dimensions (the pool slots + the shared
 * hyperspace world). Slot dims are registered SERVER-side at pool registration; on a dedicated
 * server the client knows nothing about them, so moving a player into one (entry, descent, login
 * restore, station docking) would respawn him into a dimension his Forge {@code DimensionManager}
 * has never heard of. This packet registers the slot {@link DimensionType} and the current slot dim
 * ids client-side. It is sent to each player at login and broadcast whenever the pool (re)registers
 * or grows — always BEFORE anything can relocate a player into a slot (the sequencing contract).
 *
 * <p>Wire contract (same-version): the server's slot {@code DimensionType} id, then a
 * count-prefixed list of dim ids, then WHICH of them is hyperspace ({@link Integer#MIN_VALUE} when
 * it is not registered). The client registers the type under the SERVER's id; a client-side id
 * collision means a mismatched client/server mod set and is logged, never masked. On an integrated
 * server both sides share the JVM-global registration, so the registration half is a no-op.</p>
 *
 * <p><b>Why hyperspace is named separately.</b> The list alone says which dims are slot worlds and
 * nothing about which one is the transit host — and the client needs exactly that to know it is
 * flying a jump rather than parked in a cell, because hyperspace and the cells share one
 * {@link WorldProviderSpaceSlot}. The backdrop used to be gated on the seat entity's synced jump
 * phase instead, which meant standing up emptied the sky.</p>
 */
public class PacketSlotDimSync extends BasePacket {

    /** Must match the server-side registration name in {@link SpaceSlotPool#registerPool}. */
    private static final String SLOT_TYPE_NAME = "arspacepoolslot";

    private int typeId = Integer.MIN_VALUE;
    private List<Integer> dims = new ArrayList<>();
    private int hyperDim = Integer.MIN_VALUE;

    public PacketSlotDimSync() {
    }

    /** The current pool snapshot: every registered slot dim + the hyperspace dim when registered. */
    public static PacketSlotDimSync current() {
        PacketSlotDimSync p = new PacketSlotDimSync();
        p.typeId = SpaceSlotPool.slotType == null ? Integer.MIN_VALUE : SpaceSlotPool.slotType.getId();
        p.dims.addAll(SpaceSlotPool.slotDims());
        int hyper = HyperspaceWorld.dimId();
        if (hyper != Integer.MIN_VALUE) {
            p.dims.add(hyper);
        }
        p.hyperDim = hyper;
        return p;
    }

    /** Nothing to sync (no pool registered yet). */
    public boolean isEmpty() {
        return typeId == Integer.MIN_VALUE || dims.isEmpty();
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeInt(typeId);
        buffer.writeInt(dims.size());
        for (Integer d : dims) {
            buffer.writeInt(d);
        }
        buffer.writeInt(hyperDim);
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        typeId = buffer.readInt();
        int n = buffer.readInt();
        dims = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dims.add(buffer.readInt());
        }
        hyperDim = buffer.readInt();
    }

    @Override
    public void read(ByteBuf in) {
        // never read on the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        // Ahead of every guard below, and deliberately: which dim is hyperspace is independent of the
        // DimensionType negotiation, and the sky's gate should not be lost to a mod-set mismatch it has
        // nothing to do with.
        HyperspaceWorld.rememberOnClient(hyperDim);
        if (typeId == Integer.MIN_VALUE) {
            return;
        }
        if (SpaceSlotPool.slotType == null) {
            DimensionType existing = null;
            for (DimensionType t : DimensionType.values()) {
                if (t.getId() == typeId) {
                    existing = t;
                    break;
                }
            }
            if (existing != null) {
                if (SLOT_TYPE_NAME.equals(existing.getName())) {
                    SpaceSlotPool.slotType = existing; // already registered in this JVM
                } else {
                    AdvancedRocketry.logger.error("[SPACE] client DimensionType id {} is already taken by "
                            + "'{}' - client/server mod sets differ; slot dims will NOT register",
                            typeId, existing.getName());
                    return;
                }
            } else {
                SpaceSlotPool.slotType = DimensionType.register(
                        SLOT_TYPE_NAME, SLOT_TYPE_NAME, typeId, WorldProviderSpaceSlot.class, false);
            }
        } else if (SpaceSlotPool.slotType.getId() != typeId) {
            AdvancedRocketry.logger.error("[SPACE] slot DimensionType id mismatch: client {} vs server {} - "
                    + "slot dims will NOT register", SpaceSlotPool.slotType.getId(), typeId);
            return;
        }
        for (Integer d : dims) {
            if (!net.minecraftforge.common.DimensionManager.isDimensionRegistered(d)) {
                net.minecraftforge.common.DimensionManager.registerDimension(d, SpaceSlotPool.slotType);
            }
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
