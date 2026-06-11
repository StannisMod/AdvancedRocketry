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
import zmaster587.advancedRocketry.entity.EntityHoverCraft;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.interfaces.INetworkEntity;
import zmaster587.libVulpes.network.PacketChangeKeyState;
import zmaster587.libVulpes.network.PacketEntity;
import zmaster587.libVulpes.network.PacketHandler;
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
    static KeyBinding flightStop          = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightStop"),          Keyboard.KEY_B, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightAssistToggle  = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightAssistToggle"),  Keyboard.KEY_N, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightHoverHold     = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightHoverHold"),     Keyboard.KEY_H, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    boolean prevState;
    /** Last FF input dispatched to the server. We only resend when the intent actually changes (saves bandwidth). */
    private FreeFlightInput lastSentInput = FreeFlightInput.zero();
    /** Tracks FF-gate transitions for [FF-TRACE] logging. */
    private boolean wasFreeFlightActive = false;
    /** Camera-pin state for mouse-as-rate steering (TASK-46 D1): the player
     *  rotation we pinned at the end of the previous tick. Whatever the mouse
     *  added on top of it since is this tick's turn command. Static so the
     *  PosLook re-pin (see {@link #repinCameraAfterTeleport()}) can keep the
     *  pin and the delta baseline consistent. */
    private static volatile float lastPinnedYaw, lastPinnedPitch;
    /** True once the camera has been pinned to the craft this flight — gates
     *  the frame-time lock telemetry in RocketEventHandler so pre-takeoff
     *  frames (arbitrary look) don't pollute it. */
    private static volatile boolean cameraPinValid = false;

    public static boolean isCameraPinnedThisFlight() {
        return cameraPinValid;
    }

    /**
     * Undo the vanilla riding echo. While a player rides, the server answers
     * every position report with an SPacketPlayerPosLook that carries the
     * rotation the client sent ~1 RTT ago — with the camera hard-locked to a
     * turning craft that snaps the view back by (turn rate × latency), a
     * visible per-frame hiccup. Called from the PosLook mixin after the vanilla
     * handler ran: re-pin the camera to the craft and move the mouse-delta
     * baseline with it, so the next tick still reads pure mouse motion (no
     * feedback). Mouse motion pending at the moment of the teleport is lost —
     * rare and at most one tick's worth.
     */
    public static void repinCameraAfterTeleport() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.player == null || !cameraPinValid) return;
        if (!mc.isCallingFromMinecraftThread()) return; // netty-thread early return path
        EntityPlayerSP player = mc.player;
        if (!(player.getRidingEntity() instanceof EntityRocket)) return;
        EntityRocket rocket = (EntityRocket) player.getRidingEntity();
        if (!rocket.isFreeFlight() || !rocket.isInFlight()) return;
        player.rotationYaw       = rocket.rotationYaw;
        player.prevRotationYaw   = rocket.prevRotationYaw;
        player.rotationPitch     = rocket.rotationPitch;
        player.prevRotationPitch = rocket.prevRotationPitch;
        lastPinnedYaw   = rocket.rotationYaw;
        lastPinnedPitch = rocket.rotationPitch;
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
        ClientRegistry.registerKeyBinding(flightStop);
        ClientRegistry.registerKeyBinding(flightAssistToggle);
        ClientRegistry.registerKeyBinding(flightHoverHold);
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
    public static java.util.List<String> freeFlightHudLines(boolean inFlight, boolean flightAssistOn) {
        GameSettings gs = Minecraft.getMinecraft().gameSettings;
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (!inFlight) {
            lines.add(I18n.format("msg.ff.hud.title"));
            lines.add(I18n.format("msg.ff.hud.prelaunch",
                    GameSettings.getKeyDisplayString(Keyboard.KEY_SPACE), key(toggleFlightMode)));
            return lines;
        }
        lines.add(I18n.format("msg.ff.hud.active",
                I18n.format(flightAssistOn ? "msg.ff.hud.fa.on" : "msg.ff.hud.fa.off")));
        lines.add(I18n.format("msg.ff.hud.move",   key(gs.keyBindForward), key(gs.keyBindBack)));
        lines.add(I18n.format("msg.ff.hud.strafe", key(strafeLeft),        key(strafeRight)));
        lines.add(I18n.format("msg.ff.hud.vert",   key(flightVerticalUp),  key(flightVerticalDown)));
        lines.add(I18n.format("msg.ff.hud.yaw",    key(turnRocketLeft),    key(turnRocketRight)));
        lines.add(I18n.format("msg.ff.hud.pitchmouse"));
        lines.add(I18n.format("msg.ff.hud.cut",    key(turnRocketDown)));
        lines.add(I18n.format("msg.ff.hud.brake",  key(gs.keyBindSneak),   key(flightStop)));
        lines.add(I18n.format("msg.ff.hud.assist", key(flightAssistToggle), key(flightHoverHold)));
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
        // Don't steer while a GUI is open. (We intentionally do NOT require
        // inGameHasFocus — losing window focus shouldn't freeze the controls,
        // and the headless test bot never reports focus.)
        if (player == null || mc.currentScreen != null) return;
        if (!(player.getRidingEntity() instanceof EntityRocket)) {
            if (wasFreeFlightActive) { kbTrace("FF gate -> inactive (no longer riding a rocket)"); wasFreeFlightActive = false; }
            return;
        }

        EntityRocket rocket = (EntityRocket) player.getRidingEntity();
        boolean active = rocket.isFreeFlight() && rocket.isInFlight();
        if (active != wasFreeFlightActive) {
            kbTrace("FF gate active=" + active + " (isFreeFlight=" + rocket.isFreeFlight()
                    + " isInFlight=" + rocket.isInFlight() + ")");
            wasFreeFlightActive = active;
        }
        if (!active) {
            // Reset so the next entry into FF sends a fresh, current snapshot
            // and the camera re-aligns to the craft on the next takeoff.
            lastSentInput = FreeFlightInput.zero();
            cameraPinValid = false;
            return;
        }

        // Throttle cut (X): hold to neutralise all translation thrust this tick.
        boolean cut = turnRocketDown.isKeyDown();

        float fwd = cut ? 0f
                : (mc.gameSettings.keyBindForward.isKeyDown() ?  1f : 0f)
                + (mc.gameSettings.keyBindBack.isKeyDown()    ? -1f : 0f);
        float strafe = cut ? 0f
                : (strafeRight.isKeyDown() ?  1f : 0f)
                + (strafeLeft.isKeyDown()  ? -1f : 0f);
        float vert = cut ? 0f
                : (flightVerticalUp.isKeyDown()   ?  1f : 0f)
                + (flightVerticalDown.isKeyDown() ? -1f : 0f);

        // ---- Mouse-as-rate steering + camera-nose lock (TASK-46 D1) --------
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
        float yaw   = FreeFlightInput.clamp((float) FreeFlightPhysics.rateFromMouseDelta(
                mouseYawDelta, FreeFlightPhysics.MAX_YAW_RATE) + yawKeys);
        float pitch = (float) FreeFlightPhysics.rateFromMouseDelta(
                mousePitchDelta, FreeFlightPhysics.MAX_PITCH_RATE);

        float brake = mc.gameSettings.keyBindSneak.isKeyDown() ? 1f : 0f;
        boolean stop  = flightStop.isKeyDown();
        boolean hover = flightHoverHold.isKeyDown();

        FreeFlightInput input = new FreeFlightInput(fwd, vert, strafe, yaw, pitch, brake, stop, hover);
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
 
        if (player.getRidingEntity() != null && player.getRidingEntity() instanceof EntityRocket) {
            EntityRocket rocket = (EntityRocket) player.getRidingEntity();
            /* spacehammercode : janky in large packs
            if (Minecraft.getMinecraft().inGameHasFocus && player.equals(Minecraft.getMinecraft().player)) {
                if (!rocket.isInFlight() && Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {

                    rocket.prepareLaunch();
                }
                */
            if (Minecraft.getMinecraft().inGameHasFocus && player.equals(Minecraft.getMinecraft().player)) {
                if (!rocket.isInFlight()
                        && Keyboard.getEventKey() == Keyboard.KEY_SPACE
                        && Keyboard.getEventKeyState()) {
                    kbTrace("SPACE -> prepareLaunch (isFreeFlight=" + rocket.isFreeFlight() + ")");
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
