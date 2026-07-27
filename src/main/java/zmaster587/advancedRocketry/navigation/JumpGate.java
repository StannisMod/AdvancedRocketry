package zmaster587.advancedRocketry.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import zmaster587.advancedRocketry.space.GalacticCoord;

/**
 * Whether a ship may jump, and what the pilot should be told if it may not.
 *
 * <p>The gate is <b>free and read-only</b>: asking costs nothing and consumes nothing, so a refusal is
 * never a loss. Everything expensive happens after it, at the trigger's commit point.</p>
 *
 * <p>It answers in <b>two tiers</b>, because not every objection is a veto:</p>
 * <ul>
 *   <li>{@link Severity#HARD} — physically impossible. The jump does not happen.</li>
 *   <li>{@link Severity#ADVISORY} — possible but ill-advised. The pilot is told what is wrong and may
 *       confirm anyway; flying into a bad idea on purpose is a decision the game leaves to him.</li>
 * </ul>
 *
 * <p>The gate is a <b>composite</b>: this class owns the clauses every jump has (a nav computer
 * aboard, a known position, a target, a drive, the burst that opens the window, the energy for the
 * flight) and the fixed order of the stages, while later subsystems register their own predicates
 * into the stage that belongs to them. Order matters only for which message the pilot reads first —
 * every predicate is free, so all of them run.</p>
 */
public final class JumpGate {

    /** How badly a predicate objects. */
    public enum Severity {
        /** The jump cannot happen at all. */
        HARD,
        /** The jump can happen; the pilot must confirm he means it. */
        ADVISORY
    }

    /**
     * The fixed order in which objections are reported. Navigation first (it is the reason the ship
     * cannot even aim), then the burst that opens the window, then what the flight will cost.
     */
    public enum Stage {
        /** A nav computer aboard, a known position, a target — owned by this class. */
        NAVIGATION,
        /** A field generator aboard, and a window big enough for the hull. */
        DRIVE,
        /** The capacitor burst that opens the jump window. */
        POWER,
        /** Fuel / energy sufficiency for the path. */
        SUPPLY
    }

    /** One objection: what is wrong, how badly, and the message key that says so to the pilot. */
    public static final class Objection {
        private final Severity severity;
        private final String langKey;

        public Objection(Severity severity, String langKey) {
            this.severity = severity;
            this.langKey = langKey;
        }

        public Severity severity() {
            return severity;
        }

        public String langKey() {
            return langKey;
        }

        @Override
        public String toString() {
            return severity + ":" + langKey;
        }
    }

    /** What the ship can answer about itself. Kept minimal so the gate stays unit-testable. */
    public interface ShipContext {
        /** Whether a navigation computer is aboard this ship (and linked to its flight computer). */
        boolean hasNavComputer();

        /**
         * Whether the ship knows where it currently is. False after a misjump, until a re-localization
         * scan completes — which, by contract, ALWAYS succeeds after its time, so this can never
         * softlock a ship into never jumping again.
         */
        boolean positionKnown();

        /** The target the pilot has set — from a crystal or typed by hand — or {@code null}. */
        GalacticCoord target();

        // ─── What the drive can answer ─────────────────────────────────────────
        //
        // These are plain numbers rather than machine objects on purpose: the gate decides whether a
        // ship may jump, and it should not have to know what a capacitor is to do that. Each default
        // is the answer a ship with no drive at all would give, so a context that does not implement
        // them is simply a ship that cannot jump — never one that is waved through.

        /** The field generator's power, or {@code 0} when no generator is aboard. */
        default long drivePower() {
            return 0L;
        }

        /** The energy the capacitor must dump in one moment to open the window. */
        default long burstCost() {
            return 0L;
        }

        /** Charge available across every capacitor aboard, right now. */
        default long capacitorCharge() {
            return 0L;
        }

        /** Hull blocks the jump window fails to enclose; {@code 0} when the hull fits inside it. */
        default long hullOutsideWindow() {
            return 0L;
        }

        /** Energy stored aboard the ship and reachable by the drive. */
        default long storedEnergy() {
            return 0L;
        }

        /** Energy the drive will draw over the whole planned flight. */
        default long flightEnergyCost() {
            return 0L;
        }
    }

    /** A registered check. Returns its objection, or {@code null} when it is satisfied. */
    public interface Predicate {
        Objection check(ShipContext ship);
    }

    /** The verdict: every objection raised, in stage order. */
    public static final class Verdict {
        private final List<Objection> objections;

        Verdict(List<Objection> objections) {
            this.objections = Collections.unmodifiableList(objections);
        }

        /** {@code true} when nothing physically prevents the jump (advisories do not prevent it). */
        public boolean allowed() {
            for (Objection o : objections) {
                if (o.severity() == Severity.HARD) {
                    return false;
                }
            }
            return true;
        }

        /** {@code true} when the jump is possible but the pilot must confirm he means it. */
        public boolean needsConfirmation() {
            return allowed() && !objections.isEmpty();
        }

        /** Every objection, worst-blocking first within the fixed stage order. */
        public List<Objection> objections() {
            return objections;
        }

        /** The message to show the pilot first, or {@code null} when the jump is simply clear. */
        public String firstMessage() {
            for (Objection o : objections) {
                if (o.severity() == Severity.HARD) {
                    return o.langKey();
                }
            }
            return objections.isEmpty() ? null : objections.get(0).langKey();
        }

        @Override
        public String toString() {
            return "Verdict" + objections;
        }
    }

    /** Pilot-facing message keys for the navigation clauses. */
    public static final String MSG_NO_NAV_COMPUTER = "msg.jumpgate.nonavcomputer";
    public static final String MSG_POSITION_UNKNOWN = "msg.jumpgate.positionunknown";
    public static final String MSG_NO_TARGET = "msg.jumpgate.notarget";
    /** No field generator aboard: there is no machine to open a window with. */
    public static final String MSG_NO_DRIVE = "msg.jumpgate.nodrive";
    /** The window does not enclose the whole hull — possible, and it will cost the hull. */
    public static final String MSG_WINDOW_UNDERSIZED = "msg.jumpgate.windowundersized";
    /** The capacitor cannot dump the burst that opens the window. */
    public static final String MSG_CAPACITOR_LOW = "msg.jumpgate.capacitorlow";
    /** Not enough energy aboard for the whole flight — possible, and it may end early. */
    public static final String MSG_ENERGY_SHORTFALL = "msg.jumpgate.energyshortfall";

    private static final Map<Stage, List<Predicate>> REGISTERED = new EnumMap<>(Stage.class);

    static {
        reset();
    }

    private JumpGate() {
    }

    /**
     * Add a predicate to {@code stage}. Subsystems that own a jump precondition register it here rather
     * than building a gate of their own, so there is exactly one place that decides whether a ship may
     * jump — and exactly one order in which the pilot hears about it.
     */
    public static synchronized void register(Stage stage, Predicate predicate) {
        if (stage != null && predicate != null) {
            REGISTERED.get(stage).add(predicate);
        }
    }

    /** Drop every registered predicate and restore the built-in navigation clauses. */
    public static synchronized void reset() {
        REGISTERED.clear();
        for (Stage stage : Stage.values()) {
            REGISTERED.put(stage, new ArrayList<Predicate>());
        }
        REGISTERED.get(Stage.NAVIGATION).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                return ship.hasNavComputer() ? null
                        : new Objection(Severity.HARD, MSG_NO_NAV_COMPUTER);
            }
        });
        REGISTERED.get(Stage.NAVIGATION).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                return ship.positionKnown() ? null
                        : new Objection(Severity.HARD, MSG_POSITION_UNKNOWN);
            }
        });
        REGISTERED.get(Stage.NAVIGATION).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                // A target need not be a KNOWN address - a hand-typed coordinate is a legal, if
                // reckless, destination. What is refused is having no destination at all.
                return ship.target() != null ? null
                        : new Objection(Severity.HARD, MSG_NO_TARGET);
            }
        });
        // The drive clauses are built in rather than registered by the machine subsystem, because the
        // failure mode of a missed registration is the one that must never happen: a gate that has
        // forgotten to ask about the drive waves through a ship with no drive, silently and forever.
        REGISTERED.get(Stage.DRIVE).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                return ship.drivePower() > 0L ? null
                        : new Objection(Severity.HARD, MSG_NO_DRIVE);
            }
        });
        REGISTERED.get(Stage.DRIVE).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                // Advisory, not a veto: a hull that pokes out of the window can still jump. What is
                // outside when the window closes is simply not coming along in one piece.
                return ship.hullOutsideWindow() <= 0L ? null
                        : new Objection(Severity.ADVISORY, MSG_WINDOW_UNDERSIZED);
            }
        });
        REGISTERED.get(Stage.POWER).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                // Hard, because this one is physics: without the burst the window does not open at
                // all. A capacitor too small to ever hold the burst is a ship to rebuild, not a ship
                // to warn - which is exactly what "physically impossible" means here.
                if (ship.drivePower() <= 0L) {
                    return null; // already refused above; do not tell the pilot the same thing twice
                }
                return ship.capacitorCharge() >= ship.burstCost() ? null
                        : new Objection(Severity.HARD, MSG_CAPACITOR_LOW);
            }
        });
        REGISTERED.get(Stage.SUPPLY).add(new Predicate() {
            @Override
            public Objection check(ShipContext ship) {
                // Advisory by ruling: running out on the way is a flight that ends early, not a
                // flight the automation is entitled to forbid.
                if (ship.drivePower() <= 0L) {
                    return null;
                }
                return ship.storedEnergy() >= ship.flightEnergyCost() ? null
                        : new Objection(Severity.ADVISORY, MSG_ENERGY_SHORTFALL);
            }
        });
    }

    /** Ask whether {@code ship} may jump. Free, read-only, and side-effect free. */
    public static synchronized Verdict check(ShipContext ship) {
        List<Objection> objections = new ArrayList<>();
        if (ship == null) {
            objections.add(new Objection(Severity.HARD, MSG_NO_NAV_COMPUTER));
            return new Verdict(objections);
        }
        for (Stage stage : Stage.values()) {
            for (Predicate predicate : REGISTERED.get(stage)) {
                Objection objection = predicate.check(ship);
                if (objection != null) {
                    objections.add(objection);
                }
            }
        }
        return new Verdict(objections);
    }
}
