package org.valkyrienskies.mod.common.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.valkyrienskies.mod.common.capability.framework.VSDefaultCapability;
import org.valkyrienskies.mod.common.ships.ship_world.VSWorldData;

/**
 * This sort of class basically only exists because Java generics are trash
 */
public class VSWorldDataCapability extends VSDefaultCapability<VSWorldData> {

    public VSWorldDataCapability(ObjectMapper mapper) {
        super(VSWorldData.class, VSWorldData::new, mapper);
    }

    public VSWorldDataCapability() {
        super(VSWorldData.class, VSWorldData::new);
    }

    /**
     * How many ships this world's registry holds, beside the identity of the collection holding
     * them. The ship count is the only thing that makes a save line readable: an empty payload is
     * indistinguishable from a world that never had a ship, and the collection identity says whether
     * the object being written is the one the rest of the game has been mutating.
     */
    @Override
    protected String describe(VSWorldData instance) {
        return "VSWorldData ships=" + instance.getQueryableShipData().getShips().size()
            + " qsd@" + Integer.toHexString(
            System.identityHashCode(instance.getQueryableShipData()));
    }

}
