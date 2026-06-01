package zmaster587.advancedRocketry.wirelessdata;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class HandlerDataNetwork {

    private static final int ACTIVE_INTERVAL_TICKS = 10;
    private static final int IDLE_INTERVAL_TICKS = 20;
    private static final int COLD_INTERVAL_TICKS = 100;

    private static final long IDLE_BACKOFF_THRESHOLD_TICKS = 200L;
    private static final long COLD_BACKOFF_THRESHOLD_TICKS = 2000L;

    private final Map<Integer, DataNetwork> networks = new HashMap<>();
    private final Map<Integer, Integer> redirects = new HashMap<>();
    private final WirelessNetworkSavedData saveData;

    private int nextNetworkId = 1;

    /**
     * Internal scheduler time for network scheduling/backoff.
     * This does not need persistence; it is only used to space runtime work.
     */
    private long schedulerTick = 0L;

    public HandlerDataNetwork(WirelessNetworkSavedData saveData) {
        this.saveData = saveData;

        if (saveData != null) {
            this.nextNetworkId = Math.max(1, saveData.getNextNetworkId());
            this.redirects.putAll(saveData.getRedirectsCopy());
        }
    }

    public int getNewNetworkID() {
        int id = allocateNextNetworkId();
        networks.put(id, DataNetwork.createWithID(id));
        return id;
    }

    public int getNewNetworkID(int id) {
        int resolved = resolveNetworkID(id);

        if (!networks.containsKey(resolved)) {
            networks.put(resolved, DataNetwork.createWithID(resolved));
        }

        if (resolved >= nextNetworkId) {
            nextNetworkId = resolved + 1;
            persistState();
        }

        return resolved;
    }

    public int resolveNetworkID(int id) {
        if (id <= 0) {
            return id;
        }

        Integer next = redirects.get(id);
        if (next == null) {
            return id;
        }

        int current = id;
        Set<Integer> visited = new java.util.HashSet<>();

        while (true) {
            if (!visited.add(current)) {
                // Corrupt redirect cycle; break safely and return original id.
                return id;
            }

            next = redirects.get(current);
            if (next == null) {
                break;
            }

            current = next;
        }

        if (current != id) {
            redirects.put(id, current);
            persistState();
        }

        return current;
    }

    public DataNetwork getNetwork(int id) {
        return networks.get(resolveNetworkID(id));
    }

    public void removeIfEmpty(int id) {
        int resolved = resolveNetworkID(id);
        DataNetwork network = networks.get(resolved);

        if (network != null && network.isEmpty()) {
            networks.remove(resolved);
        }
    }

    public void tickAllNetworks() {
        schedulerTick++;

        for (Entry<Integer, DataNetwork> entry : networks.entrySet()) {
            int networkId = entry.getKey();
            DataNetwork network = entry.getValue();

            // Keep runtime eligibility state in sync with loaded source/sink membership.
            network.updateSchedulingState(schedulerTick);

            // Skip one-sided networks entirely. This is stricter than the old behavior,
            // where they were still visited and then early-returned in DataNetwork.tick().
            if (!network.hasSourcesAndSinks()) {
                continue;
            }

            long idleAge = network.getIdleAge(schedulerTick);
            int interval = getIntervalForIdleAge(idleAge);

            if (!shouldTickNetwork(networkId, interval)) {
                continue;
            }

            // Scale transfer budget with the interval so average wireless throughput
            // stays roughly aligned with the previous 1-per-tick behavior.
            boolean moved = network.tick(interval);

            if (moved) {
                // Any successful move restores the network immediately to active cadence,
                // because future idle age is now measured from this tick.
                network.noteSuccessfulTransfer(schedulerTick);
            }
        }
    }

    private int getIntervalForIdleAge(long idleAge) {
        if (idleAge >= COLD_BACKOFF_THRESHOLD_TICKS) {
            return COLD_INTERVAL_TICKS;
        }

        if (idleAge >= IDLE_BACKOFF_THRESHOLD_TICKS) {
            return IDLE_INTERVAL_TICKS;
        }

        return ACTIVE_INTERVAL_TICKS;
    }

    private boolean shouldTickNetwork(int networkId, int interval) {
        if (interval <= 1) {
            return true;
        }

        // Phase by network id to spread work across ticks and avoid spikes when many
        // networks share the same interval bucket.
        return Math.floorMod(schedulerTick + networkId, (long) interval) == 0L;
    }

    private int allocateNextNetworkId() {
        while (networks.containsKey(nextNetworkId) || redirects.containsKey(nextNetworkId)) {
            nextNetworkId++;
        }

        int id = nextNetworkId++;
        persistState();
        return id;
    }

    private void persistState() {
        if (saveData != null) {
            saveData.setNextNetworkId(nextNetworkId);
            saveData.setRedirects(redirects);
        }
    }
}