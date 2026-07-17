package zmaster587.advancedRocketry.space;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The offline-progress policy for a hyperspace transit: does a parked ship's flight advance this tick?
 * Config {@code spaceTransitOfflineProgress}:
 *
 * <ul>
 *   <li>{@link Mode#ALWAYS} — the flight advances whenever the server is up, regardless of whether the
 *       aboard crew are connected.</li>
 *   <li>{@link Mode#CREW_ONLINE} — the flight advances only while at least ONE aboard crew member is
 *       online; it pauses while every aboard crew member is offline (gate by ANY crew, so a crew member
 *       is never hostage to an offline owner).</li>
 * </ul>
 *
 * <p><b>Unmanned transits ALWAYS advance</b>, in either mode. World time only ticks while the server is
 * up, so "neither mode advances while the server is off" holds by construction (the clock is stopped).
 * The Δ-computation / arrival-tick event queue is a TASK-102 optimization; this policy is the per-tick
 * gate that produces the same result. Pure — no server access beyond the injected {@link OnlineCheck}.</p>
 */
public final class OfflineProgress {

    public enum Mode {
        ALWAYS,
        CREW_ONLINE
    }

    /** Is this player currently connected? (production: {@code server.getPlayerList().getPlayerByUUID}.) */
    public interface OnlineCheck {
        boolean isOnline(UUID player);
    }

    private final Mode mode;
    private final OnlineCheck online;

    public OfflineProgress(Mode mode, OnlineCheck online) {
        this.mode = mode;
        this.online = online;
    }

    /** Whether a transit whose aboard crew is {@code crew} may advance now. */
    public boolean advances(List<UUID> crew) {
        if (crew == null || crew.isEmpty()) {
            return true; // unmanned: always advances
        }
        if (mode == Mode.ALWAYS) {
            return true;
        }
        for (UUID id : crew) {
            if (online.isOnline(id)) {
                return true; // at least one aboard crew member is online
            }
        }
        return false; // crew-online mode, every aboard crew member offline -> pause
    }

    public Mode mode() {
        return mode;
    }

    /** Parse the config string, defaulting to {@link Mode#ALWAYS} on an unknown value. */
    public static Mode parseMode(String value) {
        if (value == null) {
            return Mode.ALWAYS;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("crew-online") || v.equals("crew_online")) {
            return Mode.CREW_ONLINE;
        }
        return Mode.ALWAYS;
    }
}
