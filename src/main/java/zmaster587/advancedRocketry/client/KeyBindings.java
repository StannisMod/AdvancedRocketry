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
    static KeyBinding pitchRocketUp   = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.pitchRocketUp"),   Keyboard.KEY_Q, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding pitchRocketDown = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.pitchRocketDown"), Keyboard.KEY_E, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightStop          = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightStop"),          Keyboard.KEY_B, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightAssistToggle  = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightAssistToggle"),  Keyboard.KEY_N, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    static KeyBinding flightHoverHold     = new KeyBinding(LibVulpes.proxy.getLocalizedString("key.flightHoverHold"),     Keyboard.KEY_H, LibVulpes.proxy.getLocalizedString("key.controls." + Constants.modId));
    boolean prevState;
    /** Last FF input dispatched to the server. We only resend when the intent actually changes (saves bandwidth). */
    private FreeFlightInput lastSentInput = FreeFlightInput.zero();
    /** Tracks FF-gate transitions for [FF-TRACE] logging. */
    private boolean wasFreeFlightActive = false;

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
        ClientRegistry.registerKeyBinding(pitchRocketUp);
        ClientRegistry.registerKeyBinding(pitchRocketDown);
        ClientRegistry.registerKeyBinding(flightStop);
        ClientRegistry.registerKeyBinding(flightAssistToggle);
        ClientRegistry.registerKeyBinding(flightHoverHold);
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
        lines.add(I18n.format("msg.ff.hud.move",  key(gs.keyBindForward), key(gs.keyBindBack)));
        lines.add(I18n.format("msg.ff.hud.yaw",   key(turnRocketLeft),    key(turnRocketRight)));
        lines.add(I18n.format("msg.ff.hud.vert",  key(turnRocketUp),      key(turnRocketDown)));
        lines.add(I18n.format("msg.ff.hud.pitch", key(pitchRocketUp),     key(pitchRocketDown)));
        lines.add(I18n.format("msg.ff.hud.brake", key(gs.keyBindSneak),   key(flightStop)));
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
            // Reset so the next entry into FF sends a fresh, current snapshot.
            lastSentInput = FreeFlightInput.zero();
            return;
        }

        float fwd  = (mc.gameSettings.keyBindForward.isKeyDown() ?  1f : 0f)
                   + (mc.gameSettings.keyBindBack.isKeyDown()    ? -1f : 0f);
        float vert = (turnRocketUp.isKeyDown()   ?  1f : 0f)
                   + (turnRocketDown.isKeyDown() ? -1f : 0f);
        float yaw  = (turnRocketRight.isKeyDown() ?  1f : 0f)
                   + (turnRocketLeft.isKeyDown()  ? -1f : 0f);
        // Q = nose up (pitch < 0 looks up in MC convention), E = nose down.
        float pitch = (pitchRocketUp.isKeyDown()   ? -1f : 0f)
                    + (pitchRocketDown.isKeyDown() ?  1f : 0f);
        float brake = mc.gameSettings.keyBindSneak.isKeyDown() ? 1f : 0f;
        boolean stop  = flightStop.isKeyDown();
        boolean hover = flightHoverHold.isKeyDown();

        FreeFlightInput input = new FreeFlightInput(fwd, vert, yaw, pitch, brake, stop, hover);
        if (!input.equals(lastSentInput)) {
            kbTrace("send FF input " + input);
            rocket.applyFreeFlightInput(input);
            PacketHandler.sendToServer(new PacketEntity(
                    rocket, (byte) EntityRocket.PacketType.FREE_FLIGHT_INPUT.ordinal()));
            lastSentInput = input;
        }
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
