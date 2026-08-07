package zmaster587.advancedRocketry.test.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scenario's own flight recorder, and the thing that makes its failure readable.
 *
 * <p>The problem this solves is not "the test went red", it is "the log cannot say WHICH SYSTEM is
 * broken". A client e2e has at least four ways to fail and they demand opposite responses — the
 * harness died, the arrangement never got built, the arrangement got built but never reached the
 * state the contract is about, or the contract itself is violated. Today all four arrive as one
 * {@code AssertionError} and a human has to open the source to tell them apart.</p>
 *
 * <p>So the PHASE is <b>declared</b> by the test as it runs, never guessed afterwards, and the
 * failure line carries it along with the subsystem the scenario claims to be about. A reader who has
 * never seen the file learns from one line whether to look at production, at the fixture, or at the
 * box the run happened on.</p>
 *
 * <pre>
 * E2E verdict=CONTRACT subsystem=seal-detector scenario=stoneFixture... step="read the player's chat"
 * </pre>
 *
 * <p>Two rules the phases encode:</p>
 * <ul>
 *   <li>{@link Phase#ARRANGEMENT} failures must not read as product bugs. A fixture that did not
 *       build means the contract <em>was never tested</em> — the correct response is to fix the
 *       scaffolding and re-run, not to argue with production.</li>
 *   <li>{@link Phase#PRECONDITION} exists because a threshold test that never reached its threshold
 *       is indistinguishable, from the verdict alone, from one whose guard held. Record the
 *       mechanism's measured INPUT under this phase before the verdict, and the two separate.</li>
 * </ul>
 */
public final class Scenario {

    public enum Phase {
        /** Boot, socket, the client JVM died. Not a statement about the product. */
        HARNESS,
        /** Fixture, teleport, mount, load — everything that carries the subject into place. */
        ARRANGEMENT,
        /** The measured input the contract's threshold compares against. */
        PRECONDITION,
        /** The verdict itself. This is the only phase whose red means "a bug". */
        CONTRACT,
        /** A scenario that ran after a group-fatal failure; read the first red instead. */
        CASCADE
    }

    /** Thrown by arrangement helpers so a scaffolding failure cannot be mistaken for a verdict. */
    public static final class ArrangementFailure extends AssertionError {
        private static final long serialVersionUID = 1L;

        public ArrangementFailure(String message) {
            super(message);
        }
    }

    private final String name;
    private final String subsystem;
    private final Plot plot;
    private final List<String> journal = new ArrayList<>();
    /** Probe commands re-run on failure to describe the world at the moment it broke. */
    private final List<String> stateBundle = new ArrayList<>();
    private final Map<String, String> recorded = new LinkedHashMap<>();

    private Phase phase = Phase.ARRANGEMENT;
    private String step = "(not started)";
    private final long startedNanos = System.nanoTime();

    Scenario(String name, String subsystem, Plot plot) {
        this.name = name;
        this.subsystem = subsystem;
        this.plot = plot;
    }

    /**
     * Declare the phase the scenario is now in and name the step. Everything that follows is
     * attributed here until the next call.
     */
    public Scenario step(Phase newPhase, String description) {
        this.phase = newPhase;
        this.step = description;
        journal.add(String.format("%6d ms  %-12s %s",
                (System.nanoTime() - startedNanos) / 1_000_000L, newPhase, description));
        return this;
    }

    public Scenario arranging(String description) {
        return step(Phase.ARRANGEMENT, description);
    }

    public Scenario measuring(String description) {
        return step(Phase.PRECONDITION, description);
    }

    public Scenario asserting(String description) {
        return step(Phase.CONTRACT, description);
    }

    /**
     * Record a value into the journal. Use it for the numbers a reader would otherwise have to
     * re-run the test to see — a measured input, a probe response, a chosen coordinate.
     */
    public Scenario record(String key, Object value) {
        String rendered = String.valueOf(value);
        recorded.put(key, rendered);
        journal.add(String.format("%6d ms  %-12s   %s = %s",
                (System.nanoTime() - startedNanos) / 1_000_000L, "·", key, rendered));
        return this;
    }

    /**
     * Register a probe command to be executed and printed if this scenario fails. This is the
     * scenario's own choice of what "describe the world" means for its subsystem — a generic bundle
     * cannot know, and a probe reports the SERVER's answer, so pick ones that can speak to the
     * symptom.
     */
    public Scenario describeOnFailureWith(String... artestCommands) {
        for (String c : artestCommands) {
            stateBundle.add(c);
        }
        return this;
    }

    /** Fails the scenario as an ARRANGEMENT problem — the contract was never reached. */
    public void arrangementFailed(String message) {
        throw new ArrangementFailure(message);
    }

    /** Fails the scenario as an ARRANGEMENT problem unless {@code condition} holds. */
    public void requireArranged(String message, boolean condition) {
        if (!condition) {
            arrangementFailed(message);
        }
    }

    public Phase phase() {
        return phase;
    }

    public String name() {
        return name;
    }

    public String subsystem() {
        return subsystem;
    }

    public Plot plot() {
        return plot;
    }

    List<String> stateBundle() {
        return stateBundle;
    }

    /** The one machine-readable line a triaging reader greps for. */
    String verdictLine(Phase effectivePhase) {
        return "E2E verdict=" + effectivePhase
                + " subsystem=" + subsystem
                + " scenario=" + name
                + " step=\"" + step + "\""
                + " plot=" + (plot == null ? "none" : "#" + plot.index());
    }

    String renderJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- scenario journal: ").append(name).append(" ---\n");
        for (String line : journal) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
