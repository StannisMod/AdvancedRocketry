package zmaster587.advancedRocketry.wirelessdata;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

public class WirelessNetworkSavedData extends WorldSavedData {

    public static final String DATA_NAME = "advancedRocketryWirelessNetworks";

    private int nextNetworkId = 1;
    private final Map<Integer, Integer> redirects = new HashMap<>();

    public WirelessNetworkSavedData() {
        super(DATA_NAME);
    }

    public WirelessNetworkSavedData(String name) {
        super(name);
    }

    public static WirelessNetworkSavedData get(World world) {
        WorldServer overworld = world.getMinecraftServer().getWorld(0);
        MapStorage storage = overworld.getPerWorldStorage();

        WirelessNetworkSavedData data =
                (WirelessNetworkSavedData) storage.getOrLoadData(WirelessNetworkSavedData.class, DATA_NAME);

        if (data == null) {
            data = new WirelessNetworkSavedData();
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }

        return data;
    }

    public int getNextNetworkId() {
        return nextNetworkId;
    }

    public void setNextNetworkId(int nextNetworkId) {
        this.nextNetworkId = Math.max(1, nextNetworkId);
        markDirty();
    }

    public Map<Integer, Integer> getRedirectsCopy() {
        return new HashMap<>(redirects);
    }

    public void setRedirects(Map<Integer, Integer> newRedirects) {
        redirects.clear();
        redirects.putAll(newRedirects);
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        nextNetworkId = Math.max(1, nbt.getInteger("nextNetworkId"));

        redirects.clear();
        NBTTagCompound redirectTag = nbt.getCompoundTag("redirects");
        for (String key : redirectTag.getKeySet()) {
            redirects.put(Integer.parseInt(key), redirectTag.getInteger(key));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextNetworkId", nextNetworkId);

        NBTTagCompound redirectTag = new NBTTagCompound();
        for (Entry<Integer, Integer> entry : redirects.entrySet()) {
            redirectTag.setInteger(Integer.toString(entry.getKey()), entry.getValue());
        }
        nbt.setTag("redirects", redirectTag);

        return nbt;
    }
}