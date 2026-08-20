package zmaster587.advancedRocketry.hyperdrive;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import zmaster587.advancedRocketry.navigation.JumpGate;
import zmaster587.advancedRocketry.navigation.ShipNavigation;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ShipLedger;
import zmaster587.advancedRocketry.space.ShipTransitManager;
import zmaster587.advancedRocketry.space.SpaceManager;
import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.tile.TileNavigationComputer;

/**
 * The act of jumping: what a press of the helm key does, and when it costs anything.
 *
 * <p>The two halves of the decision live in two places on purpose. Choosing a destination is
 * deliberate work at a console, with the forecast in front of you — that is where a jump is
 * <b>armed</b>. Committing to leave belongs at the helm, where the pilot can see what is around him
 * — that is where it <b>fires</b>. Neither surface can do the other's job, and that is the feature.
 * </p>
 *
 * <p>The order of events is fixed and the whole sequence hangs off it: the gate is free, so it runs
 * as often as anyone asks; the spool is free, so aborting inside it costs nothing; and the capacitor
 * burst is the first and only thing that is spent. Once the burst fires, a refusal is a refusal the
 * pilot has already paid for — which is why nothing that can refuse is allowed to run after it.</p>
 */
public final class JumpTrigger {

    /** What a press did. */
    public enum Outcome {
        /** The block pressing this is not part of an assembled ship. */
        NOT_ON_SHIP,
        /** Nobody armed a destination at the navigation computer. */
        NOT_ARMED,
        /** Something makes the jump impossible. Free. */
        REFUSED,
        /** Something makes it a bad idea. The pilot is told, and may press again to mean it. */
        WARNED,
        /** The drive is winding up. Still free. */
        SPOOLING,
        /** The pilot changed his mind during the spool. Free. */
        ABORTED,
        /** The window opened and the ship is on its way. */
        COMMITTED,
        /** The commit itself failed. The burst is spent; this is a paid refusal. */
        FAILED
    }

    /** An outcome and the line the pilot should read. */
    public static final class Result {
        private final Outcome outcome;
        private final String langKey;

        Result(Outcome outcome, String langKey) {
            this.outcome = outcome;
            this.langKey = langKey;
        }

        public Outcome outcome() {
            return outcome;
        }

        public String langKey() {
            return langKey;
        }

        @Override
        public String toString() {
            return outcome + ":" + langKey;
        }
    }

    public static final String MSG_NOT_ON_SHIP = "msg.jump.notonship";
    public static final String MSG_NOT_ARMED = "msg.jump.notarmed";
    public static final String MSG_SPOOLING = "msg.jump.spooling";
    public static final String MSG_ABORTED = "msg.jump.aborted";
    public static final String MSG_COMMITTED = "msg.jump.committed";
    public static final String MSG_BURST_FAILED = "msg.jump.burstfailed";
    public static final String MSG_NO_POSITION = "msg.jump.nopositionrecord";
    public static final String MSG_DEPART_FAILED = "msg.jump.departfailed";
    /** The ship's own cell is bound to no slot world, so there is nothing to depart from. Free. */
    public static final String MSG_CELL_NOT_LIVE = "msg.jump.cellnotlive";
    public static final String MSG_CONFIRM = "msg.jump.confirm";

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("advancedrocketry/space");

    private JumpTrigger() {
    }

    /**
     * One press of the helm's jump key.
     *
     * <p>Everything this can do is free. It refuses, it warns, it starts or stops a spool — nothing
     * here spends anything, which is what lets a pilot press the key to find out where he stands.</p>
     */
    public static Result press(World world, BlockPos flightComputerPos, UUID shipId,
                               JumpSpool spool, long now) {
        if (world == null || flightComputerPos == null || spool == null) {
            return new Result(Outcome.NOT_ON_SHIP, MSG_NOT_ON_SHIP);
        }
        if (spool.spooling(now)) {
            // Pressing again during the wind-up is how a pilot changes his mind, and it is free -
            // the burst has not fired.
            spool.abort();
            return new Result(Outcome.ABORTED, MSG_ABORTED);
        }
        ShipNavigation nav = new ShipNavigation(world, flightComputerPos, shipId);
        TileNavigationComputer computer = nav.findNavComputer();
        if (computer == null) {
            return new Result(Outcome.REFUSED, JumpGate.MSG_NO_NAV_COMPUTER);
        }
        if (!computer.isArmed()) {
            return new Result(Outcome.NOT_ARMED, MSG_NOT_ARMED);
        }
        JumpGate.Verdict verdict = JumpGate.check(nav);
        if (!verdict.allowed()) {
            spool.clearWarning();
            return new Result(Outcome.REFUSED, verdict.firstMessage());
        }
        if (verdict.needsConfirmation() && !spool.confirming(now)) {
            // The press that meets a warning is the press that RAISES it. A pilot never confirms
            // something he has not been shown.
            spool.warn(now);
            return new Result(Outcome.WARNED, verdict.firstMessage());
        }
        spool.begin(now);
        return new Result(Outcome.SPOOLING, MSG_SPOOLING);
    }

    /**
     * The end of the spool: open the window and go.
     *
     * <p>The gate is re-asked first, because a spool is long enough for the world to change under
     * it — a coil pulled, a capacitor drained — and a jump must be legal at the moment it happens
     * rather than at the moment it was ordered. Everything after the burst is committed: a failure
     * there costs the pilot his charge, which is the ratified price of a fizzle.</p>
     */
    public static Result commit(World world, BlockPos flightComputerPos, UUID shipId,
                                JumpSpool spool, long now) {
        if (spool != null) {
            spool.abort(); // whatever happens next, the wind-up is over
        }
        if (world == null || flightComputerPos == null || shipId == null) {
            return new Result(Outcome.NOT_ON_SHIP, MSG_NOT_ON_SHIP);
        }
        ShipNavigation nav = new ShipNavigation(world, flightComputerPos, shipId);
        TileNavigationComputer computer = nav.findNavComputer();
        if (computer == null || !computer.isArmed()) {
            return new Result(Outcome.NOT_ARMED, MSG_NOT_ARMED);
        }
        // Aim once more, HERE, at the instant of departure. The spool is long enough for the
        // destination to have moved, and the aim is a prediction of where it will be when this
        // particular flight ends - so it has to be made from where the ship is now, with the drive
        // it has now. Free (pure arithmetic over the body's orbit), and it runs before the gate so
        // a target that has become unlocatable is refused rather than flown at.
        computer.refreshTarget();
        JumpGate.Verdict verdict = JumpGate.check(nav);
        if (!verdict.allowed()) {
            return new Result(Outcome.REFUSED, verdict.firstMessage());
        }
        GalacticCoord target = nav.target();
        ShipLedger ledger = SpaceSubsystem.ledger();
        ShipTransitManager transit = SpaceSubsystem.transit();
        if (ledger == null || transit == null) {
            return new Result(Outcome.FAILED, MSG_NO_POSITION);
        }
        ShipLedger.Entry entry = ledger.get(shipId);
        if (entry == null || entry.coord == null) {
            // The ship is not recorded anywhere, so there is nowhere to depart FROM. Refused before
            // the burst, so it is still free.
            return new Result(Outcome.FAILED, MSG_NO_POSITION);
        }
        long speed = JumpSpeed.blocksPerTick(nav.drive().stats().drivePower(),
                ShipMassProvider.massOf(world, flightComputerPos, shipId),
                nav.drive().stats().tier());

        // Which world the ship must be cut out of is asked of the thing that binds cells to slots,
        // never remembered next to the coordinate: a slot id is minted per boot and re-used, so a
        // stored one names a different cell after a restart. A cell bound to no slot at all means the
        // ship is not in a world the departure could reach — refused HERE, above the commit line, so
        // it stays free. Nothing that can refuse belongs below it.
        SpaceManager space = SpaceSubsystem.space();
        int originSlotDim = space == null
                ? SpaceManager.UNBOUND_SLOT : space.slotDimOf(entry.coord);
        if (originSlotDim == SpaceManager.UNBOUND_SLOT) {
            LOGGER.warn("[SPACE] jump refused for ship {}: its cell {} is bound to no slot world "
                    + "(the ship is flying in dim {})",
                    shipId, entry.coord.cellKey(), world.provider.getDimension());
            return new Result(Outcome.FAILED, MSG_CELL_NOT_LIVE);
        }

        // ── the commit point: everything above this line is free, nothing below it is ──
        if (!nav.drive().fireBurst(now)) {
            return new Result(Outcome.FAILED, MSG_BURST_FAILED);
        }
        boolean departed = transit.beginTransit(shipId.toString(), entry.coord, originSlotDim,
                flightComputerPos, target, speed);
        computer.disarm();
        if (!departed) {
            // The pilot has paid for this one, so say enough in the log to tell WHICH departure step
            // refused him. The ship's own live dimension is here and nowhere below, so it is logged
            // here: an origin slot that disagrees with it means the ledger and the world have drifted.
            LOGGER.warn("[SPACE] jump departed=false for ship {}: cell {} -> slot dim {}, ship is "
                    + "flying in dim {}, anchor {}",
                    shipId, entry.coord.cellKey(), originSlotDim,
                    world.provider.getDimension(), flightComputerPos);
            return new Result(Outcome.FAILED, MSG_DEPART_FAILED);
        }
        return new Result(Outcome.COMMITTED, MSG_COMMITTED);
    }
}
