package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;

import zmaster587.advancedRocketry.hyperdrive.DriveTuning;
import zmaster587.advancedRocketry.hyperdrive.JumpSpool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two promises the moment before a jump has to keep.
 *
 * <p><b>Aborting is free.</b> The drive winds up, and until the window actually opens the pilot can
 * change his mind at no cost — that is what makes the spool a decision point instead of a delay.</p>
 *
 * <p><b>A warning has to be read to be answered.</b> The press that meets an advisory is the press
 * that raises it; only a second, deliberate press inside a short window commits. One press can never
 * both warn and confirm, and a confirmation cannot ride in behind a warning the pilot has long since
 * forgotten about.</p>
 */
public class JumpSpoolTest {

    @Test
    public void aFreshSpoolIsNeitherWindingUpNorReady() {
        JumpSpool spool = new JumpSpool();

        assertFalse(spool.spooling(0L));
        assertFalse("nothing is due to happen to a drive nobody has touched", spool.ready(0L));
    }

    @Test
    public void theDriveWindsUpAndThenIsReady() {
        JumpSpool spool = new JumpSpool();
        spool.begin(1_000L);

        assertTrue("mid-wind-up", spool.spooling(1_000L + DriveTuning.SPOOL_TICKS / 2));
        assertFalse(spool.ready(1_000L + DriveTuning.SPOOL_TICKS / 2));
        assertTrue("and when it runs out the window is due",
                spool.ready(1_000L + DriveTuning.SPOOL_TICKS));
    }

    @Test
    public void abortingDuringTheWindUpLeavesNothingPending() {
        JumpSpool spool = new JumpSpool();
        spool.begin(1_000L);
        spool.abort();

        assertFalse(spool.spooling(1_010L));
        assertFalse("an aborted jump must never fire later on its own",
                spool.ready(1_000L + DriveTuning.SPOOL_TICKS * 10));
    }

    @Test
    public void aFirstPressNeverConfirmsItsOwnWarning() {
        JumpSpool spool = new JumpSpool();

        assertFalse("nothing has been shown to the pilot yet", spool.confirming(500L));

        spool.warn(500L);

        assertFalse("and the press that raised the warning is not the press that answers it",
                spool.confirming(500L - 1L));
    }

    @Test
    public void asecondPressInsideTheWindowConfirms() {
        JumpSpool spool = new JumpSpool();
        spool.warn(500L);

        assertTrue(spool.confirming(500L + DriveTuning.ADVISORY_CONFIRM_TICKS / 2));
    }

    @Test
    public void aStaleConfirmationDoesNotCount() {
        JumpSpool spool = new JumpSpool();
        spool.warn(500L);

        assertFalse("a pilot who has forgotten the warning has not confirmed it",
                spool.confirming(500L + DriveTuning.ADVISORY_CONFIRM_TICKS + 1L));
    }

    @Test
    public void beginningAWindUpClearsAnOutstandingWarning() {
        // Once the drive is actually winding up the warning has been answered. Leaving it live would
        // let the NEXT jump inherit a confirmation the pilot gave for this one.
        JumpSpool spool = new JumpSpool();
        spool.warn(500L);
        spool.begin(505L);

        assertFalse(spool.confirming(510L));
    }
}
