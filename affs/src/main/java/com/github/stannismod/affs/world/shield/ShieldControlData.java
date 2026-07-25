package com.github.stannismod.affs.world.shield;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistence for the domain-level shield configuration (D134-6 Layer 4). Holding it in world storage —
 * not in a console tile — is what makes consoles genuinely stateless: the data outlives every console,
 * every chunk unload and the session itself.
 *
 * <p>Stored on the overworld's per-world storage (mirrors AR's own network saved data) so a ship's
 * configuration survives the ship moving between dimensions, which a per-dimension store could not.</p>
 */
public class ShieldControlData extends WorldSavedData {

    public static final String DATA_NAME = "affsShieldControl";

    private final Map<String, ShieldDomainConfig> domains = new HashMap<>();

    public ShieldControlData() {
        super(DATA_NAME);
    }

    public ShieldControlData(String name) {
        super(name);
    }

    public static ShieldControlData get(World world) {
        if (world == null || world.getMinecraftServer() == null) {
            return null;
        }
        WorldServer overworld = world.getMinecraftServer().getWorld(0);
        if (overworld == null) {
            return null;
        }
        MapStorage storage = overworld.getPerWorldStorage();
        ShieldControlData data = (ShieldControlData) storage.getOrLoadData(ShieldControlData.class, DATA_NAME);
        if (data == null) {
            data = new ShieldControlData();
            storage.setData(DATA_NAME, data);
            data.markDirty();
        }
        return data;
    }

    /** The domain's configuration, creating an empty one on first use (zero groups = the floor). */
    public ShieldDomainConfig getOrCreate(String domainId) {
        if (domainId == null || domainId.isEmpty()) {
            return null;
        }
        ShieldDomainConfig config = domains.get(domainId);
        if (config == null) {
            config = new ShieldDomainConfig(domainId);
            domains.put(domainId, config);
            markDirty();
        }
        return config;
    }

    /** The domain's configuration, or null when the player never created a group there. */
    public ShieldDomainConfig peek(String domainId) {
        return domainId == null ? null : domains.get(domainId);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        domains.clear();
        NBTTagList list = nbt.getTagList("domains", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ShieldDomainConfig config = ShieldDomainConfig.readFromNBT(list.getCompoundTagAt(i));
            if (config != null) {
                domains.put(config.getDomainId(), config);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        NBTTagList list = new NBTTagList();
        for (ShieldDomainConfig config : domains.values()) {
            list.appendTag(config.writeToNBT());
        }
        compound.setTag("domains", list);
        return compound;
    }
}
