package zmaster587.advancedRocketry.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.EntityRocketBase;
import zmaster587.advancedRocketry.api.FreeFlightInput;
import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.api.RocketFlightMode;
import zmaster587.advancedRocketry.command.test.TestProbeCommandRegistration;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.entity.EntityDummy;
import zmaster587.advancedRocketry.entity.EntityHoverCraft;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.tile.TilePilotSeat;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.interfaces.INetworkEntity;
import zmaster587.libVulpes.network.PacketChangeKeyState;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
import zmaster587.libVulpes.network.PacketMachine;
import zmaster587.libVulpes.util.InputSyncHandler;

@SideOnly(Side.CLIENT)
public class KeyBindings {

    //static KeyBinding launch = new KeyBinding("Launch", Keyboard.KEY_SPACE, "key.controls." + Constants.modId);
    static KeyBinding toggleJetpack = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.toggleJetpack"), Keyboard.KEY_X, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding openRocketUI = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.openRocketUI"), Keyboard.KEY_C, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding toggleRCS = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.togglercs"), Keyboard.KEY_R, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding turnRocketLeft = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.turnRocketLeft"), Keyboard.KEY_A, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding turnRocketRight = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.turnRocketRight"), Keyboard.KEY_D, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding turnRocketUp = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.turnRocketUp"), Keyboard.KEY_Z, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding turnRocketDown = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.turnRocketDown"), Keyboard.KEY_X, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding toggleFlightMode = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.toggleFlightMode"), Keyboard.KEY_M, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    // Free Flight lateral strafe (nose-relative). Q/E — share defaults with vanilla drop/inventory (resolved by ARKeyConflictContext).
    static KeyBinding strafeLeft  = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.strafeLeft"),  Keyboard.KEY_Q, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding strafeRight = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.strafeRight"), Keyboard.KEY_E, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    // Free Flight vertical along the craft's up axis. R/F — R shares with toggleRCS, F with vanilla swap-hands (resolved by ARKeyConflictContext).
    static KeyBinding flightVerticalUp   = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightVerticalUp"),   Keyboard.KEY_R, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightVerticalDown = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightVerticalDown"), Keyboard.KEY_F, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightAssistToggle  = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightAssistToggle"),  Keyboard.KEY_N, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    // Tier-2 auto-takeoff autopilot (diagonal climb to orbit). K — unbound in vanilla, so no conflict.
    static KeyBinding autoTakeoffToggle   = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.autoTakeoffToggle"),   Keyboard.KEY_K, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    boolean prevState;
    /** Last FF input dispatched to the server. We only resend when the intent actually changes (saves bandwidth). */
    private FreeFlightInput lastSentInput = FreeFlightInput.zero();
    /** Tracks FF-gate transitions for [FF-TRACE] logging. */
    private boolean wasFreeFlightActive = false;
    /** Last FF input dispatched to a tier-2 ship's pilot seat; resend only on change. */
    private FreeFlightInput lastSentShipInput = FreeFlightInput.zero();
    /** Whether the ship-pilot mouse baseline is valid; false forces this tick's look-delta to
     *  zero (so an arbitrary pre-seat look doesn't read as one huge cursor jump on sit). */
    private boolean shipPilotPinValid = false;
    /** Ship attitude sampled this tick / previous tick (client), for the per-frame camera slerp -
     *  the tier-2 analogue of the rocket's ffQuat/prevFfQuat. Read by the render camera lock in
     *  RocketEventHandler via {@link #shipQuat()} / {@link #shipPrevQuat()}. */
    private static volatile FreeFlightPhysics.Quat shipQuat = FreeFlightPhysics.Quat.IDENTITY;
    private static volatile FreeFlightPhysics.Quat shipPrevQuat = FreeFlightPhysics.Quat.IDENTITY;
    /** RAW mouse motion (converted to vanilla look degrees) accumulated since the last client tick,
     *  consumed by the tier-2 flight cursor. See {@link #handleShipPilotInput} for why the ship
     *  cursor must be driven by the mouse itself and never by a player-rotation difference. */
    private static volatile float pendingCursorYawDeg, pendingCursorPitchDeg;
    /** Camera-pin state for mouse-as-rate steering: the player
     *  rotation we pinned at the end of the previous tick. Whatever the mouse
     *  added on top of it since is this tick's turn command. Static so the
     *  PosLook re-pin (see {@link #repinCameraAfterTeleport()}) can keep the
     *  pin and the delta baseline consistent. */
    private static volatile float lastPinnedYaw, lastPinnedPitch;
    /** Elite-style flight-cursor deflection in [-1,1]² (X = roll, Y = pitch).
     *  Absolute: stays where the mouse leaves it; reset when FF goes inactive. */
    private static volatile float flightCursorX, flightCursorY;
    /** Previous-tick cursor deflection, so the HUD can interpolate the dot by
     *  partialTicks — the cursor is sampled at 20 Hz (client tick) but drawn per
     *  frame, and without this the dot visibly steps at the tick rate. */
    private static volatile float prevFlightCursorX, prevFlightCursorY;
    /** Deflection added per degree of mouse movement (≈ full deflection at 25°). */
    private static final float FF_CURSOR_SENS = 0.04f;
    /** Centre deadzone: |deflection| below this reads as zero (no drift at rest). */
    private static final float FF_CURSOR_DEADZONE = 0.05f;

    /** Apply the centre deadzone to a deflection component. */
    private static float deadzone(float v) {
        return Math.abs(v) < FF_CURSOR_DEADZONE ? 0f : v;
    }

    /** Current flight-cursor deflection (X = roll, Y = pitch), for the HUD. */
    public static float flightCursorX() { return flightCursorX; }
    public static float flightCursorY() { return flightCursorY; }
    /** partialTicks-interpolated cursor deflection for smooth per-frame HUD draw. */
    public static float flightCursorX(float partialTicks) {
        return prevFlightCursorX + (flightCursorX - prevFlightCursorX) * partialTicks;
    }
    public static float flightCursorY(float partialTicks) {
        return prevFlightCursorY + (flightCursorY - prevFlightCursorY) * partialTicks;
    }
    /** Ship attitude sampled this / previous client tick, for the render camera lock's per-frame
     *  slerp (RocketEventHandler). Identity until the client first pilots a tier-2 ship. */
    public static FreeFlightPhysics.Quat shipQuat() { return shipQuat; }
    public static FreeFlightPhysics.Quat shipPrevQuat() { return shipPrevQuat; }
    /** True once the camera has been pinned to the craft this flight — gates
     *  the frame-time lock telemetry in RocketEventHandler so pre-takeoff
     *  frames (arbitrary look) don't pollute it. */
    private static volatile boolean cameraPinValid = false;

    // ---- Ship-input delivery diagnostics (ungated statics) ----------------------------------
    // The tier-2 gate below (handleShipPilotInput) refuses SILENTLY: when the ridden mount does
    // not resolve a linked pilot seat the client just never sends, which from the outside is
    // indistinguishable from "sent but lost". These counters make the gate's per-tick decision
    // and the actual send observable (a client test reads them reflectively), so a dead cockpit
    // can be attributed to the right link of the chain. See TilePilotSeat.lastRiderResolve for
    // WHAT the resolution saw; deliberately not test-gated so they carry values everywhere.

    /** Client ticks on which the tier-2 gate refused: riding, but no linked seat resolved. */
    public static volatile int shipGateClosedTicks;
    /** Client ticks on which the tier-2 gate held a linked pilot seat (the pilot branch ran). */
    public static volatile int shipGateOpenTicks;
    /** PACKET_PILOT_INPUT packets this client actually dispatched to the seat. */
    public static volatile int shipInputSendCount;

    public static boolean isCameraPinnedThisFlight() {
        return cameraPinValid;
    }

    // ---- Engine-start ritual -------------------------------
    /** Ticks the jump key must be held (pre-flight, FF mode) to start the engines. */
    public static final int ENGINE_START_HOLD_TICKS = 60;
    /** Client-side hold progress, 0..ENGINE_START_HOLD_TICKS. Published for the
     *  HUD progress line and for client e2e readback. */
    public static volatile int engineStartHoldTicks = 0;
    /** Ticks left to flash the engine-state line ("Engines started/stopped"). */
    public static volatile int engineFlashTicks = 0;
    /** Which flash: true = "Engines started", false = "Engines stopped". */
    public static volatile boolean engineFlashStarted = false;
    /** Guards the one-shot ENGINE_START send per hold. */
    private boolean engineStartSent = false;
    /** Commanded turn rates of the current tick, [-1,1] — drawn as the HUD
     *  turn-rate dot (Phase 4). */
    public static volatile float hudYawRate = 0f, hudPitchRate = 0f;

    /** Mouse motion accumulated since the last pin, captured at the HEAD of
     *  a PosLook teleport so the vanilla handler can't destroy it (the echo
     *  overwrites the player rotation fields the delta lives in). */
    private static volatile float pendingMouseYaw = 0f, pendingMousePitch = 0f;

    /** True between the HEAD capture and the RETURN re-pin of one PosLook. */
    private static boolean teleportCaptureArmed = false;

    /** Guards shared by the two PosLook hooks: a pinned FF flight on the MC thread. */
    private static EntityRocket pinnedFlightCraft() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || !cameraPinValid) return null;
        if (!mc.isCallingFromMinecraftThread()) return null; // netty-thread early return path
        if (!(mc.player.getRidingEntity() instanceof EntityRocket)) return null;
        EntityRocket rocket = (EntityRocket) mc.player.getRidingEntity();
        return (rocket.isFreeFlight() && rocket.isInFlight()) ? rocket : null;
    }

    /**
     * The camera-pinned tier-2 ship attitude as Euler {yaw, pitch, roll}, or null when the client
     * is not piloting a ship (or off the MC thread). The tier-2 analogue of
     * {@link #pinnedFlightCraft()}: it lets the PosLook re-pin below keep the ship pilot's cursor
     * baseline consistent against the vanilla riding echo, exactly as for the rocket.
     */
    private static float[] pinnedShipEuler() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null) return null;
        if (!mc.isCallingFromMinecraftThread()) return null;
        TilePilotSeat seat = TilePilotSeat.forRider(mc.player.getRidingEntity(), mc.world);
        if (seat == null || !seat.isLinked()) return null;
        return FreeFlightPhysics.eulerFromQuat(shipQuat);
    }

    /**
     * PosLook HEAD hook: capture the mouse motion accumulated since the last
     * camera pin BEFORE the vanilla handler overwrites the player rotation
     * with the riding echo's ~1-RTT-stale values. Without this, every echo
     * (which arrives about once per tick while riding) destroys the pending
     * swipe — imperceptible drops for a human moving the mouse continuously,
     * but it eats discrete injected swipes wholesale.
     */
    public static void captureMouseBeforeTeleport() {
        EntityPlayerSP player = Minecraft.getMinecraft() == null ? null : Minecraft.getMinecraft().player;
        if (player == null) return;
        // Gated on an FF-pinned craft - a rocket OR a tier-2 ship pilot; a no-op otherwise.
        if (pinnedFlightCraft() == null && pinnedShipEuler() == null) return;
        pendingMouseYaw   = net.minecraft.util.math.MathHelper.wrapDegrees(player.rotationYaw - lastPinnedYaw);
        pendingMousePitch = player.rotationPitch - lastPinnedPitch;
        teleportCaptureArmed = true;
    }

    /**
     * PosLook RETURN hook — undo the vanilla riding echo. While a player
     * rides, the server answers every position report with an
     * SPacketPlayerPosLook carrying the rotation the client sent ~1 RTT ago;
     * with the camera hard-locked to a turning craft that snaps the view back
     * by (turn rate × latency) — a visible per-frame hiccup. Re-pin the camera
     * to the craft, re-apply the mouse motion captured at HEAD on top, and
     * keep the delta baseline at the pin so the next tick still reads pure
     * mouse motion (no feedback).
     */
    public static void repinCameraAfterTeleport() {
        EntityRocket rocket = pinnedFlightCraft();
        float[] shipEuler = rocket == null ? pinnedShipEuler() : null;
        if (rocket == null && shipEuler == null) return;
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        float pendYaw   = teleportCaptureArmed ? pendingMouseYaw   : 0f;
        float pendPitch = teleportCaptureArmed ? pendingMousePitch : 0f;
        teleportCaptureArmed = false;
        // Re-pin to the craft attitude (the rocket's live rotation, or the ship's sampled Euler).
        float yaw, prevYaw, pitch, prevPitch;
        if (rocket != null) {
            yaw = rocket.rotationYaw;   prevYaw = rocket.prevRotationYaw;
            pitch = rocket.rotationPitch; prevPitch = rocket.prevRotationPitch;
        } else {
            yaw = prevYaw = shipEuler[0];
            pitch = prevPitch = shipEuler[1];
        }
        player.rotationYaw       = yaw + pendYaw;
        player.prevRotationYaw   = prevYaw + pendYaw;
        player.rotationPitch     = pitch + pendPitch;
        player.prevRotationPitch = prevPitch + pendPitch;
        lastPinnedYaw   = yaw;
        lastPinnedPitch = pitch;
    }

    /** Harness-only ([FF-TRACE/K]) client keybind log; pass -Dadvancedrocketry.tests=true. */
    private static void kbTrace(String msg) {
        if (TestProbeCommandRegistration.isTestMode()) {
            AdvancedRocketry.logger.info("[FF-TRACE/K] " + msg);
        }
    }

    public static void init() {
        //ClientRegistry.registerKeyBinding(launch);
        ClientRegistry.registerKeyBinding(toggleJetpack);
        ClientRegistry.registerKeyBinding(openRocketUI);
        ClientRegistry.registerKeyBinding(toggleRCS);
        ClientRegistry.registerKeyBinding(turnRocketRight);
        ClientRegistry.registerKeyBinding(turnRocketLeft);
        ClientRegistry.registerKeyBinding(turnRocketUp);
        ClientRegistry.registerKeyBinding(turnRocketDown);
        ClientRegistry.registerKeyBinding(toggleFlightMode);
        ClientRegistry.registerKeyBinding(strafeLeft);
        ClientRegistry.registerKeyBinding(strafeRight);
        ClientRegistry.registerKeyBinding(flightVerticalUp);
        ClientRegistry.registerKeyBinding(flightVerticalDown);
        ClientRegistry.registerKeyBinding(flightAssistToggle);
        ClientRegistry.registerKeyBinding(autoTakeoffToggle);
        scopeSteeringKeysToCockpit();
    }

    /**
     * Resolve the steering-key conflicts with vanilla (and the internal X dup)
     * via mutually-exclusive {@link ARKeyConflictContext}s instead of rebinding:
     * the AR steering keys only fire while piloting, the vanilla keys they share
     * a default with only fire otherwise. See {@link ARKeyConflictContext} for
     * why this resolves both the runtime double-fire and the Controls-screen
     * conflict warning.
     */
    private static void scopeSteeringKeysToCockpit() {
        // AR craft-steering keys: active only while piloting an AR craft.
        turnRocketLeft.setKeyConflictContext(ARKeyConflictContext.PILOTING);   // A — yaw
        turnRocketRight.setKeyConflictContext(ARKeyConflictContext.PILOTING);  // D — yaw
        turnRocketUp.setKeyConflictContext(ARKeyConflictContext.PILOTING);     // Z — classic up (unused in FF)
        turnRocketDown.setKeyConflictContext(ARKeyConflictContext.PILOTING);   // X — classic down / FF throttle-cut
        strafeLeft.setKeyConflictContext(ARKeyConflictContext.PILOTING);       // Q — strafe
        strafeRight.setKeyConflictContext(ARKeyConflictContext.PILOTING);      // E — strafe
        flightVerticalUp.setKeyConflictContext(ARKeyConflictContext.PILOTING);   // R — vertical
        flightVerticalDown.setKeyConflictContext(ARKeyConflictContext.PILOTING); // F — vertical
        // AR keys that share a key with another AR action get the complement, so
        // the cockpit binding wins while piloting and the other works on foot:
        //  X = jetpack toggle (foot) vs throttle-cut (cockpit),
        //  R = RCS toggle (foot) vs vertical-up (cockpit).
        toggleJetpack.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING);
        toggleRCS.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING);

        // Pair the overridden vanilla keys with the complement so exactly one
        // binding is active per shared key (no double-fire, no GUI conflict).
        // Movement forward/back/sneak are intentionally left alone — Free Flight
        // reuses them and they never carry an AR binding of their own.
        GameSettings gs = Minecraft.getMinecraft() != null
                ? Minecraft.getMinecraft().gameSettings : null;
        if (gs != null) {
            gs.keyBindInventory.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING); // E vs strafe-right
            gs.keyBindDrop.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING);      // Q vs strafe-left
            gs.keyBindLeft.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING);      // A vs yaw-left
            gs.keyBindRight.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING);     // D vs yaw-right
            gs.keyBindSwapHands.setKeyConflictContext(ARKeyConflictContext.NOT_PILOTING); // F vs vertical-down
        }
    }
    //Getters for keybindings
    public static KeyBinding getOpenRocketUI() {
        return openRocketUI;
    }

    private static String key(KeyBinding binding) {
        return GameSettings.getKeyDisplayString(binding.getKeyCode());
    }

    /**
     * Localised Free Flight HUD lines: a mode indicator plus a control legend
     * with the player's actual bound keys. Pre-launch shows how to launch /
     * switch mode; in-flight shows the steering legend + Flight Assist state.
     * Client-only (reads GameSettings + I18n).
     */
    /** One signed body-frame pair "setpoint/actual" for the HUD vector line. */
    private static String vecPair(double sp, double act) {
        return String.format("%+.2f/%+.2f", sp, act);
    }

    /**
     * The Free Flight HUD text, backend-agnostic: driven by a {@link FreeFlightHudState} snapshot
     * so the SAME lines serve a tier-1 rocket and a tier-2 ship. The active title carries the
     * craft tier; the velocity vector + speed lines appear only when the backend supplies velocity
     * ({@link FreeFlightHudState#hasVelocity}).
     */
    public static java.util.List<String> freeFlightHudLines(FreeFlightHudState state) {
        GameSettings gs = Minecraft.getMinecraft().gameSettings;
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (!state.inFlight) {
            // Pre-flight is a tier-1 rocket concept (the engine-start ritual). A tier-2 ship is
            // always "in flight" while seated, so it never reaches this branch.
            lines.add(I18n.format("msg.ff.hud.title"));
            // Engine state: off -> how to start; mid-hold -> progress.
            if (engineStartHoldTicks > 0) {
                lines.add(I18n.format("msg.ff.hud.engines.starting",
                        engineStartHoldTicks * 100 / ENGINE_START_HOLD_TICKS));
            } else if (engineFlashTicks > 0 && !engineFlashStarted) {
                lines.add(I18n.format("msg.ff.engines.stopped"));
            } else {
                lines.add(I18n.format("msg.ff.hud.engines.off", key(gs.keyBindJump)));
            }
            lines.add(I18n.format("msg.ff.hud.prelaunch", key(toggleFlightMode)));
            return lines;
        }
        // Active title: FA state + the craft tier (1 = rocket, 2 = ship).
        lines.add(I18n.format("msg.ff.hud.active",
                        I18n.format(state.flightAssistOn ? "msg.ff.hud.fa.on" : "msg.ff.hud.fa.off"))
                + " " + I18n.format("msg.ff.hud.tier", state.tier));
        lines.add(engineFlashTicks > 0 && engineFlashStarted
                ? I18n.format("msg.ff.engines.started")
                : I18n.format("msg.ff.hud.engines.on"));
        lines.add(I18n.format("msg.ff.hud.move",   key(gs.keyBindForward), key(gs.keyBindBack)));
        lines.add(I18n.format("msg.ff.hud.strafe", key(strafeLeft),        key(strafeRight)));
        lines.add(I18n.format("msg.ff.hud.vert",   key(flightVerticalUp),  key(flightVerticalDown)));
        lines.add(I18n.format("msg.ff.hud.yaw",    key(turnRocketLeft),    key(turnRocketRight)));
        lines.add(I18n.format("msg.ff.hud.pitchmouse"));
        lines.add(I18n.format("msg.ff.hud.cut",    key(turnRocketDown)));
        lines.add(I18n.format("msg.ff.hud.brake",  key(gs.keyBindSneak)));
        lines.add(I18n.format("msg.ff.hud.assist", key(flightAssistToggle)));

        // Per-axis vector readout: body-frame setpoint vs actual velocity, blocks/tick — the
        // textual twin of the graphic bars (and what the client e2e reads). Only when the backend
        // supplies velocity; FA off shows the actual only.
        if (state.hasVelocity) {
            if (state.flightAssistOn) {
                lines.add(I18n.format("msg.ff.hud.vector",
                        vecPair(state.faForward, state.bodyForward),
                        vecPair(state.faRight,   state.bodyRight),
                        vecPair(state.faUp,      state.bodyUp)));
            } else {
                lines.add(I18n.format("msg.ff.hud.vector",
                        String.format("%+.2f", state.bodyForward),
                        String.format("%+.2f", state.bodyRight),
                        String.format("%+.2f", state.bodyUp)));
            }
            lines.add(I18n.format("msg.ff.hud.speed", String.format("%.1f", state.speed() * 20.0)));
        }
        return lines;
    }

    /**
     * Free Flight steering is sampled every client tick (not just on key
     * transitions) and dispatched when the intent changes. This is what makes a
     * held key keep thrusting after {@code isInFlight} replicates to the client,
     * and lets the pilot hold a direction continuously.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        final Minecraft mc = Minecraft.getMinecraft();
        final EntityPlayerSP player = mc.player;
        // Deck-frame look glue for a walking crew member, BEFORE the GUI gate: the ship keeps
        // turning under him while he reads a chest, and his world aim must keep following the
        // deck even when the mouse is captured by a screen.
        if (player != null) {
            DeckLook.clientTick(player);
            // Pending dismount seed: apply the queued deck capture the moment the body's transient
            // exclusion (the post-dismount riding tail) clears. Driven here, per client tick, so
            // the seed does not depend on packet timing.
            zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.clientTickPendingSeed(player);
            // Subspace census: record every tick what the CLIENT world holds at the nearby ship's
            // subspace coordinates. Sampled here, not in the travel hook, so it keeps reporting
            // through the phases where the client is NOT resolving (server-held fallback) - which
            // is exactly when the question "does this client even have the ship's chunks?" matters.
            zmaster587.advancedRocketry.integration.vs.ShipFrameTravel.clientCensusTick(player);
        }
        // Don't steer while a GUI is open. (We intentionally do NOT require
        // inGameHasFocus — losing window focus shouldn't freeze the controls,
        // and the headless test bot never reports focus.)
        if (player == null || mc.currentScreen != null) return;
        if (engineFlashTicks > 0) engineFlashTicks--;
        // Tier-2 ship path: if the player is seated in a linked pilot seat, steer the ship and
        // stop — the rocket steering below is only for a ridden EntityRocket.
        if (handleShipPilotInput(mc, player)) return;
        if (!(player.getRidingEntity() instanceof EntityRocket)) {
            if (wasFreeFlightActive) { kbTrace("FF gate -> inactive (no longer riding a rocket)"); wasFreeFlightActive = false; }
            engineStartHoldTicks = 0;
            engineStartSent = false;
            return;
        }

        EntityRocket rocket = (EntityRocket) player.getRidingEntity();
        boolean active = rocket.isFreeFlight() && rocket.isInFlight();
        if (active != wasFreeFlightActive) {
            kbTrace("FF gate active=" + active + " (isFreeFlight=" + rocket.isFreeFlight()
                    + " isInFlight=" + rocket.isInFlight() + ")");
            // Engine-state flash: in FF, "in flight" IS "engines
            // on" — entering shows "Engines started", leaving (touchdown)
            // shows "Engines stopped".
            if (rocket.isFreeFlight()) {
                engineFlashTicks = 60;
                engineFlashStarted = active;
            }
            wasFreeFlightActive = active;
        }
        if (!active) {
            // Reset so the next entry into FF sends a fresh, current snapshot
            // and the camera re-aligns to the craft on the next takeoff.
            lastSentInput = FreeFlightInput.zero();
            cameraPinValid = false;
            flightCursorX = 0f;
            flightCursorY = 0f;
            prevFlightCursorX = 0f;
            prevFlightCursorY = 0f;

            // Engine-start ritual: pre-flight in FF mode, hold
            // the jump key for ENGINE_START_HOLD_TICKS; releasing early
            // cancels. One ENGINE_START packet per completed hold — the
            // server validates (mode, fuel, climb authority) and starts.
            if (rocket.isFreeFlight()) {
                if (mc.gameSettings.keyBindJump.isKeyDown()) {
                    if (engineStartHoldTicks < ENGINE_START_HOLD_TICKS) engineStartHoldTicks++;
                    if (engineStartHoldTicks >= ENGINE_START_HOLD_TICKS && !engineStartSent) {
                        kbTrace("engine-start hold complete -> ENGINE_START");
                        PacketHandler.sendToServer(new PacketEntity(
                                rocket, (byte) EntityRocket.PacketType.ENGINE_START.ordinal()));
                        engineStartSent = true;
                    }
                } else {
                    if (engineStartHoldTicks > 0) kbTrace("engine-start hold released at " + engineStartHoldTicks);
                    engineStartHoldTicks = 0;
                    engineStartSent = false;
                }
            } else {
                engineStartHoldTicks = 0;
                engineStartSent = false;
            }
            return;
        }
        engineStartHoldTicks = 0;
        engineStartSent = false;

        // Throttle cut (X): with FA on the server zeroes the velocity setpoint
        // (brake-to-hover); with FA off it neutralises translation thrust.
        // Either way the raw channels still travel — the server decides.
        boolean cut = turnRocketDown.isKeyDown();

        float fwd = (mc.gameSettings.keyBindForward.isKeyDown() ?  1f : 0f)
                + (mc.gameSettings.keyBindBack.isKeyDown()    ? -1f : 0f);
        // Q/E strafe polarity is flipped relative to the raw body-right axis: with
        // the FF camera looking out the nose, world +X (body right) renders on the
        // pilot's LEFT, so "strafe right" (E) must command −right to move the craft
        // the way the pilot sees it. (Playtest fix — the raw-axis mapping felt
        // inverted.) The physics strafe->right-axis mapping is unchanged; only which
        // key drives which sign flips here.
        float strafe = (strafeRight.isKeyDown() ? -1f : 0f)
                + (strafeLeft.isKeyDown()  ?  1f : 0f);
        float vert = (flightVerticalUp.isKeyDown()   ?  1f : 0f)
                + (flightVerticalDown.isKeyDown() ? -1f : 0f);

        // ---- Mouse-as-rate steering + camera-nose lock --------
        // The look delta the mouse accumulated since the last camera pin IS
        // this tick's turn command: clamped to the craft turn rate (1:1 below
        // it, excess discarded — Elite-style rate limit), then the camera is
        // re-pinned to the craft axes so view and nose can never diverge.
        float mouseYawDelta, mousePitchDelta;
        if (!cameraPinValid) {
            // First active tick (takeoff / remount): align the view to the
            // craft and DISCARD the stale look-offset — otherwise the
            // pilot's arbitrary pre-takeoff look would read as a huge
            // phantom swipe and kick the nose around on tick one.
            mouseYawDelta = 0f;
            mousePitchDelta = 0f;
            cameraPinValid = true;
            kbTrace("camera pinned to craft (yaw=" + rocket.rotationYaw
                    + " pitch=" + rocket.rotationPitch + ")");
        } else {
            mouseYawDelta   = net.minecraft.util.math.MathHelper.wrapDegrees(player.rotationYaw - lastPinnedYaw);
            mousePitchDelta = player.rotationPitch - lastPinnedPitch;
        }

        float yawKeys = (turnRocketRight.isKeyDown() ?  1f : 0f)
                      + (turnRocketLeft.isKeyDown()  ? -1f : 0f);
        // Elite-style flight cursor: the mouse drives a VIRTUAL cursor that stays
        // where you put it (absolute deflection), clamped to a rectangular zone
        // mapped to [-1,1]². Its POSITION is the command — a stationary cursor is
        // a fixed, non-spiky input exactly like a held key, which is what makes
        // the mouse as glass-smooth as A/D (rate-from-movement was the jitter).
        // Vertical -> pitch rate, horizontal -> roll (bank) rate. Yaw is keyboard.
        // Snapshot the previous-tick cursor so the HUD interpolates the dot per
        // frame (sampled 20 Hz, drawn 60+ fps) instead of stepping at tick rate.
        prevFlightCursorX = flightCursorX;
        prevFlightCursorY = flightCursorY;
        flightCursorX = FreeFlightInput.clamp(flightCursorX + mouseYawDelta   * FF_CURSOR_SENS);
        flightCursorY = FreeFlightInput.clamp(flightCursorY + mousePitchDelta * FF_CURSOR_SENS);
        float yaw   = FreeFlightInput.clamp(yawKeys);
        float pitch = deadzone(flightCursorY);
        float roll  = deadzone(flightCursorX);

        float brake = mc.gameSettings.keyBindSneak.isKeyDown() ? 1f : 0f;

        // HUD indicators: commanded pitch/roll deflection.
        hudYawRate = roll;
        hudPitchRate = pitch;

        FreeFlightInput input = new FreeFlightInput(fwd, vert, strafe, yaw, pitch, roll, brake, cut);
        if (!input.equals(lastSentInput)) {
            kbTrace("send FF input " + input);
            rocket.applyFreeFlightInput(input);
            PacketHandler.sendToServer(new PacketEntity(
                    rocket, (byte) EntityRocket.PacketType.FREE_FLIGHT_INPUT.ordinal()));
            lastSentInput = input;
        }

        // Hard camera-nose lock: mirror BOTH current and prev rotation from the
        // craft so the per-frame render interpolation sweeps the camera exactly
        // with the craft (pinning prev=current would step the view at 20 fps).
        player.rotationYaw       = rocket.rotationYaw;
        player.prevRotationYaw   = rocket.prevRotationYaw;
        player.rotationPitch     = rocket.rotationPitch;
        player.prevRotationPitch = rocket.prevRotationPitch;
        lastPinnedYaw   = rocket.rotationYaw;
        lastPinnedPitch = rocket.rotationPitch;
    }

    /**
     * Steer a tier-2 (Valkyrien Skies) ship when the player is seated in a linked pilot seat.
     *
     * <p>Sampled every client tick (like the rocket path): translation and yaw come from the
     * Free Flight keys; pitch/roll from an absolute mouse-driven flight cursor. The camera is
     * hard-locked to the ship's nose exactly as in the rocket Free Flight path — the mouse only
     * STEERS (drives the flight cursor) and never free-looks, and the view sweeps with the ship as
     * it turns. The current intent is pushed to the seat's tile as a {@code PacketMachine} only
     * when it changes; the seat forwards it to the ship's flight computer server-side. Returns
     * {@code true} iff the player is piloting a ship this tick, so the caller skips the rocket
     * steering path.</p>
     */
    private boolean handleShipPilotInput(Minecraft mc, EntityPlayerSP player) {
        // Resolve the pilot seat via the dummy's BOUND seat position (its own world position does
        // not locate the seat tile on a physics-mod ship — the seat lives in a distant subspace).
        TilePilotSeat seat = TilePilotSeat.forRider(player.getRidingEntity(), mc.world);
        if (seat == null || !seat.isLinked()) {
            // Diagnostics: count only ticks where the player IS on a seat mount - that is the
            // silent "seated but not piloting" state worth attributing (walking ticks are noise).
            if (player.getRidingEntity() instanceof EntityDummy) {
                shipGateClosedTicks++;
            }
            shipPilotPinValid = false;
            lastSentShipInput = FreeFlightInput.zero();
            pendingCursorYawDeg = 0f;
            pendingCursorPitchDeg = 0f;
            return false;
        }
        shipGateOpenTicks++;
        BlockPos seatPos = seat.getPos();

        boolean cut = turnRocketDown.isKeyDown();
        float fwd = (mc.gameSettings.keyBindForward.isKeyDown() ? 1f : 0f)
                + (mc.gameSettings.keyBindBack.isKeyDown() ? -1f : 0f);
        // Same strafe-sign convention as the rocket path (E = move craft the way the pilot sees).
        float strafe = (strafeRight.isKeyDown() ? -1f : 0f)
                + (strafeLeft.isKeyDown() ? 1f : 0f);
        float vert = (flightVerticalUp.isKeyDown() ? 1f : 0f)
                + (flightVerticalDown.isKeyDown() ? -1f : 0f);
        float yawKeys = (turnRocketRight.isKeyDown() ? 1f : 0f)
                + (turnRocketLeft.isKeyDown() ? -1f : 0f);

        // Mouse -> absolute flight cursor (pitch on Y, roll on X). The steer command is the RAW
        // mouse motion accumulated since the last tick (see onShipPilotMouseMoved), NOT a difference
        // of player rotations: the pin below and the vanilla riding echo both write that rotation,
        // so a difference would alias the ship's own A/D yaw into the cursor and bank the craft.
        // Writes the SHARED cursor / turn-rate fields the FF HUD reads, so the ship gets the same
        // on-screen flight cursor as the rocket. The first tick after sitting centres the cursor and
        // discards any motion made before the pilot sat down.
        boolean firstShipTick = !shipPilotPinValid;
        float yawDelta = pendingCursorYawDeg;
        float pitchDelta = pendingCursorPitchDeg;
        pendingCursorYawDeg = 0f;
        pendingCursorPitchDeg = 0f;
        if (firstShipTick) {
            yawDelta = 0f;
            pitchDelta = 0f;
            shipPilotPinValid = true;
            flightCursorX = 0f;
            flightCursorY = 0f;
        }
        prevFlightCursorX = flightCursorX;
        prevFlightCursorY = flightCursorY;
        flightCursorX = FreeFlightInput.clamp(flightCursorX + yawDelta * FF_CURSOR_SENS);
        flightCursorY = FreeFlightInput.clamp(flightCursorY + pitchDelta * FF_CURSOR_SENS);

        // Sample the ship attitude for BOTH the cursor baseline and the per-frame render camera
        // lock. The visible camera is owned by RocketEventHandler.onFreeFlightCameraSetup, which
        // slerps shipPrevQuat->shipQuat by partialTicks (smooth at frame rate instead of stepping
        // at the 20 Hz tick - the old prev==current pin was the tier-2 jitter). Here we only mirror
        // the ship attitude onto the player rotation and re-baseline the pin, so the flight-cursor
        // delta above reads pure mouse motion next tick. When no attitude is available this tick
        // (VS transform not ready) don't fight the view - just re-baseline the pin.
        FreeFlightPhysics.Quat shipAttitude = VSIntegration.getShipAttitude(mc.world, seatPos);
        if (shipAttitude != null) {
            shipPrevQuat = firstShipTick ? shipAttitude : shipQuat;
            shipQuat = shipAttitude;
            float[] euler = FreeFlightPhysics.eulerFromQuat(shipQuat);
            float[] prevEuler = FreeFlightPhysics.eulerFromQuat(shipPrevQuat);
            player.rotationYaw = euler[0];
            player.prevRotationYaw = prevEuler[0];
            player.rotationPitch = euler[1];
            player.prevRotationPitch = prevEuler[1];
            lastPinnedYaw = euler[0];
            lastPinnedPitch = euler[1];
        } else {
            lastPinnedYaw = player.rotationYaw;
            lastPinnedPitch = player.rotationPitch;
        }

        float yaw = FreeFlightInput.clamp(yawKeys);
        float pitch = deadzone(flightCursorY);
        float roll = deadzone(flightCursorX);
        float brake = mc.gameSettings.keyBindSneak.isKeyDown() ? 1f : 0f;

        // Publish the commanded pitch/roll deflection for the HUD turn-rate dot.
        hudYawRate = roll;
        hudPitchRate = pitch;

        FreeFlightInput input = new FreeFlightInput(fwd, vert, strafe, yaw, pitch, roll, brake, cut);
        if (!input.equals(lastSentShipInput)) {
            seat.pendingInput = input;
            PacketHandler.sendToServer(new PacketMachine(seat, TilePilotSeat.PACKET_PILOT_INPUT));
            shipInputSendCount++;
            kbTrace("SHIP send " + input + " -> seat " + seatPos);
            lastSentShipInput = input;
        }
        return true;
    }

    /**
     * Accumulate the RAW mouse motion a tier-2 ship pilot makes, converted to the same degrees the
     * vanilla look would have applied (so the pilot's mouse-sensitivity and invert-Y settings still
     * govern the flight cursor). Consumed once per client tick by {@link #handleShipPilotInput}.
     *
     * <p>This exists because the ship cursor CANNOT be recovered from the player's rotation: that
     * field is overwritten every tick by the ship-attitude camera pin, and in between by the vanilla
     * riding position echo, which carries a rotation about one round-trip old. Any tick where those
     * two disagree folds the ship's own yaw - i.e. the A/D steering - into the "mouse" delta and
     * banks the craft whenever the pilot turns. Reading the mouse directly cannot alias that way.</p>
     */
    @SubscribeEvent
    public void onShipPilotMouseMoved(net.minecraftforge.client.event.MouseEvent event) {
        // Only motion of a GRABBED cursor is steering. A GUI that has just closed leaves the mouse
        // ungrabbed for a tick, and the pointer's jump back to the screen centre would otherwise arrive
        // here as a hard flick of the flight stick.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.inGameHasFocus) return;
        acceptShipPilotMouseDelta(event.getDx(), event.getDy());
    }

    /**
     * Accumulate one raw mouse delta into the ship pilot's flight cursor. Split out of the event handler
     * so the SAME code runs whether the delta arrives from the window's mouse or is injected by a driver
     * that has no window - a cursor no test can deflect is a cursor whose behaviour cannot be pinned,
     * and the key path already works exactly this way.
     */
    public static void acceptShipPilotMouseDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || mc.currentScreen != null) return;
        TilePilotSeat seat = TilePilotSeat.forRider(mc.player.getRidingEntity(), mc.world);
        if (seat == null || !seat.isLinked()) return;
        // Vanilla's raw-delta to degrees mapping (EntityRenderer sensitivity curve x Entity.turn).
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float degPerUnit = f * f * f * 8.0F * 0.15F;
        int invert = mc.gameSettings.invertMouse ? -1 : 1;
        pendingCursorYawDeg += dx * degPerUnit;
        pendingCursorPitchDeg -= dy * degPerUnit * invert;
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        final Minecraft minecraft = FMLClientHandler.instance().getClient();
        final EntityPlayerSP player = minecraft.player;


        //Prevent control when a GUI is open
        if (Minecraft.getMinecraft().currentScreen != null)// && Minecraft.getMinecraft().currentScreen instanceof GuiChat)
            return;


        //EntityRocket rocket;
        //If the space bar is pressed then send a packet to the server and launch the rocket
		/*if(/*launch.isPressed()* / false && player.ridingEntity instanceof EntityRocket && !(rocket = (EntityRocket)player.ridingEntity).isInFlight()) {
				PacketHandler.sendToServer(new PacketEntity(rocket, (byte)EntityRocket.PacketType.LAUNCH.ordinal()));
				rocket.launch();
			}*/
 
        // Tier-2 ship pilot: the flight keys are sampled per-tick in onClientTick's
        // handleShipPilotInput; only the edge-triggered Flight-Assist toggle (N) lives here. Guarded
        // by forRider==linked seat, so isPressed() is never consumed for a non-ship pilot (the
        // rocket branch below still gets the N press when riding a rocket).
        TilePilotSeat pilotSeat = TilePilotSeat.forRider(player.getRidingEntity(), minecraft.world);
        if (pilotSeat != null && pilotSeat.isLinked() && flightAssistToggle.isPressed()) {
            PacketHandler.sendToServer(new PacketMachine(pilotSeat, TilePilotSeat.PACKET_FLIGHT_ASSIST_TOGGLE));
            kbTrace("SHIP flight-assist toggle -> seat " + pilotSeat.getPos());
        }
        // Edge-triggered auto-takeoff toggle (K), same seat-gated dispatch as the FA toggle.
        if (pilotSeat != null && pilotSeat.isLinked() && autoTakeoffToggle.isPressed()) {
            PacketHandler.sendToServer(new PacketMachine(pilotSeat, TilePilotSeat.PACKET_AUTO_TAKEOFF_TOGGLE));
            kbTrace("SHIP auto-takeoff toggle -> seat " + pilotSeat.getPos());
        }

        if (player.getRidingEntity() != null && player.getRidingEntity() instanceof EntityRocket) {
            EntityRocket rocket = (EntityRocket) player.getRidingEntity();
            /* spacehammercode : janky in large packs
            if (Minecraft.getMinecraft().inGameHasFocus && player.equals(Minecraft.getMinecraft().player)) {
                if (!rocket.isInFlight() && Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {

                    rocket.prepareLaunch();
                }
                */
            if (Minecraft.getMinecraft().inGameHasFocus && player.equals(Minecraft.getMinecraft().player)) {
                // Classic mode keeps the instant Space launch. Free Flight
                // replaces it with the 3 s engine-start hold sampled per tick
                // in onClientTick — no instant path there.
                if (!rocket.isInFlight()
                        && !rocket.isFreeFlight()
                        && Keyboard.getEventKey() == Keyboard.KEY_SPACE
                        && Keyboard.getEventKeyState()) {
                    kbTrace("SPACE -> prepareLaunch (classic)");
                    rocket.prepareLaunch();
                }

                // Mode toggle (M) — only meaningful before launch, server-side gated anyway.
                if (toggleFlightMode.isPressed() && !rocket.isInFlight()) {
                    RocketFlightMode next = rocket.isFreeFlight()
                            ? RocketFlightMode.CLASSIC_LAUNCH
                            : RocketFlightMode.FREE_FLIGHT;
                    kbTrace("M pressed -> set mode " + next);
                    // Set local intent so writeDataToNetwork serializes the new mode ordinal.
                    rocket.setFlightMode(next);
                    PacketHandler.sendToServer(new PacketEntity(
                            rocket,
                            (byte) EntityRocket.PacketType.SET_FLIGHT_MODE.ordinal()));
                }

                // Flight-assist toggle (N) — persistent state, server-side gated.
                if (flightAssistToggle.isPressed() && rocket.isFreeFlight()) {
                    rocket.setFlightAssistOn(!rocket.isFlightAssistOn());
                    PacketHandler.sendToServer(new PacketEntity(
                            rocket,
                            (byte) EntityRocket.PacketType.SET_FLIGHT_ASSIST.ordinal()));
                }

                // Free Flight steering input is sampled every client tick in
                // onClientTick (below), NOT here: KeyInputEvent only fires on key
                // transitions, so a key held *before* isInFlight replicates to the
                // client would never be sent and the rocket would never climb.
                // The legacy (non-FF) turning path stays edge-driven here.
                if (!(rocket.isFreeFlight() && rocket.isInFlight())) {
                    rocket.onTurnLeft(turnRocketLeft.isKeyDown());
                    rocket.onTurnRight(turnRocketRight.isKeyDown());
                    rocket.onUp(turnRocketUp.isKeyDown());
                    rocket.onDown(turnRocketDown.isKeyDown());
                }
            }
        }

        if (player.getRidingEntity() != null && player.getRidingEntity() instanceof EntityHoverCraft) {
            EntityHoverCraft hoverCraft = (EntityHoverCraft) player.getRidingEntity();
            if (Minecraft.getMinecraft().inGameHasFocus && player.equals(Minecraft.getMinecraft().player)) {
                //hoverCraft.onTurnLeft(turnRocketLeft.isKeyDown());
                //hoverCraft.onTurnRight(turnRocketRight.isKeyDown());
                hoverCraft.onUp(turnRocketUp.isKeyDown());
                hoverCraft.onDown(turnRocketDown.isKeyDown());
            }
        }

        if (toggleJetpack.isPressed()) {
            if (player.isSneaking())
                PacketHandler.sendToServer(new PacketChangeKeyState(1, false));
            else
                PacketHandler.sendToServer(new PacketChangeKeyState(0, false));
        }

        if (openRocketUI.isPressed()) {
            if (player.getRidingEntity() instanceof EntityRocketBase) {
                PacketHandler.sendToServer(new PacketEntity((INetworkEntity) player.getRidingEntity(), (byte) EntityRocket.PacketType.OPENGUI.ordinal()));
            }
        }

        if (toggleRCS.isPressed()) {
            if (player.getRidingEntity() instanceof EntityRocketBase) {
                PacketHandler.sendToServer(new PacketEntity((INetworkEntity) player.getRidingEntity(), (byte) EntityRocket.PacketType.TOGGLE_RCS.ordinal()));
            }
        }


        if (Keyboard.isKeyDown(Keyboard.KEY_SPACE) != prevState) {
            prevState = Keyboard.isKeyDown(Keyboard.KEY_SPACE);
            InputSyncHandler.updateKeyPress(player, Keyboard.KEY_SPACE, prevState);
            PacketHandler.sendToServer(new PacketChangeKeyState(Keyboard.KEY_SPACE, prevState));
        }
    }
}
