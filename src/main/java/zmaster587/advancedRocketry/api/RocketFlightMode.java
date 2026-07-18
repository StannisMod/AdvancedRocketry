package zmaster587.advancedRocketry.api;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Selects how an EntityRocket behaves during flight.
 *
 * CLASSIC_LAUNCH — pre-existing scripted vertical launch / orbit / descent flow.
 *                  Default for newly-spawned and legacy NBT-loaded rockets.
 * FREE_FLIGHT    — opt-in: rocket behaves as a player-piloted vehicle with
 *                  arcade-style thrust / yaw / pitch handling on the server.
 */
public enum RocketFlightMode {
    CLASSIC_LAUNCH,
    FREE_FLIGHT;

    public static final String NBT_KEY = "flightMode";

    /** Default behaviour when the NBT field is missing (legacy save). */
    public static final RocketFlightMode DEFAULT = CLASSIC_LAUNCH;

    /**
     * Read mode from NBT; missing or unparseable value &rarr; {@link #DEFAULT}.
     * Tolerant of forward-compat: unknown enum names fall back to default
     * instead of throwing, so a save written by a newer mod version still
     * loads (degrades to classic).
     */
    public static RocketFlightMode readFromNBT(NBTTagCompound nbt) {
        if (nbt == null || !nbt.hasKey(NBT_KEY)) return DEFAULT;
        String s = nbt.getString(NBT_KEY);
        if (s == null || s.isEmpty()) return DEFAULT;
        for (RocketFlightMode m : values()) {
            if (m.name().equals(s)) return m;
        }
        return DEFAULT;
    }

    public static void writeToNBT(NBTTagCompound nbt, RocketFlightMode mode) {
        if (nbt == null) return;
        nbt.setString(NBT_KEY, (mode == null ? DEFAULT : mode).name());
    }
}
