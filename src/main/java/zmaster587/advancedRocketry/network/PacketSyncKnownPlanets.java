package zmaster587.advancedRocketry.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.libVulpes.network.BasePacket;

import java.util.HashSet;
import java.util.Set;

public class PacketSyncKnownPlanets extends BasePacket {

    public int stationId;
    private Set<Integer> knownPlanets;

    public PacketSyncKnownPlanets() {
    }

    public PacketSyncKnownPlanets(int stationId, Set<Integer> knownPlanets) {
        this.stationId = stationId;
        this.knownPlanets = knownPlanets;
        this.knownPlanets.addAll(DimensionManager.getInstance().knownPlanets);
    }

    @Override
    public void write(ByteBuf out) {
        PacketBuffer buffer = new PacketBuffer(out);
        buffer.writeInt(stationId);
        buffer.writeInt(knownPlanets.size());
        for (Integer planetId : knownPlanets) {
            buffer.writeInt(planetId);
        }
    }

    @Override
    public void readClient(ByteBuf in) {
        PacketBuffer buffer = new PacketBuffer(in);
        stationId = buffer.readInt();
        int size = buffer.readInt();
        knownPlanets = new HashSet<>();

        for (int i = 0; i < size; i++) {
            knownPlanets.add(buffer.readInt());
        }
    }

    @Override
    public void read(ByteBuf in) {
    }

    @Override
    public void executeClient(EntityPlayer player) {
        SpaceStationObject spaceObject = (SpaceStationObject) SpaceObjectManager.getSpaceManager().getSpaceStation(stationId);
        if (spaceObject != null) {
            spaceObject.getKnownPlanetList().clear();
            spaceObject.getKnownPlanetList().addAll(knownPlanets);
        }
    }

    @Override
    public void executeServer(EntityPlayerMP player) {
    }
}
