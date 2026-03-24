package zmaster587.advancedRocketry.wirelessdata;


import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class HandlerDataNetwork {

    private final Map<Integer, DataNetwork> networks = new HashMap<>();
    private final Map<Integer, Integer> redirects = new HashMap<>();
    private final WirelessNetworkSavedData saveData;

    private int nextNetworkId = 1;


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
        for (DataNetwork network : networks.values()) {
            network.tick();
        }
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