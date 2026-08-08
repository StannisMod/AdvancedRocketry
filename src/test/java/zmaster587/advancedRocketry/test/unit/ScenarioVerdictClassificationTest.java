package zmaster587.advancedRocketry.test.unit;

import org.junit.Test;
import zmaster587.advancedRocketry.test.client.Scenario;

import static org.junit.Assert.assertEquals;
import static zmaster587.advancedRocketry.test.client.Scenario.Phase.ARRANGEMENT;
import static zmaster587.advancedRocketry.test.client.Scenario.Phase.CASCADE;
import static zmaster587.advancedRocketry.test.client.Scenario.Phase.CONTRACT;
import static zmaster587.advancedRocketry.test.client.Scenario.Phase.HARNESS;
import static zmaster587.advancedRocketry.test.client.Scenario.Phase.PRECONDITION;

/**
 * The verdict decision table of the shared-harness client tier, pinned without a harness.
 *
 * <p>Why this is a unit test and not an e2e: the shared-harness base class classifies every failure
 * into a phase, and that classification is what lets a red name the broken system in one line. Four
 * of its five outcomes are reachable in an ordinary run. The fifth, {@link Scenario.Phase#CASCADE},
 * is only reachable <b>after the client JVM has died</b> — so exercising it for real costs a
 * deliberately killed harness, which is a one-off experiment and not something a suite can carry.
 * Left there, the branch would be permanently unverified.</p>
 *
 * <p>Extracting the table into {@link Scenario#classify} makes it a pure function over four inputs,
 * and then the whole thing is a table test that runs in milliseconds.</p>
 *
 * <p>What the table protects is the ORDER of the rules, and every case below is a case where the
 * wrong order gives a plausible but harmful answer: a dead-client failure reported as a broken
 * contract sends a reader to production code that is fine, and a post-corpse scenario reported as
 * anything other than CASCADE multiplies one root cause into N wrong diagnoses.</p>
 */
public class ScenarioVerdictClassificationTest {

    private static final Throwable ORDINARY = new AssertionError("an ordinary assertion");
    private static final Throwable ARRANGEMENT_FAILURE =
            new Scenario.ArrangementFailure("the fixture did not build");

    // ── rule 1: the group is already down, and that outranks everything ───────

    @Test
    public void aScenarioAfterTheCorpseIsCascadeWhateverItHadDeclared() {
        assertEquals("a scenario that ran after the harness died never met the product;"
                        + " reporting its declared phase would multiply one root cause into many",
                CASCADE,
                Scenario.classify(ORDINARY, Scenario.declaring(CONTRACT), false, true));
    }

    @Test
    public void cascadeOutranksEvenAnArrangementFailure() {
        assertEquals(CASCADE,
                Scenario.classify(ARRANGEMENT_FAILURE, Scenario.declaring(ARRANGEMENT), false, true));
    }

    @Test
    public void cascadeHoldsEvenIfTheHarnessAnswersAgain() {
        // A client can come back (a reconnect, a restarted bridge) without the group's first
        // failure becoming any more meaningful. Once down, the group's later reds stay CASCADE.
        assertEquals(CASCADE,
                Scenario.classify(ORDINARY, Scenario.declaring(CONTRACT), true, true));
    }

    // ── rule 2: a dead harness is never a contract violation ─────────────────

    @Test
    public void aFailureOnADeadClientIsHarnessNotContract() {
        assertEquals("a red raised while the client is dying is not evidence about production,"
                        + " however confidently the scenario had declared CONTRACT",
                HARNESS,
                Scenario.classify(ORDINARY, Scenario.declaring(CONTRACT), false, false));
    }

    @Test
    public void aDeadHarnessOutranksAnArrangementFailure() {
        assertEquals(HARNESS,
                Scenario.classify(ARRANGEMENT_FAILURE, Scenario.declaring(ARRANGEMENT), false, false));
    }

    // ── rule 3: an arrangement failure beats the declared phase ──────────────

    @Test
    public void anArrangementFailureBeatsTheDeclaredPhase() {
        // A helper can throw one while the scenario has already moved on to declaring CONTRACT.
        assertEquals(ARRANGEMENT,
                Scenario.classify(ARRANGEMENT_FAILURE, Scenario.declaring(CONTRACT), true, false));
    }

    // ── rule 4: no scenario means it broke before or inside the shared setup ─

    @Test
    public void aFailureWithNoScenarioIsHarness() {
        assertEquals(HARNESS, Scenario.classify(ORDINARY, null, true, false));
    }

    // ── rule 5: otherwise the scenario's own DECLARED phase ──────────────────

    @Test
    public void aLiveRunReportsThePhaseTheScenarioDeclared() {
        assertEquals(CONTRACT,
                Scenario.classify(ORDINARY, Scenario.declaring(CONTRACT), true, false));
        assertEquals(ARRANGEMENT,
                Scenario.classify(ORDINARY, Scenario.declaring(ARRANGEMENT), true, false));
        assertEquals(PRECONDITION,
                Scenario.classify(ORDINARY, Scenario.declaring(PRECONDITION), true, false));
    }

    /**
     * The table's own sensitivity check: the four inputs must actually MOVE the answer. A
     * classifier that returned one constant would satisfy several cases above by luck, so assert
     * that the same throwable and the same declared phase produce three different verdicts as only
     * the two booleans change.
     */
    @Test
    public void theSameFailureGetsThreeDifferentVerdictsAsOnlyTheStateChanges() {
        Scenario declared = Scenario.declaring(CONTRACT);
        assertEquals(CONTRACT, Scenario.classify(ORDINARY, declared, true, false));
        assertEquals(HARNESS, Scenario.classify(ORDINARY, declared, false, false));
        assertEquals(CASCADE, Scenario.classify(ORDINARY, declared, false, true));
    }
}
