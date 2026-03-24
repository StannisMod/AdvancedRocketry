package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.world.World;

public class NetworkRegistry {

    private static HandlerDataNetwork dataNetwork;

    public static void registerDataNetwork(World world) {
        if (dataNetwork != null || world == null || world.isRemote) {
            return;
        }

        WirelessNetworkSavedData saveData = WirelessNetworkSavedData.get(world);
        dataNetwork = new HandlerDataNetwork(saveData);
    }

    public static HandlerDataNetwork dataNetwork(World world) {
        if (dataNetwork == null && world != null && !world.isRemote) {
            registerDataNetwork(world);
        }
        return dataNetwork;
    }

    public static HandlerDataNetwork dataNetwork() {
        return dataNetwork;
    }

    public static void clear() {
        dataNetwork = null;
    }
}