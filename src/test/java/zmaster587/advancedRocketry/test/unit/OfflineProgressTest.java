package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.space.OfflineProgress;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract of the offline-progress gate: unmanned transits always advance; {@code always} advances
 * regardless of crew connectivity; {@code crew-online} advances iff at least one aboard crew member is
 * online. Pure — the online check is injected.
 */
public class OfflineProgressTest {

    /** Online-check backed by a fixed set of "connected" player ids. */
    private static OfflineProgress.OnlineCheck onlineSet(UUID... online) {
        final Set<UUID> set = new HashSet<>(Arrays.asList(online));
        return set::contains;
    }

    @Test
    public void unmannedAlwaysAdvancesInEitherMode() {
        OfflineProgress always = new OfflineProgress(OfflineProgress.Mode.ALWAYS, onlineSet());
        OfflineProgress crew = new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, onlineSet());
        assertTrue("no crew + ALWAYS", always.advances(Collections.<UUID>emptyList()));
        assertTrue("no crew + CREW_ONLINE", crew.advances(Collections.<UUID>emptyList()));
        assertTrue("null crew is unmanned", crew.advances(null));
    }

    @Test
    public void alwaysModeAdvancesEvenWithEveryCrewOffline() {
        OfflineProgress p = new OfflineProgress(OfflineProgress.Mode.ALWAYS, onlineSet()); // nobody online
        List<UUID> crew = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());
        assertTrue(p.advances(crew));
    }

    @Test
    public void crewOnlinePausesWhenEveryCrewOffline() {
        List<UUID> crew = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());
        OfflineProgress p = new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, onlineSet()); // nobody online
        assertFalse("crew-online + all crew offline -> paused", p.advances(crew));
    }

    @Test
    public void crewOnlineAdvancesWhenAnyCrewOnline() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        OfflineProgress p = new OfflineProgress(OfflineProgress.Mode.CREW_ONLINE, onlineSet(b)); // only b online
        assertTrue("gate by ANY crew member being online", p.advances(Arrays.asList(a, b)));
    }

    @Test
    public void parseModeDefaultsToAlwaysOnUnknown() {
        assertEquals(OfflineProgress.Mode.CREW_ONLINE, OfflineProgress.parseMode("crew-online"));
        assertEquals(OfflineProgress.Mode.CREW_ONLINE, OfflineProgress.parseMode("CREW_ONLINE"));
        assertEquals(OfflineProgress.Mode.ALWAYS, OfflineProgress.parseMode("always"));
        assertEquals(OfflineProgress.Mode.ALWAYS, OfflineProgress.parseMode("gibberish"));
        assertEquals(OfflineProgress.Mode.ALWAYS, OfflineProgress.parseMode(null));
    }
}
