package zmaster587.advancedRocketry.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import zmaster587.advancedRocketry.space.HyperspaceWorld;

/**
 * <b>Which dimension hyperspace is has two answers, and they are not interchangeable.</b> The server
 * knows because it registered the world; a client only ever knows because it was told. Both live in
 * JVM-global statics that outlive a session, so the danger is not that an answer is missing - it is
 * that a stale one is confidently served to the wrong side.
 *
 * <p>What is pinned here is the SERVER's answer, which needs no world to express. The client's half -
 * that a reader on a remote world picks the told value, and that the telling is forgotten when the
 * connection ends - is observable ONLY through a world that reports itself remote, so it belongs to a
 * client test and is deliberately not faked here. An assertion routed through a world-less call
 * would read the server's field and stay green however the client's side behaved.</p>
 */
public class HyperspaceDimIdIsAskedPerSideTest {

    /** A JVM-global that other tests share: leave it exactly as it was found. */
    @Before
    @After
    public void forgetAnyServerId() {
        HyperspaceWorld.forgetServerId();
    }

    @Test
    public void whatAServerReportedIsNeverTheServerSideAnswer() {
        HyperspaceWorld.adoptFromServer(45);

        assertEquals("a dim id learned from a connection must never come back as this server's own:"
                + " nothing was registered here, and answering 45 would point every server-side reader"
                + " - the void that kills a player who is not aboard a ship, the login restore, the"
                + " helm's parked gate - at whatever world happens to sit at 45 in THIS save",
                Integer.MIN_VALUE, HyperspaceWorld.dimId());
    }

    @Test
    public void nothingIsHyperspaceWhenThereIsNoWorldToAskAbout() {
        HyperspaceWorld.adoptFromServer(45);

        assertFalse("a question about no world has one honest answer, and it is not a crash",
                HyperspaceWorld.isHyperspace(null));
    }
}
