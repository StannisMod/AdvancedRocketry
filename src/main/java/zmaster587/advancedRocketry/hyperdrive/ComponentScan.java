package zmaster587.advancedRocketry.hyperdrive;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * How a machine of this family measures itself: it walks outward from its controller through the
 * component blocks welded to it and counts what it finds.
 *
 * <p>This is the whole of the "bigger machine, better machine" lever. A fixed structure template
 * cannot express it — a template is one size by definition — so the machines here are scanned
 * rather than pattern-matched, exactly the way a rocket's stats are scanned off its build.</p>
 *
 * <p>The walk is deliberately dumb: six-neighbour connectivity, one shared budget, and a hard cap on
 * how many blocks it will visit. The cap is not a balance number that happens to be enforced here —
 * it is what keeps a scan on a pilot's key press from walking a whole ship.</p>
 */
public final class ComponentScan {

    /** What is at a position, as far as a scan is concerned. Lets the walk be tested with no world. */
    public interface Component {
        /**
         * The kind of component at {@code pos}, or {@code null} when nothing there belongs to this
         * machine. Kinds are free-form: the caller decides what it is counting.
         */
        String kindAt(BlockPos pos);
    }

    /** The tally: how many of each kind the walk reached, and whether it ran out of budget. */
    public static final class Result {
        private final Map<String, Integer> counts;
        private final boolean truncated;

        Result(Map<String, Integer> counts, boolean truncated) {
            this.counts = counts;
            this.truncated = truncated;
        }

        public int count(String kind) {
            Integer n = counts.get(kind);
            return n == null ? 0 : n;
        }

        /** Every component reached, of every kind. */
        public int total() {
            int total = 0;
            for (Integer n : counts.values()) {
                total += n;
            }
            return total;
        }

        /** Whether the cap stopped the walk before it ran out of connected blocks. */
        public boolean truncated() {
            return truncated;
        }

        @Override
        public String toString() {
            return "ComponentScan.Result" + counts + (truncated ? "(capped)" : "");
        }
    }

    private ComponentScan() {
    }

    /**
     * Walk out from {@code origin} — which is the controller, and is never counted — through every
     * connected component block, visiting at most {@code cap} of them.
     */
    public static Result from(BlockPos origin, Component world, int cap) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (origin == null || world == null || cap <= 0) {
            return new Result(counts, false);
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        seen.add(origin);
        frontier.add(origin);
        boolean truncated = false;
        int counted = 0;
        while (!frontier.isEmpty()) {
            BlockPos current = frontier.poll();
            for (EnumFacing face : EnumFacing.VALUES) {
                BlockPos next = current.offset(face);
                if (!seen.add(next)) {
                    continue;
                }
                String kind = world.kindAt(next);
                if (kind == null) {
                    continue; // not part of this machine; the walk stops at its own edge
                }
                if (counted >= cap) {
                    truncated = true;
                    continue; // keep draining the frontier so the answer stays deterministic
                }
                counted++;
                Integer prior = counts.get(kind);
                counts.put(kind, prior == null ? 1 : prior + 1);
                frontier.add(next);
            }
        }
        return new Result(counts, truncated);
    }
}
