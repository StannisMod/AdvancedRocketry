package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import zmaster587.advancedRocketry.tile.TileWirelessTransceiver;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class HandlerDataNetwork {

    private final Map<Integer, DataNetwork> networks = new HashMap<>();
    private final Map<Integer, Integer> redirects = new HashMap<>();
    private final WirelessNetworkSavedData saveData;

    private int nextNetworkId = 1;

    public HandlerDataNetwork() {
        this(null);
    }

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

    public boolean doesNetworkExist(int id) {
        return networks.containsKey(resolveNetworkID(id));
    }

    public DataNetwork getNetwork(int id) {
        return networks.get(resolveNetworkID(id));
    }

    public void removeNetworkByID(int id) {
        networks.remove(resolveNetworkID(id));
    }

    public int mergeNetworks(int a, int b) {
        a = resolveNetworkID(a);
        b = resolveNetworkID(b);

        if (a == b) {
            return a;
        }

        boolean hasA = networks.containsKey(a);
        boolean hasB = networks.containsKey(b);

        if (!hasA && !hasB) {
            return getNewNetworkID();
        }
        if (!hasA) {
            return b;
        }
        if (!hasB) {
            return a;
        }

        int keep = Math.min(a, b);
        int remove = Math.max(a, b);

        DataNetwork keepNet = networks.get(keep);
        DataNetwork removeNet = networks.get(remove);

        if (keepNet == null || removeNet == null) {
            return keep;
        }

        remapMemberTiles(removeNet, keep);

        keepNet.merge(removeNet);
        networks.remove(remove);

        redirects.put(remove, keep);
        persistState();

        return keep;
    }

    public void tickAllNetworks() {
        for (DataNetwork network : networks.values()) {
            network.tick();
        }
    }

    public int getNextNetworkId() {
        return nextNetworkId;
    }

    public void setNextNetworkId(int nextNetworkId) {
        this.nextNetworkId = Math.max(1, nextNetworkId);
        persistState();
    }

    private int allocateNextNetworkId() {
        while (networks.containsKey(nextNetworkId) || redirects.containsKey(nextNetworkId)) {
            nextNetworkId++;
        }

        int id = nextNetworkId++;
        persistState();
        return id;
    }

    private void remapMemberTiles(DataNetwork network, int newId) {
        Set<TileWirelessTransceiver> seen =
                Collections.newSetFromMap(new IdentityHashMap<TileWirelessTransceiver, Boolean>());

        remapEntries(network.getSources(), newId, seen);
        remapEntries(network.getSinks(), newId, seen);
    }

    private void remapEntries(
            Set<Entry<TileEntity, EnumFacing>> entries,
            int newId,
            Set<TileWirelessTransceiver> seen
    ) {
        for (Entry<TileEntity, EnumFacing> entry : entries) {
            TileEntity tile = entry.getKey();
            if (!(tile instanceof TileWirelessTransceiver)) {
                continue;
            }

            TileWirelessTransceiver transceiver = (TileWirelessTransceiver) tile;
            if (seen.add(transceiver)) {
                transceiver.setWirelessNetworkId(newId);
            }
        }
    }

    private void persistState() {
        if (saveData != null) {
            saveData.setNextNetworkId(nextNetworkId);
            saveData.setRedirects(redirects);
        }
    }
}