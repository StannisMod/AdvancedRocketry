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
 * <p>Wire contract (same-version): the server's slot {@code DimensionType} id, then the hyperspace
 * dim id ({@code Integer.MIN_VALUE} when hyperspace is not registered), then a count-prefixed list
 * of dim ids. The client registers the type under the SERVER's id; a client-side id collision means
 * a mismatched client/server mod set and is logged, never masked. On an integrated server both
 * sides share the JVM-global registration, so this is a no-op.</p>
 *
 * <p><b>Why hyperspace is NAMED and not merely listed.</b> Every id in the list is registered the
 * same way, so the list says which dimensions exist and not which one is which. The client has its
 * own reason to tell them apart — hyperspace's sky is a transit corridor while a pool slot's is a
 * descent boundary — and it cannot derive it: {@link HyperspaceWorld#register()} is server state,
 * and on a client {@code dimId()} would otherwise never be answerable. One int buys that.</p>
 */
public class PacketSlotDimSync extends BasePacket {

    /** Must match the server-side registration name in {@link SpaceSlotPool#registerPool}. */
    private static final String SLOT_TYPE_NAME = "arspacepoolslot";

    private int typeId = Integer.MIN_VALUE;
    private int hyperDim = Integer.MIN_VALUE;
    private List<Integer> dims = new ArrayList<>();

    public PacketSlotDimSync() {
    }

    /** The current pool snapshot: every registered slot dim + the hyperspace dim when registered. */
    public static PacketSlotDimSync current() {
        PacketSlotDimSync p = new PacketSlotDimSync();
        p.typeId = SpaceSlotPool.slotType == null ? Integer.MIN_VALUE : SpaceSlotPool.slotType.getId();
        p.dims.addAll(SpaceSlotPool.slotDims());
        int hyper = HyperspaceWorld.dimId();
        if (hyper != Integer.MIN_VALUE) {
            // Listed like any other slot dim, because it has to be REGISTERED like one, and named
            // separately because the client also has to tell it apart from one.
            p.dims.add(hyper);
            p.hyperDim = hyper;
        }
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
        buffer.writeInt(hyperDim);
        buffer.writeInt(dims.size());
        for (Integer d : dims) {
            buffer.writeInt(d);
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        typeId = buffer.readInt();
        hyperDim = buffer.readInt();
        int n = buffer.readInt();
        dims = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dims.add(buffer.readInt());
        }
    }

    @Override
    public void read(ByteBuf in) {
        // never read on the server
    }

    @Override
    public void executeClient(EntityPlayer player) {
        // Ahead of the DimensionType handling below, and deliberately: which dimension hyperspace IS
        // does not depend on the type registering cleanly, and the sky renderer's gate reads it. A
        // client that bailed out on a type collision would otherwise draw a descent boundary in the
        // transit corridor on top of everything else that is already wrong.
        HyperspaceWorld.adoptFromServer(hyperDim);
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
