package zmaster587.advancedRocketry.command.test;

import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for the protection mod nobody wants to install to run a test.
 *
 * <h3>What it exists to prove</h3>
 * <p>Weapon fire removes blocks by asking first — it posts a break event and honours a refusal. That
 * contract is only worth anything if something can actually refuse, and everything that would refuse
 * in production (a claim mod, a region plugin, an admin's listener) is a third party we do not ship.
 * So this registers exactly what one of them registers: an ordinary subscriber to
 * {@code BlockEvent.BreakEvent} that cancels for the positions it was told to guard.</p>
 *
 * <p>It is a listener, not a seam: nothing in the damage engine knows this class exists, and the
 * path a test exercises through it is the same path a stranger's mod takes. Test-only, and reachable
 * only through the {@code /artest} command, which the mod refuses to register without its test
 * property.</p>
 */
public final class WeaponFireVetoProbe {

    /** Guarded positions, per dimension. */
    private static final Map<Integer, Set<Long>> GUARDED = new ConcurrentHashMap<>();

    private static volatile boolean listening;

    private WeaponFireVetoProbe() {
    }

    /** Guard, or stop guarding, one position. Answers how many are guarded in that dimension now. */
    public static int guard(int dimension, BlockPos pos, boolean guarded) {
        listen();
        Set<Long> set = GUARDED.get(dimension);
        if (set == null) {
            set = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
            Set<Long> raced = GUARDED.putIfAbsent(dimension, set);
            if (raced != null) {
                set = raced;
            }
        }
        if (guarded) {
            set.add(pos.toLong());
        } else {
            set.remove(pos.toLong());
        }
        return set.size();
    }

    /** Forget every guarded position, in every dimension. */
    public static void clear() {
        GUARDED.clear();
    }

    /** How many positions are guarded in this dimension. */
    public static int count(int dimension) {
        Set<Long> set = GUARDED.get(dimension);
        return set == null ? 0 : set.size();
    }

    private static void listen() {
        if (listening) {
            return;
        }
        synchronized (WeaponFireVetoProbe.class) {
            if (!listening) {
                MinecraftForge.EVENT_BUS.register(new WeaponFireVetoProbe.Handler());
                listening = true;
            }
        }
    }

    /** The subscriber itself, in its own type so the registration is an object like any other. */
    public static final class Handler {

        @SubscribeEvent
        public void onBreak(BlockEvent.BreakEvent event) {
            if (event.getWorld() == null || event.getWorld().isRemote || event.getPos() == null) {
                return;
            }
            Set<Long> set = GUARDED.get(event.getWorld().provider.getDimension());
            if (set != null && set.contains(event.getPos().toLong())) {
                event.setCanceled(true);
            }
        }
    }

    /** The guarded set, copied, for a probe that wants to report it. */
    public static Set<Long> guarded(int dimension) {
        Set<Long> set = GUARDED.get(dimension);
        return set == null ? Collections.<Long>emptySet() : new HashSet<>(set);
    }
}
