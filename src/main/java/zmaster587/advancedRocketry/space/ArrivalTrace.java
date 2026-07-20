package zmaster587.advancedRocketry.space;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Position-writer timeline for a pilot around a ship crossing (ungated statics, probe/e2e-readable).
 *
 * <p>An arrival un-seat is a multi-writer symptom: the crew re-seat, the rigid pose teleport, the
 * mount's seat-glue, vanilla's passenger snap and the client's own movement authority may all write
 * the same player's position within a few ticks of a crossing, and fixing any one of them blind
 * only shifts the balance. Before a fix, the timeline must NAME which writers actually fired and in
 * what order. Every explicit write site appends a tagged event here; a per-tick sampler records a
 * player's position/riding whenever either jumps between tick phases (a change between {@code END}
 * and the next {@code START} was written outside the player's own tick — vanilla's passenger snap
 * or a network teleport; a change between {@code START} and {@code END} happened inside it); and
 * mount/dismount events record their CALL STACK, because the dismount's caller is the un-seater's
 * name. Ring-buffered so it is cheap and bounded, and deliberately not test-gated so harness child
 * JVMs (which have no test mode) still carry values.</p>
 */
public final class ArrivalTrace {

    private static final int CAPACITY = 400;

    /** How far (blocks) a per-tick position may move before the sampler records it as a jump.
     *  Powered flight moves a few blocks per tick; teleports move hundreds to millions. */
    private static final double JUMP_THRESHOLD = 16.0;

    /** Server-side events (server main thread; read by the {@code artest vs arrival-trace} probe). */
    public static final Deque<String> SERVER = new ArrayDeque<>();
    /** Client-side events (client thread; read via {@code readStaticField} — toString is the dump). */
    public static final Deque<String> CLIENT = new ArrayDeque<>();

    private ArrivalTrace() { }

    public static void server(String event) {
        add(SERVER, event);
    }

    public static void client(String event) {
        add(CLIENT, event);
    }

    /** Route on the world's side — for write sites that run on both sides (the seat-glue). */
    public static void side(boolean isRemote, String event) {
        add(isRemote ? CLIENT : SERVER, event);
    }

    private static synchronized void add(Deque<String> ring, String event) {
        if (ring.size() >= CAPACITY) {
            ring.removeFirst();
        }
        ring.addLast(event);
    }

    public static synchronized String dumpServer() {
        StringBuilder sb = new StringBuilder();
        for (String e : SERVER) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e);
        }
        return sb.toString();
    }

    /** Compact entity-id list, for tagging which passengers a mount carried through a write. */
    public static String ids(List<Entity> entities) {
        StringBuilder sb = new StringBuilder("[");
        for (Entity e : entities) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(e.getEntityId());
        }
        return sb.append(']').toString();
    }

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * The nearest game-code frames above the event dispatch — the mount/dismount CALLER, which is
     * exactly the writer the timeline exists to name. Infrastructure frames (JRE, the event bus,
     * this class) are skipped; the rest keep {@code Class.method:line} so vanilla and mod callers
     * are distinguishable at a glance.
     */
    public static String callerTrail() {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            String c = f.getClassName();
            if (c.startsWith("java.") || c.startsWith("sun.")
                    || c.contains("ArrivalTrace")
                    || c.contains("EventBus") || c.contains("ForgeEventFactory")
                    || c.contains("ASMEventHandler")) {
                continue;
            }
            if (kept > 0) {
                sb.append(" < ");
            }
            sb.append(c.substring(c.lastIndexOf('.') + 1))
                    .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
            if (++kept >= 5) {
                break;
            }
        }
        return sb.toString();
    }

    /** The event hooks; registered on the common bus (samples both sides, each into its own ring). */
    public static final class Hooks {

        /** Last sampled {y, ridingEntityId} per player, keyed by side + uuid so a single-JVM dev
         *  environment does not conflate the client and server copies of one player. */
        private final Map<String, double[]> lastSample = new HashMap<>();

        @SubscribeEvent
        public void onPlayerTick(TickEvent.PlayerTickEvent event) {
            EntityPlayer player = event.player;
            boolean remote = player.world.isRemote;
            String key = (remote ? "c:" : "s:") + player.getUniqueID();
            Entity riding = player.getRidingEntity();
            int ridingId = riding == null ? -1 : riding.getEntityId();
            double y = player.posY;
            double[] last = lastSample.get(key);
            if (last != null && (Math.abs(y - last[0]) > JUMP_THRESHOLD || ridingId != (int) last[1])) {
                side(remote, "tick." + event.phase
                        + " t=" + player.world.getTotalWorldTime()
                        + " y=" + fmt(last[0]) + "->" + fmt(y)
                        + " riding=" + (int) last[1] + "->" + ridingId);
            }
            lastSample.put(key, new double[]{y, ridingId});
        }

        @SubscribeEvent
        public void onMountChange(EntityMountEvent event) {
            Entity rider = event.getEntityMounting();
            if (!(rider instanceof EntityPlayer)) {
                return;
            }
            Entity mount = event.getEntityBeingMounted();
            side(rider.world.isRemote, (event.isDismounting() ? "dismount" : "mount")
                    + " t=" + rider.world.getTotalWorldTime()
                    + " y=" + fmt(rider.posY)
                    + " mount=" + (mount == null ? -1 : mount.getEntityId())
                    + " mountY=" + (mount == null ? "?" : fmt(mount.posY))
                    + " by=" + callerTrail());
        }
    }
}
