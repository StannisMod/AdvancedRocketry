package com.github.stannismod.forge.testing.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ForgeTestClientBootstrap {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicLong CLIENT_TICKS = new AtomicLong(0L);

    /**
     * The server address the last "disconnect" command left, so a later "connect" can rejoin it.
     * Only the disconnect/connect pair uses this; "reconnect" reads the address off the live
     * connection and never touches it. Client-thread confined (both writers and the reader run
     * via runOnClientThread).
     */
    private static String lastServerHost;
    private static int lastServerPort;

    /**
     * A connection teardown (quit, or quit+reconnect) deferred to the next ClientTickEvent.
     *
     * <p>NEVER close the server channel from inside the scheduled-task drain (runOnClientThread):
     * {@code Minecraft.runGameLoop} HOLDS the {@code scheduledTasks} monitor while draining, and
     * {@code NetworkManager.closeChannel} then waits on the netty event loop — which can itself be
     * BLOCKED in {@code Minecraft.addScheduledTask} on that same monitor, delivering an inbound
     * packet. Measured deadlock (2026-07-22): a mid-transit relog raced a Valkyrien Skies
     * ship-index packet; client thread waited on the close promise, Netty Client IO waited on the
     * task queue, forever. The tick event fires on the client thread OUTSIDE the drain, so a quit
     * performed there cannot deadlock against inbound traffic.</p>
     */
    private static final java.util.concurrent.atomic.AtomicReference<Runnable>
            PENDING_CONNECTION_ACTION = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Ring buffer of sound locations the client {@code SoundManager} was asked
     * to play ({@code PlaySoundEvent} fires once per {@code playSound(ISound)}
     * on the real client). Read via {@code report_sounds}, reset via
     * {@code clear_sounds}. Capped so a long-running client can't grow it
     * unbounded; the cap only matters for tests that never clear.
     */
    private static final Object SOUND_LOG_LOCK = new Object();
    private static final java.util.ArrayDeque<String> PLAYED_SOUNDS = new java.util.ArrayDeque<>();
    private static final int PLAYED_SOUNDS_CAP = 256;
    private static final AtomicLong SOUNDS_TOTAL = new AtomicLong(0L);

    private ForgeTestClientBootstrap() {
    }

    public static void bootstrap() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        installClientLogFile();
        FMLCommonHandler.instance().bus().register(new TickCounter());
        FMLCommonHandler.instance().bus().register(new SoundRecorder());
        Thread bridgeThread = new Thread(ForgeTestClientBootstrap::runBridge, "forge-test-client-bridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    private static void installClientLogFile() {
        String logFile = System.getProperty("forge.test.client.logFile");
        if (logFile == null || logFile.trim().isEmpty()) {
            return;
        }

        try {
            File file = new File(logFile);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                // Best effort only.
                parent.mkdirs();
            }

            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream fileStream = new PrintStream(new FileOutputStream(file, true), true, StandardCharsets.UTF_8.name());
            PrintStream teeOut = new PrintStream(new TeeOutputStream(originalOut, fileStream), true, StandardCharsets.UTF_8.name());
            PrintStream teeErr = new PrintStream(new TeeOutputStream(originalErr, fileStream), true, StandardCharsets.UTF_8.name());
            System.setOut(teeOut);
            System.setErr(teeErr);
            System.out.println("Forge test client bootstrap logging installed: " + file.getAbsolutePath());
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private static void runBridge() {
        Integer port = Integer.getInteger("forge.test.client.port");
        if (port == null || port <= 0) {
            return;
        }

        Socket socket = null;
        try {
            socket = connectWithRetry(port.intValue());
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write("READY");
            writer.newLine();
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject response;
                try {
                    JsonElement parsed = new JsonParser().parse(line);
                    if (!parsed.isJsonObject()) {
                        response = error("Malformed command payload");
                    } else {
                        response = handleCommand(parsed.getAsJsonObject());
                    }
                } catch (RuntimeException exception) {
                    response = error(exception.getMessage() == null ? exception.toString() : exception.getMessage());
                }

                writer.write(response.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Nothing left to do.
                }
            }
        }
    }

    private static Socket connectWithRetry(int port) throws IOException {
        IOException last = null;
        long deadline = System.nanoTime() + com.github.stannismod.forge.testing.TestTimeouts
                .scaledNanos(TimeUnit.MINUTES.toNanos(2));

        while (System.nanoTime() < deadline) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return socket;
            } catch (IOException exception) {
                last = exception;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for test bridge socket", interruptedException);
                }
            }
        }

        throw new IOException("Timed out connecting Forge test bridge", last);
    }

    private static JsonObject handleCommand(JsonObject request) {
        String command = request.has("command") ? request.get("command").getAsString() : "";
        switch (command) {
            case "wait_world":
                waitForWorld();
                return ok();
            case "wait_ticks":
                return waitTicks(request);
            case "select_hotbar":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    int slot = boundedInt(request, "slot", 0, 8);
                    mc.player.inventory.currentItem = slot;
                    mc.player.connection.sendPacket(new CPacketHeldItemChange(slot));
                    JsonObject response = ok();
                    response.addProperty("selectedHotbar", slot);
                    return response;
                });
            case "right_click_block":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    EntityPlayerSP player = requirePlayer(mc);
                    PlayerControllerMP controller = mc.playerController;
                    BlockPos pos = new BlockPos(requireInt(request, "x"), requireInt(request, "y"), requireInt(request, "z"));
                    EnumFacing face = EnumFacing.valueOf(requireString(request, "face").toUpperCase(Locale.ROOT));
                    EnumHand hand = EnumHand.valueOf(requireString(request, "hand").toUpperCase(Locale.ROOT));
                    Vec3d hit = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                    controller.processRightClickBlock(player, mc.world, pos, face, hit, hand);
                    return ok();
                });
            case "click_screen_point":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = mc.currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to click");
                    }
                    invokeMouseClicked(screen, requireInt(request, "x"), requireInt(request, "y"), boundedInt(request, "button", 0, 2));
                    return ok();
                });
            case "click_button":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = mc.currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to click");
                    }
                    int index = boundedInt(request, "index", 0, Integer.MAX_VALUE);
                    List<?> buttons = buttonList(screen);
                    if (index < 0 || index >= buttons.size()) {
                        throw new IllegalArgumentException("Button index " + index + " is out of range");
                    }
                    GuiButton button = (GuiButton) buttons.get(index);
                    invokeMouseClicked(screen, button.x + button.width / 2, button.y + button.height / 2, 0);
                    return ok();
                });
            case "click_button_ratio":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = mc.currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to click");
                    }
                    int index = boundedInt(request, "index", 0, Integer.MAX_VALUE);
                    double ratio = request.has("ratio") ? request.get("ratio").getAsDouble() : 0.5D;
                    ratio = Math.max(0.0D, Math.min(1.0D, ratio));
                    List<?> buttons = buttonList(screen);
                    if (index < 0 || index >= buttons.size()) {
                        throw new IllegalArgumentException("Button index " + index + " is out of range");
                    }
                    GuiButton button = (GuiButton) buttons.get(index);
                    int x = button.x + 2 + (int) Math.round((button.width - 8 - 4) * ratio);
                    int y = button.y + button.height / 2;
                    invokeMouseClicked(screen, x, y, 0);
                    return ok();
                });
            case "report_buttons":
                return runOnClientThread(() -> {
                    GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to inspect");
                    }
                    JsonArray buttons = new JsonArray();
                    for (GuiButton button : collectAllButtons(screen)) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", button.id);
                        entry.addProperty("text", button.displayString == null ? "" : button.displayString);
                        entry.addProperty("x", button.x);
                        entry.addProperty("y", button.y);
                        entry.addProperty("width", button.width);
                        entry.addProperty("height", button.height);
                        entry.addProperty("enabled", button.enabled);
                        entry.addProperty("visible", button.visible);
                        buttons.add(entry);
                    }
                    JsonObject response = ok();
                    response.add("buttons", buttons);
                    return response;
                });
            case "click_button_id":
                return runOnClientThread(() -> {
                    GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to click");
                    }
                    int targetId = requireInt(request, "id");
                    GuiButton match = null;
                    for (GuiButton button : collectAllButtons(screen)) {
                        if (button.id == targetId) {
                            match = button;
                            break;
                        }
                    }
                    if (match == null) {
                        throw new IllegalArgumentException("No GUI button with id " + targetId);
                    }
                    if (!match.visible || !match.enabled) {
                        throw new IllegalStateException("GUI button id " + targetId
                                + " is not clickable (visible=" + match.visible
                                + ", enabled=" + match.enabled + ")");
                    }
                    // Dispatch through actionPerformed rather than a synthetic
                    // mouse click: coordinate-free, and libVulpes' GuiModular
                    // forwards actionPerformed to every module — so this hits
                    // module-local buttons (planet selector grid, …) that never
                    // land in GuiScreen.buttonList.
                    invokeActionPerformed(screen, match);
                    return ok();
                });
            case "report_slots":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (!(mc.currentScreen instanceof GuiContainer)) {
                        throw new IllegalStateException("Current GUI is not a container screen");
                    }
                    net.minecraft.inventory.Container container =
                            ((GuiContainer) mc.currentScreen).inventorySlots;
                    JsonArray slots = new JsonArray();
                    for (Slot slot : container.inventorySlots) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("slot", slot.slotNumber);
                        entry.addProperty("x", slot.xPos);
                        entry.addProperty("y", slot.yPos);
                        entry.addProperty("playerSlot",
                                mc.player != null && slot.inventory == mc.player.inventory);
                        ItemStack stack = slot.getStack();
                        entry.addProperty("hasStack", !stack.isEmpty());
                        entry.addProperty("item", stack.isEmpty()
                                ? "" : String.valueOf(stack.getItem().getRegistryName()));
                        entry.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
                        slots.add(entry);
                    }
                    JsonObject response = ok();
                    response.add("slots", slots);
                    return response;
                });
            case "click_slot":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (!(mc.currentScreen instanceof GuiContainer)) {
                        throw new IllegalStateException("Current GUI is not a container screen");
                    }
                    GuiContainer containerScreen = (GuiContainer) mc.currentScreen;
                    int slotId = requireInt(request, "slot");
                    int mouseButton = boundedInt(request, "button", 0, 2);
                    String modeName = request.has("mode")
                            ? request.get("mode").getAsString() : "PICKUP";
                    ClickType clickType;
                    try {
                        clickType = ClickType.valueOf(modeName.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException invalid) {
                        throw new IllegalArgumentException("Unknown click mode '" + modeName
                                + "' — expected one of PICKUP, QUICK_MOVE, SWAP, CLONE, THROW,"
                                + " QUICK_CRAFT, PICKUP_ALL");
                    }
                    Slot slot = null;
                    for (Slot candidate : containerScreen.inventorySlots.inventorySlots) {
                        if (candidate.slotNumber == slotId) {
                            slot = candidate;
                            break;
                        }
                    }
                    if (slot == null) {
                        throw new IllegalArgumentException("No container slot with id " + slotId);
                    }
                    invokeHandleMouseClick(containerScreen, slot, slotId, mouseButton, clickType);
                    return ok();
                });
            case "drag_screen_point":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = mc.currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to drag");
                    }
                    int startX = requireInt(request, "startX");
                    int startY = requireInt(request, "startY");
                    int endX = requireInt(request, "endX");
                    int endY = requireInt(request, "endY");
                    int button = boundedInt(request, "button", 0, 2);
                    invokeMouseClicked(screen, startX, startY, button);
                    for (int step = 1; step <= 8; step++) {
                        int x = startX + (int) Math.round((endX - startX) * (step / 8.0D));
                        int y = startY + (int) Math.round((endY - startY) * (step / 8.0D));
                        invokeMouseClickMove(screen, x, y, button, step * 50L);
                    }
                    invokeMouseReleased(screen, endX, endY, button);
                    return ok();
                });
            case "focus_field":
                return runOnClientThread(() -> {
                    GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to focus");
                    }
                    String fieldName = requireString(request, "field");
                    GuiTextField textField = textField(screen, fieldName);
                    textField.setFocused(true);
                    textField.setCursorPositionEnd();
                    return ok();
                });
            case "type_text":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    GuiScreen screen = mc.currentScreen;
                    if (screen == null) {
                        throw new IllegalStateException("No current GUI to type into");
                    }
                    String text = requireString(request, "text");
                    for (int i = 0; i < text.length(); i++) {
                        char typed = text.charAt(i);
                        invokeKeyTyped(screen, typed, 0);
                    }
                    if (request.has("pressEnter") && request.get("pressEnter").getAsBoolean()) {
                        invokeKeyTyped(screen, '\n', Keyboard.KEY_RETURN);
                    }
                    return ok();
                });
            case "close_screen":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player != null) {
                        mc.player.closeScreen();
                    } else {
                        mc.displayGuiScreen(null);
                    }
                    return ok();
                });
            case "reset_client_state":
                // Puts the client back to the state a freshly-booted one is in, for the
                // channels that measurably survive a scenario when ONE harness carries
                // several. Measured 2026-08-06, all four fired at once on the second
                // scenario of a shared run: an open GuiModular, an action-bar overlay
                // still counting down at overlayTicks=50, an inherited hotbar, and a
                // player still standing where the previous scenario left him.
                //
                // Position and inventory are the SERVER's to reset (a tp / clear through
                // the command channel); everything reset here is client-owned state the
                // server cannot reach. The response reports what was actually found dirty
                // so a caller can assert on the reset instead of trusting it — a reset
                // nobody checks is indistinguishable from no reset at all.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();

                    String hadScreen = mc.currentScreen == null
                            ? "" : mc.currentScreen.getClass().getName();
                    if (mc.player != null) {
                        mc.player.closeScreen();
                    } else {
                        mc.displayGuiScreen(null);
                    }
                    response.addProperty("clearedScreen", hadScreen);

                    clearChatAndOverlay(mc, response);

                    // A key left down by set_key/holdKey keeps driving production input
                    // handlers into the next scenario.
                    net.minecraft.client.settings.KeyBinding.unPressAllKeys();
                    response.addProperty("keysReleased", true);

                    return response;
                });
            case "clear_chat":
                // The observation channel ALONE, with the screen left exactly as it is.
                //
                // A test that reads "the player was told X" must clear the chat immediately
                // before the stimulus, because the harness itself writes to that channel —
                // every server command echoes a FORGE_TEST_DONE marker into it. But a GUI
                // test's stimulus is a click on an OPEN screen, and reset_client_state closes
                // the screen, so using it to arm the channel destroys the arrangement it was
                // called to protect. Hence this narrower verb: same chat/overlay wipe, no
                // screen close, no key release.
                return runOnClientThread(() -> {
                    JsonObject response = ok();
                    clearChatAndOverlay(Minecraft.getMinecraft(), response);
                    return response;
                });
            case "report_state":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    response.addProperty("worldReady", mc.world != null && mc.player != null);
                    response.addProperty("screen", mc.currentScreen == null ? "" : mc.currentScreen.getClass().getName());
                    response.addProperty("ticks", CLIENT_TICKS.get());
                    response.addProperty("screenWidth", mc.currentScreen == null ? 0 : mc.currentScreen.width);
                    response.addProperty("screenHeight", mc.currentScreen == null ? 0 : mc.currentScreen.height);
                    response.addProperty("guiLeft", 0);
                    response.addProperty("guiTop", 0);
                    response.addProperty("guiXSize", 0);
                    response.addProperty("guiYSize", 0);
                    if (mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer) {
                        net.minecraft.client.gui.inventory.GuiContainer containerScreen = (net.minecraft.client.gui.inventory.GuiContainer) mc.currentScreen;
                        response.addProperty("guiLeft", intField(containerScreen, "guiLeft"));
                        response.addProperty("guiTop", intField(containerScreen, "guiTop"));
                        response.addProperty("guiXSize", intField(containerScreen, "xSize"));
                        response.addProperty("guiYSize", intField(containerScreen, "ySize"));
                    }
                    if (mc.player != null) {
                        response.addProperty("selectedHotbar", mc.player.inventory.currentItem);
                        response.addProperty("playerX", mc.player.posX);
                        response.addProperty("playerY", mc.player.posY);
                        response.addProperty("playerZ", mc.player.posZ);
                        response.addProperty("playerYaw", mc.player.rotationYaw);
                        response.addProperty("playerPitch", mc.player.rotationPitch);
                        // The client owns a player's movement, so his VELOCITY is what decides
                        // whether a server-side teleport survives the next few ticks: a body
                        // carrying a large one is moved back off wherever it was put, and a test
                        // reading only the position cannot tell that from a teleport that never
                        // arrived. Reported here so the answer is one read rather than an inference
                        // from two positions taken at unknown times.
                        response.addProperty("motionX", mc.player.motionX);
                        response.addProperty("motionY", mc.player.motionY);
                        response.addProperty("motionZ", mc.player.motionZ);
                        response.addProperty("onGround", mc.player.onGround);
                        response.addProperty("health", mc.player.getHealth());
                        response.addProperty("heldItem", mc.player.getHeldItemMainhand().isEmpty()
                                ? ""
                                : String.valueOf(mc.player.getHeldItemMainhand().getItem().getRegistryName()));
                    }
                    if (mc.currentScreen instanceof GuiContainer) {
                        response.addProperty("container", mc.currentScreen.getClass().getName());
                    }
                    return response;
                });
            case "report_riding_entity":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    net.minecraft.entity.Entity ridden =
                            mc.player == null ? null : mc.player.getRidingEntity();
                    response.addProperty("riding", ridden != null);
                    if (ridden != null) {
                        response.addProperty("entityClass", ridden.getClass().getName());
                        response.addProperty("entityId", ridden.getEntityId());
                        response.addProperty("posX", ridden.posX);
                        response.addProperty("posY", ridden.posY);
                        response.addProperty("posZ", ridden.posZ);
                        response.addProperty("motionX", ridden.motionX);
                        response.addProperty("motionY", ridden.motionY);
                        response.addProperty("motionZ", ridden.motionZ);
                        response.addProperty("rotationYaw", ridden.rotationYaw);
                        response.addProperty("rotationPitch", ridden.rotationPitch);
                    }
                    return response;
                });
            case "set_look":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    float yaw = request.get("yaw").getAsFloat();
                    float pitch = request.get("pitch").getAsFloat();
                    JsonObject response = ok();
                    if (mc.player != null) {
                        // Set both current and prev so the look snaps without a
                        // render-interpolation sweep — mirrors an instantaneous aim.
                        mc.player.rotationYaw = yaw;
                        mc.player.prevRotationYaw = yaw;
                        mc.player.rotationPitch = pitch;
                        mc.player.prevRotationPitch = pitch;
                        response.addProperty("applied", true);
                    } else {
                        response.addProperty("applied", false);
                    }
                    response.addProperty("yaw", yaw);
                    response.addProperty("pitch", pitch);
                    return response;
                });
            case "reconnect":
                return runOnClientThread(() -> {
                    // A REAL relog: quit the server connection and reconnect to the same
                    // address, exactly as the disconnect button + server rejoin would. The
                    // server sees a full player logout (data saved) and a fresh login; the
                    // client rebuilds its world and player entity. The control bridge lives
                    // at JVM level and survives. Callers follow with wait_world.
                    // The teardown itself is DEFERRED to the next client tick — closing the
                    // channel inside this scheduled task deadlocks against inbound packets
                    // (see PENDING_CONNECTION_ACTION).
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.getConnection() == null || mc.world == null) {
                        response.addProperty("applied", false);
                        return response;
                    }
                    java.net.SocketAddress remote =
                            mc.getConnection().getNetworkManager().getRemoteAddress();
                    if (!(remote instanceof java.net.InetSocketAddress)) {
                        response.addProperty("applied", false);
                        return response;
                    }
                    java.net.InetSocketAddress addr = (java.net.InetSocketAddress) remote;
                    String host = addr.getAddress().getHostAddress();
                    int port = addr.getPort();
                    PENDING_CONNECTION_ACTION.set(() -> {
                        // Step markers on stdout: a hang here leaves no stacktrace, and the
                        // surviving client.log's LAST marker names the hung step.
                        System.out.println("[forge-test] reconnect: quitting");
                        Minecraft m = Minecraft.getMinecraft();
                        if (m.world != null) {
                            m.world.sendQuittingDisconnectingPacket();
                        }
                        System.out.println("[forge-test] reconnect: unloading world");
                        m.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
                        System.out.println("[forge-test] reconnect: connecting to " + host + ":" + port);
                        m.displayGuiScreen(new net.minecraft.client.multiplayer.GuiConnecting(
                                new net.minecraft.client.gui.GuiMainMenu(), m, host, port));
                        System.out.println("[forge-test] reconnect: initiated");
                    });
                    response.addProperty("applied", true);
                    response.addProperty("host", host);
                    response.addProperty("port", port);
                    return response;
                });
            case "disconnect":
                return runOnClientThread(() -> {
                    // The DISCONNECT half of "reconnect": quit the server connection and STAY at
                    // the main menu, exactly as the player's own disconnect button would. The
                    // server performs a full logout (player data saved to disk) and the world
                    // keeps running without the player - which is the point: a test can now act
                    // on the server while the player is genuinely OFFLINE, then "connect" back.
                    // The address is remembered for that later "connect"; the control bridge
                    // lives at JVM level and survives without a world. The teardown is DEFERRED
                    // to the next client tick like reconnect's (see PENDING_CONNECTION_ACTION).
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.getConnection() == null || mc.world == null) {
                        response.addProperty("applied", false);
                        return response;
                    }
                    java.net.SocketAddress remote =
                            mc.getConnection().getNetworkManager().getRemoteAddress();
                    if (!(remote instanceof java.net.InetSocketAddress)) {
                        response.addProperty("applied", false);
                        return response;
                    }
                    java.net.InetSocketAddress addr = (java.net.InetSocketAddress) remote;
                    lastServerHost = addr.getAddress().getHostAddress();
                    lastServerPort = addr.getPort();
                    PENDING_CONNECTION_ACTION.set(() -> {
                        System.out.println("[forge-test] disconnect: quitting");
                        Minecraft m = Minecraft.getMinecraft();
                        if (m.world != null) {
                            m.world.sendQuittingDisconnectingPacket();
                        }
                        m.loadWorld((net.minecraft.client.multiplayer.WorldClient) null);
                        m.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
                        System.out.println("[forge-test] disconnect: at main menu");
                    });
                    response.addProperty("applied", true);
                    response.addProperty("host", lastServerHost);
                    response.addProperty("port", lastServerPort);
                    return response;
                });
            case "connect":
                return runOnClientThread(() -> {
                    // The CONNECT half: rejoin the server a prior "disconnect" left, exactly as
                    // the player's rejoin would - the server sees a fresh login and re-reads the
                    // player's saved data. Asynchronous like "reconnect": callers follow with
                    // wait_world. Fails loudly without a remembered address (a connect that
                    // silently went nowhere would make every later observation unattributable),
                    // and no-ops when a world is already up (the caller's sequencing is broken).
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.getConnection() != null && mc.world != null) {
                        response.addProperty("applied", false);
                        return response;
                    }
                    if (lastServerHost == null) {
                        return error("connect without a prior disconnect: no remembered address");
                    }
                    mc.displayGuiScreen(new net.minecraft.client.multiplayer.GuiConnecting(
                            new net.minecraft.client.gui.GuiMainMenu(), mc,
                            lastServerHost, lastServerPort));
                    response.addProperty("applied", true);
                    response.addProperty("host", lastServerHost);
                    response.addProperty("port", lastServerPort);
                    return response;
                });
            case "turn_look":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    float dYaw = request.get("deltaYaw").getAsFloat();
                    float dPitch = request.get("deltaPitch").getAsFloat();
                    JsonObject response = ok();
                    if (mc.player != null) {
                        // The REAL mouse path: Entity.turn is exactly what the game's own
                        // mouse handler feeds accumulated deltas into, so mod hooks on the
                        // turn (frame-relative look transforms) run - unlike set_look,
                        // which writes the rotation fields directly.
                        mc.player.turn(dYaw, dPitch);
                        response.addProperty("applied", true);
                        response.addProperty("yaw", mc.player.rotationYaw);
                        response.addProperty("pitch", mc.player.rotationPitch);
                    } else {
                        response.addProperty("applied", false);
                    }
                    return response;
                });
            case "set_key":
                return runOnClientThread(() -> {
                    int keyCode = requireInt(request, "keyCode");
                    boolean pressed = request.has("pressed") && request.get("pressed").getAsBoolean();
                    // Drive the binding's held-state (isKeyDown) and, on press, a
                    // single isPressed() edge via onTick — mirroring a real key.
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(keyCode, pressed);
                    if (pressed) {
                        net.minecraft.client.settings.KeyBinding.onTick(keyCode);
                    }
                    // ...and fire Forge's KeyInputEvent, exactly where the real keyboard fires it:
                    // Minecraft.runTickKeyboard calls FMLCommonHandler.fireKeyInput() at the end of
                    // EVERY iteration of its `while (Keyboard.next())` loop — press and release
                    // alike. Without this, an injected key drives only handlers that POLL key state
                    // on ClientTickEvent; every edge-triggered handler subscribed to
                    // InputEvent.KeyInputEvent (the idiomatic place for a one-shot toggle key) is
                    // unreachable from a test, and a test that "presses" such a key silently
                    // asserts nothing. Note the event carries no key: a handler that reads
                    // Keyboard.getEventKey()/getEventKeyState() directly still sees LWJGL's own
                    // (here: empty) event state, so it must poll its KeyBinding instead.
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().fireKeyInput();
                    JsonObject response = ok();
                    response.addProperty("keyCode", keyCode);
                    response.addProperty("pressed", pressed);
                    return response;
                });
            case "read_static_field":
                return runOnClientThread(() -> {
                    String className = requireString(request, "className");
                    String fieldName = requireString(request, "fieldName");
                    JsonObject response = ok();
                    try {
                        Class<?> clazz = Class.forName(className);
                        java.lang.reflect.Field field = findField(clazz, fieldName);
                        field.setAccessible(true);
                        Object value = field.get(null);
                        response.addProperty("isNull", value == null);
                        response.addProperty("value", value == null ? "" : String.valueOf(value));
                        response.addProperty("type", value == null ? "null" : value.getClass().getName());
                    } catch (Throwable t) {
                        throw new IllegalStateException("read_static_field(" + className + "#"
                                + fieldName + ") failed: " + t, t);
                    }
                    return response;
                });
            case "use_item":
                // Right-click the held item "in the air" (no block target):
                // PlayerControllerMP.processRightClick sends the real
                // CPacketPlayerTryUseItem, so Item.onItemRightClick runs on
                // both sides against the real player.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null || mc.world == null) {
                        throw new IllegalStateException("use_item: client world/player not ready");
                    }
                    net.minecraft.util.EnumActionResult result = mc.playerController
                            .processRightClick(mc.player, mc.world, EnumHand.MAIN_HAND);
                    JsonObject response = ok();
                    response.addProperty("result", result.name());
                    return response;
                });
            case "report_chat":
                // Recent lines of the client chat overlay (GuiNewChat), newest
                // first — i18n ALREADY RESOLVED, exactly what the player reads.
                // The honest observation for "the player got a chat message".
                // Also reports the ACTION-BAR overlay (GuiIngame.setOverlayMessage,
                // the GAME_INFO chat type) under "overlay"/"overlayTicks" — those
                // messages never enter GuiNewChat, so without this a server's
                // action-bar reply is invisible to the harness. "overlay" is the
                // last one shown (empty before any); overlayTicks > 0 = still on
                // screen right now.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    int limit = request.has("limit") ? request.get("limit").getAsInt() : 20;
                    JsonObject response = ok();
                    JsonArray lines = new JsonArray();
                    if (mc.ingameGUI != null) {
                        try {
                            net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
                            java.lang.reflect.Field f = findField(chat.getClass(), "chatLines");
                            f.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            List<net.minecraft.client.gui.ChatLine> raw =
                                    (List<net.minecraft.client.gui.ChatLine>) f.get(chat);
                            for (int i = 0; i < raw.size() && i < limit; i++) {
                                lines.add(raw.get(i).getChatComponent().getUnformattedText());
                            }
                            java.lang.reflect.Field overlayF =
                                    findField(mc.ingameGUI.getClass(), "overlayMessage");
                            overlayF.setAccessible(true);
                            response.addProperty("overlay",
                                    String.valueOf(overlayF.get(mc.ingameGUI)));
                            java.lang.reflect.Field overlayTimeF =
                                    findField(mc.ingameGUI.getClass(), "overlayMessageTime");
                            overlayTimeF.setAccessible(true);
                            response.addProperty("overlayTicks", overlayTimeF.getInt(mc.ingameGUI));
                        } catch (Throwable t) {
                            throw new IllegalStateException("report_chat failed: " + t, t);
                        }
                    }
                    response.add("lines", lines);
                    response.addProperty("count", lines.size());
                    return response;
                });
            case "report_player_items":
                // Client-side view of the player's held/offhand/armor/main
                // inventory stacks (id, count, NBT string). This is the synced
                // state the HUD and inventory screen render from — the honest
                // layer for "the suit's air tank drained" style assertions.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.player == null) {
                        response.addProperty("worldReady", false);
                        return response;
                    }
                    response.addProperty("worldReady", true);
                    response.add("held", stackJson(mc.player.getHeldItemMainhand()));
                    response.add("offhand", stackJson(mc.player.getHeldItemOffhand()));
                    JsonArray armor = new JsonArray();
                    for (ItemStack stack : mc.player.inventory.armorInventory) {
                        armor.add(stackJson(stack)); // index 0=feet … 3=head
                    }
                    response.add("armor", armor);
                    JsonArray main = new JsonArray();
                    for (ItemStack stack : mc.player.inventory.mainInventory) {
                        main.add(stackJson(stack));
                    }
                    response.add("main", main);
                    return response;
                });
            case "report_entities":
                // Entities in the CLIENT world near the player, optionally
                // filtered by a class-name substring. Pins "the client actually
                // sees the spawned/tracked entity", which no server query can.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null || mc.world == null) {
                        throw new IllegalStateException("report_entities: client world/player not ready");
                    }
                    double radius = request.has("radius") ? request.get("radius").getAsDouble() : 64.0D;
                    String needle = request.has("classContains")
                            ? requireString(request, "classContains") : "";
                    JsonObject response = ok();
                    JsonArray entities = new JsonArray();
                    for (net.minecraft.entity.Entity entity : mc.world.loadedEntityList) {
                        if (entity == mc.player) continue;
                        if (!needle.isEmpty() && !entity.getClass().getName().contains(needle)) continue;
                        if (mc.player.getDistance(entity) > radius) continue;
                        JsonObject je = new JsonObject();
                        je.addProperty("class", entity.getClass().getName());
                        je.addProperty("id", entity.getEntityId());
                        je.addProperty("x", entity.posX);
                        je.addProperty("y", entity.posY);
                        je.addProperty("z", entity.posZ);
                        entities.add(je);
                    }
                    response.add("entities", entities);
                    response.addProperty("count", entities.size());
                    return response;
                });
            case "report_mouse_over":
                // What the client's own crosshair is pointing at: mc.objectMouseOver,
                // refreshed every tick by Minecraft.runTick (entityRenderer.getMouseOver),
                // which is the SAME field vanilla's rightClickMouse() reads to decide what a
                // right-click hits. Without it, "the bot aimed at nothing" and "the click was
                // aimed correctly but refused" are indistinguishable — so a red key-press test
                // cannot say which hop failed. Read-only.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    net.minecraft.util.math.RayTraceResult hit = mc.objectMouseOver;
                    response.addProperty("present", hit != null);
                    response.addProperty("typeOfHit", hit == null ? "" : hit.typeOfHit.name());
                    if (hit != null) {
                        BlockPos hitPos = hit.getBlockPos();
                        response.addProperty("hasBlockPos", hitPos != null);
                        if (hitPos != null) {
                            response.addProperty("blockX", hitPos.getX());
                            response.addProperty("blockY", hitPos.getY());
                            response.addProperty("blockZ", hitPos.getZ());
                            // The block AT that position in the CLIENT world. On a physics-mod
                            // ship the raytrace reports the ship's own (subspace) position, so
                            // this is what tells a test whether the pos it got back names the
                            // block it meant to aim at.
                            response.addProperty("block", mc.world == null ? ""
                                    : String.valueOf(mc.world.getBlockState(hitPos)
                                            .getBlock().getRegistryName()));
                        }
                        response.addProperty("sideHit",
                                hit.sideHit == null ? "" : hit.sideHit.name());
                        if (hit.hitVec != null) {
                            response.addProperty("hitX", hit.hitVec.x);
                            response.addProperty("hitY", hit.hitVec.y);
                            response.addProperty("hitZ", hit.hitVec.z);
                        }
                        response.addProperty("entityClass", hit.entityHit == null ? ""
                                : hit.entityHit.getClass().getName());
                        response.addProperty("entityId",
                                hit.entityHit == null ? -1 : hit.entityHit.getEntityId());
                    }
                    return response;
                });
            case "interact_block":
                // Real right-click: PlayerControllerMP.processRightClickBlock
                // sends CPacketPlayerTryUseItemOnBlock, so the server's
                // interaction path (reach checks, Block.onBlockActivated, bed
                // trySleep, ...) runs against the real player.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null || mc.world == null) {
                        throw new IllegalStateException("interact_block: client world/player not ready");
                    }
                    BlockPos pos = new BlockPos(requireInt(request, "x"),
                            requireInt(request, "y"), requireInt(request, "z"));
                    Vec3d hit = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    net.minecraft.util.EnumActionResult result = mc.playerController
                            .processRightClickBlock(mc.player, mc.world, pos,
                                    EnumFacing.UP, hit, EnumHand.MAIN_HAND);
                    JsonObject response = ok();
                    response.addProperty("result", result.name());
                    return response;
                });
            case "report_mods":
                // The two counts the vanilla main menu shows ("N mods loaded,
                // M mods active" — FMLCommonHandler.getBrandings reads exactly
                // these lists), plus the loaded modids. A loaded-but-never-
                // active container shows up here as a count mismatch.
                return runOnClientThread(() -> {
                    JsonObject response = ok();
                    List<net.minecraftforge.fml.common.ModContainer> loaded =
                            net.minecraftforge.fml.common.Loader.instance().getModList();
                    List<net.minecraftforge.fml.common.ModContainer> active =
                            net.minecraftforge.fml.common.Loader.instance().getActiveModList();
                    response.addProperty("loadedCount", loaded.size());
                    response.addProperty("activeCount", active.size());
                    JsonArray ids = new JsonArray();
                    for (net.minecraftforge.fml.common.ModContainer mod : loaded) {
                        ids.add(mod.getModId());
                    }
                    response.add("loadedModIds", ids);
                    return response;
                });
            case "send_chat":
                // One chat line exactly as typed by the player (commands
                // included): EntityPlayerSP.sendChatMessage → CPacketChatMessage,
                // so the server sees a real player sender — its world,
                // permissions and CommandEvent hooks follow the production
                // path, unlike console-driven commands.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.player == null) {
                        throw new IllegalStateException("send_chat: client player not in world yet");
                    }
                    mc.player.sendChatMessage(requireString(request, "message"));
                    return ok();
                });
            case "report_weather":
                // Client-side view of vanilla weather state for whatever
                // dimension the client is currently in. Reports what the
                // PLAYER is seeing — different from a server-side query
                // because vanilla syncs weather via SPacketChangeGameState
                // (begin/end raining + strength edges), so this is the
                // canonical way to assert that those packets reached the
                // rendered frame after a server-side weather change or a
                // cross-dimension teleport.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.world == null) {
                        response.addProperty("worldReady", false);
                        return response;
                    }
                    response.addProperty("worldReady", true);
                    response.addProperty("dim", mc.world.provider.getDimension());
                    response.addProperty("worldInfoClass", mc.world.getWorldInfo().getClass().getName());
                    response.addProperty("isRaining", mc.world.getWorldInfo().isRaining());
                    response.addProperty("isThundering", mc.world.getWorldInfo().isThundering());
                    response.addProperty("rainTime", mc.world.getWorldInfo().getRainTime());
                    response.addProperty("thunderTime", mc.world.getWorldInfo().getThunderTime());
                    response.addProperty("rainStrength", mc.world.getRainStrength(1.0f));
                    response.addProperty("thunderStrength", mc.world.getThunderStrength(1.0f));
                    return response;
                });
            case "report_spawn":
                // Client-side view of the spawn point for the dim the client is
                // currently in — precisely the two fields
                // NetHandlerPlayClient.handleSpawnPosition writes when
                // SPacketSpawnPosition arrives:
                //     mc.player.setSpawnPoint(pos, true)
                //     mc.world.getWorldInfo().setSpawn(pos)
                // WorldClient seeds worldInfo's spawn to the placeholder
                // (8,64,8) in its constructor and is rebuilt from scratch on
                // every dimension change, so these values are the canonical
                // observation for "did the spawn-position packet reach the
                // client for the dim it is in". No server-side query can see
                // this: the server's own state is identical whether or not the
                // packet was sent.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    JsonObject response = ok();
                    if (mc.world == null) {
                        response.addProperty("worldReady", false);
                        return response;
                    }
                    response.addProperty("worldReady", true);
                    response.addProperty("dim", mc.world.provider.getDimension());
                    response.addProperty("worldInfoClass", mc.world.getWorldInfo().getClass().getName());
                    response.addProperty("spawnX", mc.world.getWorldInfo().getSpawnX());
                    response.addProperty("spawnY", mc.world.getWorldInfo().getSpawnY());
                    response.addProperty("spawnZ", mc.world.getWorldInfo().getSpawnZ());
                    // World.getSpawnPoint() adds the provider indirection and a
                    // world-border clamp — reporting it too makes a
                    // provider-level divergence visible instead of silent, and
                    // it is the exact expression the SERVER packs into the
                    // packet.
                    BlockPos worldSpawn = mc.world.getSpawnPoint();
                    response.addProperty("worldSpawnX", worldSpawn.getX());
                    response.addProperty("worldSpawnY", worldSpawn.getY());
                    response.addProperty("worldSpawnZ", worldSpawn.getZ());
                    if (mc.player == null) {
                        response.addProperty("playerReady", false);
                        return response;
                    }
                    response.addProperty("playerReady", true);
                    // getBedLocation() delegates to getBedLocation(this.dimension),
                    // which reads spawnPos for dim 0 and spawnChunkMap otherwise —
                    // mirroring the branch setSpawnPoint(pos, forced) takes. So
                    // this is dimension-correct on AR planets too. null means the
                    // packet never arrived.
                    BlockPos bed = mc.player.getBedLocation();
                    response.addProperty("hasBedLocation", bed != null);
                    if (bed != null) {
                        response.addProperty("bedX", bed.getX());
                        response.addProperty("bedY", bed.getY());
                        response.addProperty("bedZ", bed.getZ());
                    }
                    return response;
                });
            case "report_sounds": {
                // Sound locations the client SoundManager was asked to play
                // since the last clear_sounds — PlaySoundEvent fires per
                // playSound(ISound) on the real client. NOTE: the event fires
                // BEFORE asset resolution, so this observes the play REQUEST
                // reaching the SoundManager, not asset existence / audibility.
                // Includes vanilla ambience/music; the caller filters.
                // managerLoaded=false means the sound system never initialised
                // (no audio device) and NOTHING will ever be recorded — callers
                // should Assume on it instead of misdiagnosing.
                JsonObject response = ok();
                JsonArray sounds = new JsonArray();
                synchronized (SOUND_LOG_LOCK) {
                    for (String sound : PLAYED_SOUNDS) {
                        sounds.add(sound);
                    }
                }
                response.add("sounds", sounds);
                response.addProperty("total", SOUNDS_TOTAL.get());
                boolean managerLoaded = false;
                try {
                    Object handler = Minecraft.getMinecraft().getSoundHandler();
                    java.lang.reflect.Field sndManager = findField(handler.getClass(), "sndManager");
                    sndManager.setAccessible(true);
                    Object manager = sndManager.get(handler);
                    java.lang.reflect.Field loaded = findField(manager.getClass(), "loaded");
                    loaded.setAccessible(true);
                    managerLoaded = loaded.getBoolean(manager);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    response.addProperty("managerLoadedError", String.valueOf(e));
                }
                response.addProperty("managerLoaded", managerLoaded);
                return response;
            }
            case "clear_sounds":
                // Reset the played-sound log (see report_sounds) so a test can
                // scope its assertion to sounds triggered after this point.
                synchronized (SOUND_LOG_LOCK) {
                    PLAYED_SOUNDS.clear();
                }
                return ok();
            case "block_state":
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    BlockPos pos = new BlockPos(requireInt(request, "x"), requireInt(request, "y"), requireInt(request, "z"));
                    JsonObject response = ok();
                    if (mc.world == null) {
                        response.addProperty("block", "");
                        response.addProperty("tile", "");
                        response.addProperty("loaded", false);
                        return response;
                    }
                    response.addProperty("loaded", mc.world.isBlockLoaded(pos));
                    if (mc.world.isBlockLoaded(pos)) {
                        response.addProperty("block", String.valueOf(mc.world.getBlockState(pos).getBlock().getRegistryName()));
                        response.addProperty("tile", mc.world.getTileEntity(pos) == null
                                ? ""
                                : mc.world.getTileEntity(pos).getClass().getName());
                    } else {
                        response.addProperty("block", "");
                        response.addProperty("tile", "");
                    }
                    return response;
                });
            case "tile_nbt":
                // What the CLIENT's own tile entity holds, as the tile itself describes it.
                // block_state answers which class is there; this answers what that instance believes,
                // which is the only honest way to ask whether a server-side change reached the client
                // — a renderer's output is not readable from here, but the state it draws from is.
                // Reads getUpdateTag() rather than writeToNBT(): the update tag is exactly the subset
                // a tile chooses to replicate, so a test that reads it is asking about the wire and
                // not about the tile's private bookkeeping.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    BlockPos pos = new BlockPos(requireInt(request, "x"), requireInt(request, "y"),
                            requireInt(request, "z"));
                    JsonObject response = ok();
                    net.minecraft.tileentity.TileEntity tile =
                            mc.world == null ? null : mc.world.getTileEntity(pos);
                    response.addProperty("present", tile != null);
                    response.addProperty("tile", tile == null ? "" : tile.getClass().getName());
                    response.addProperty("nbt", tile == null ? "" : tile.getUpdateTag().toString());
                    return response;
                });
            case "invoke_static_int":
                // Drive a mod's own CLIENT-side input entry point on the client thread. The sibling of
                // set_key: that one writes KeyBinding state rather than feeding the LWJGL key queue,
                // and this one calls the method the mouse handler calls rather than feeding the LWJGL
                // mouse queue. Both run the real client code; neither invents the outcome.
                return runOnClientThread(() -> {
                    String className = requireString(request, "className");
                    String methodName = requireString(request, "methodName");
                    JsonArray args = request.has("intArgs")
                            ? request.getAsJsonArray("intArgs") : new JsonArray();
                    Class<?>[] types = new Class<?>[args.size()];
                    Object[] values = new Object[args.size()];
                    for (int i = 0; i < args.size(); i++) {
                        types[i] = int.class;
                        values[i] = args.get(i).getAsInt();
                    }
                    JsonObject response = ok();
                    try {
                        Class<?> clazz = Class.forName(className);
                        java.lang.reflect.Method method = clazz.getDeclaredMethod(methodName, types);
                        method.setAccessible(true);
                        Object result = method.invoke(null, values);
                        response.addProperty("returned", result == null ? "" : String.valueOf(result));
                    } catch (Throwable t) {
                        throw new IllegalStateException("invoke_static_int(" + className + "#"
                                + methodName + ") failed: " + t, t);
                    }
                    return response;
                });
            case "set_framebuffer":
                // A capture needs the framebuffer object: without it the last frame lives only in a back
                // buffer whose contents are undefined after the swap, and the image comes out flat. The
                // harness leaves the FBO off by default (vendor GL drivers), so a test that wants to SEE
                // the frame turns it on for itself, renders a few, and turns it back off - rather than
                // every other test paying for a render path it never looks at.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    boolean enabled = request.get("enabled").getAsBoolean();
                    boolean previous = mc.gameSettings.fboEnable;
                    mc.gameSettings.fboEnable = enabled;
                    // The framebuffer object is allocated at init only if it was enabled THEN; flipping
                    // the setting now does not retroactively create the GL texture the render loop draws
                    // into, so a capture would read an empty one. Allocate it here so the next frames
                    // actually render into it. Guarded on driver support.
                    if (enabled && OpenGlHelper.framebufferSupported && mc.getFramebuffer() != null) {
                        mc.getFramebuffer().createBindFramebuffer(mc.displayWidth, mc.displayHeight);
                    }
                    JsonObject response = ok();
                    response.addProperty("previous", previous);
                    response.addProperty("enabled", enabled);
                    response.addProperty("supported", OpenGlHelper.framebufferSupported);
                    return response;
                });
            case "set_render_distance":
                // The sky pass is GATED on the render distance: EntityRenderer.renderWorldPass only
                // calls RenderGlobal.renderSky (and therefore a dimension's custom IRenderHandler) when
                // gameSettings.renderDistanceChunks >= 4. The harness pins it at 2 for speed, so a sky
                // renderer never runs here by default and a screenshot of it would be honestly empty for
                // the wrong reason. A test that means to LOOK at the sky raises it for itself and puts it
                // back, exactly as set_framebuffer does - the render path every other test runs is
                // unchanged. The distance also sets the sky projection's far plane (farPlaneDistance * 2),
                // so it must clear whatever radius the sky geometry is drawn at.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    int chunks = request.get("chunks").getAsInt();
                    int previous = mc.gameSettings.renderDistanceChunks;
                    mc.gameSettings.renderDistanceChunks = chunks;
                    // What vanilla's own GameSettings.setOptionFloatValue does for RENDER_DISTANCE.
                    if (mc.renderGlobal != null) {
                        mc.renderGlobal.setDisplayListEntitiesDirty();
                    }
                    JsonObject response = ok();
                    response.addProperty("previous", previous);
                    response.addProperty("chunks", chunks);
                    // The gate the sky pass itself tests, reported so a caller never has to re-derive it.
                    // Read the field BACK rather than echoing the argument: this is the field the sky
                    // gate tests, and a caller that only sees its own request reflected has learned
                    // nothing about whether the write stuck.
                    response.addProperty("renderDistance", mc.gameSettings.renderDistanceChunks);
                    response.addProperty("skyPassEnabled", mc.gameSettings.renderDistanceChunks >= 4);
                    return response;
                });
            case "set_frame_rate":
                // The harness seeds options.txt with maxFps:30 (RealClientHarness), which is right for
                // every test that only needs the client to RUN. It is wrong for a test that measures the
                // rendered frame as a CLOCK: a stutter a player sees at 120 fps is averaged away by a
                // 30 fps sampler, so such a test would read a clean number off an instrument that cannot
                // resolve the thing it is looking for. This raises the cap for the test that needs it and
                // puts it back, exactly as set_render_distance does for the sky.
                //
                // Vanilla applies the cap in Minecraft.runGameLoop via Display.sync(getLimitFramerate()),
                // and getLimitFramerate() returns gameSettings.limitFramerate whenever a world is loaded
                // - so this takes effect on the next frame with no other call needed. Vsync is reported
                // because it overrides the cap at the driver: a caller that raised the limit and still
                // sees 60 has learned why here rather than by guessing.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    int fps = request.get("fps").getAsInt();
                    int previous = mc.gameSettings.limitFramerate;
                    mc.gameSettings.limitFramerate = fps;
                    JsonObject response = ok();
                    response.addProperty("previous", previous);
                    // Read BACK rather than echo: the caller learns whether the write stuck, and
                    // getLimitFramerate() is the value vanilla actually syncs to, which is not the
                    // setting itself when no world is loaded.
                    response.addProperty("limitFramerate", mc.gameSettings.limitFramerate);
                    response.addProperty("effectiveLimit", mc.getLimitFramerate());
                    response.addProperty("capped", mc.isFramerateLimitBelowMax());
                    response.addProperty("vsync", mc.gameSettings.enableVsync);
                    return response;
                });
            case "set_hud_hidden":
                // F1. A test that MEASURES the rendered world has to get the HUD out of the frame first:
                // the chat overlay carries this harness's own per-command completion markers, which scroll
                // through the middle of the screen and CHANGE between two captures, and the crosshair
                // inverts to white over a dark background - both are differences the test did not cause.
                // Toasts are drawn OUTSIDE vanilla's hideGUI gate (Minecraft.runGameLoop calls
                // drawToast after updateCameraAndRender), so hiding also drains the toast queue; call this
                // immediately before each capture, since a new toast can arrive at any tick.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    boolean hidden = request.get("hidden").getAsBoolean();
                    boolean previous = mc.gameSettings.hideGUI;
                    mc.gameSettings.hideGUI = hidden;
                    if (hidden && mc.getToastGui() != null) {
                        mc.getToastGui().clear();
                    }
                    JsonObject response = ok();
                    response.addProperty("previous", previous);
                    response.addProperty("hidden", hidden);
                    return response;
                });
            case "screenshot":
                // The only way a headless test can see what the client actually DREW. Vanilla's F2
                // cannot be driven: it is dispatched off the raw LWJGL key-event queue, which
                // set_key (a KeyBinding state write) never reaches. So call the same helper directly,
                // on the client thread, where the GL context is current.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    String name = requireString(request, "name");
                    String fileName = name.endsWith(".png") ? name : name + ".png";
                    // Without the FBO, ScreenShotHelper falls back to glReadPixels of the current READ
                    // buffer. Read the FRONT buffer, which at least holds the frame on screen; the
                    // caller should have enabled the framebuffer if it means to trust the pixels.
                    boolean fbo = OpenGlHelper.isFramebufferEnabled();
                    int previousReadBuffer = fbo ? 0 : GL11.glGetInteger(GL11.GL_READ_BUFFER);
                    if (!fbo) {
                        GL11.glReadBuffer(GL11.GL_FRONT);
                    }
                    try {
                        ScreenShotHelper.saveScreenshot(mc.mcDataDir, fileName,
                                mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                    } finally {
                        if (!fbo) {
                            GL11.glReadBuffer(previousReadBuffer);
                        }
                    }
                    File written = new File(new File(mc.mcDataDir, "screenshots"), fileName);
                    JsonObject response = ok();
                    response.addProperty("path", written.getAbsolutePath());
                    response.addProperty("exists", written.isFile());
                    response.addProperty("bytes", written.isFile() ? written.length() : 0L);
                    response.addProperty("width", mc.displayWidth);
                    response.addProperty("height", mc.displayHeight);
                    response.addProperty("framebuffer", fbo);
                    return response;
                });
            case "shutdown":
                return runOnClientThread(() -> {
                    Minecraft.getMinecraft().shutdown();
                    return ok();
                });
            case "tile_modules_throws":
                // Invoke a tile's libVulpes getModules(int, EntityPlayer) on the CLIENT
                // and report whether it threw. Lets a client e2e pin a GUI-build crash
                // (getModules runs client-side inside the modular-GUI open path) without
                // needing the full multiblock assembled — the exact production method is
                // driven on real client world/tile state.
                return runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.world == null) {
                        return error("no client world");
                    }
                    BlockPos pos = new BlockPos(requireInt(request, "x"),
                            requireInt(request, "y"), requireInt(request, "z"));
                    net.minecraft.tileentity.TileEntity tile = mc.world.getTileEntity(pos);
                    if (tile == null) {
                        return error("no tile at pos");
                    }
                    JsonObject response = ok();
                    response.addProperty("tile", tile.getClass().getName());
                    java.lang.reflect.Method method;
                    try {
                        method = tile.getClass().getMethod("getModules",
                                int.class, net.minecraft.entity.player.EntityPlayer.class);
                    } catch (NoSuchMethodException noSuchMethod) {
                        return error("tile has no getModules(int, EntityPlayer): "
                                + tile.getClass().getName());
                    }
                    try {
                        method.setAccessible(true);
                        method.invoke(tile, 0, mc.player);
                        response.addProperty("threw", false);
                    } catch (java.lang.reflect.InvocationTargetException invocationTarget) {
                        response.addProperty("threw", true);
                        response.addProperty("error", String.valueOf(invocationTarget.getTargetException()));
                    } catch (Throwable other) {
                        response.addProperty("threw", true);
                        response.addProperty("error", String.valueOf(other));
                    }
                    return response;
                });
            case "invoke_static_chain":
                // Evaluate a no-arg reflective chain on the CLIENT: the first method is
                // static on {@code class}, each subsequent method is called on the prior
                // result. Reports the final value's toString and, when it is an
                // array/Collection/Map, its size. Framework-agnostic client-state probe.
                return runOnClientThread(() -> {
                    String className = request.get("class").getAsString();
                    String[] methods = request.get("methods").getAsString().split(",");
                    JsonObject response = ok();
                    try {
                        Class<?> cls = Class.forName(className);
                        Object target = null;
                        for (int i = 0; i < methods.length; i++) {
                            String name = methods[i].trim();
                            java.lang.reflect.Method method = (i == 0)
                                    ? cls.getMethod(name)
                                    : target.getClass().getMethod(name);
                            method.setAccessible(true);
                            target = method.invoke(target);
                        }
                        if (target != null && target.getClass().isArray()) {
                            response.addProperty("size", java.lang.reflect.Array.getLength(target));
                        } else if (target instanceof java.util.Collection) {
                            response.addProperty("size", ((java.util.Collection<?>) target).size());
                        } else if (target instanceof java.util.Map) {
                            response.addProperty("size", ((java.util.Map<?, ?>) target).size());
                        }
                        response.addProperty("result", String.valueOf(target));
                    } catch (Throwable t) {
                        return error("invoke_static_chain failed: " + t);
                    }
                    return response;
                });
            default:
                return error("Unknown command: " + command);
        }
    }

    private static JsonObject waitTicks(JsonObject request) {
        int ticks = boundedInt(request, "ticks", 0, 1000000);
        long start = CLIENT_TICKS.get();
        // Load-scaled: under concurrent forks the effective client tick rate drops and a fixed
        // wall-clock ceiling would turn a tick-counted wait into a spurious timeout.
        long deadline = System.nanoTime() + com.github.stannismod.forge.testing.TestTimeouts
                .scaledNanos(TimeUnit.MINUTES.toNanos(2));

        while (CLIENT_TICKS.get() - start < ticks) {
            if (System.nanoTime() > deadline) {
                return error("Timed out waiting for " + ticks + " client ticks");
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return error("Interrupted while waiting for ticks");
            }
        }
        return ok();
    }

    private static void waitForWorld() {
        long deadline = System.nanoTime() + com.github.stannismod.forge.testing.TestTimeouts
                .scaledNanos(TimeUnit.MINUTES.toNanos(2));
        while (System.nanoTime() < deadline) {
            try {
                Boolean ready = runOnClientThread(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    return mc.world != null && mc.player != null && mc.player.connection != null;
                });
                if (Boolean.TRUE.equals(ready)) {
                    return;
                }
                Thread.sleep(100L);
            } catch (RuntimeException exception) {
                throw exception;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the client world to load", interruptedException);
            }
        }
        throw new IllegalStateException("Timed out waiting for the client world to load");
    }

    private static <T> T runOnClientThread(Callable<T> callable) {
        Minecraft mc = Minecraft.getMinecraft();
        FutureTask<T> task = new FutureTask<>(callable);
        mc.addScheduledTask(task);
        try {
            return task.get(com.github.stannismod.forge.testing.TestTimeouts
                    .scaledMillis(TimeUnit.MINUTES.toMillis(2)), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static EntityPlayerSP requirePlayer(Minecraft mc) {
        if (mc.player == null) {
            throw new IllegalStateException("Client player is not available");
        }
        return mc.player;
    }

    /** {id, count, nbt} of a client-side ItemStack; empty stacks → id="" count=0. */
    private static JsonObject stackJson(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            json.addProperty("id", "");
            json.addProperty("count", 0);
            json.addProperty("nbt", "");
            return json;
        }
        json.addProperty("id", String.valueOf(stack.getItem().getRegistryName()));
        json.addProperty("count", stack.getCount());
        json.addProperty("nbt", stack.getTagCompound() == null ? "" : stack.getTagCompound().toString());
        return json;
    }

    private static JsonObject ok() {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        return response;
    }

    private static JsonObject error(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message == null ? "unknown" : message);
        return response;
    }

    private static int requireInt(JsonObject object, String key) {
        if (!object.has(key)) {
            throw new IllegalArgumentException("Missing required key: " + key);
        }
        return object.get(key).getAsInt();
    }

    private static int boundedInt(JsonObject object, String key, int min, int max) {
        int value = requireInt(object, key);
        return Math.max(min, Math.min(max, value));
    }

    private static String requireString(JsonObject object, String key) {
        if (!object.has(key)) {
            throw new IllegalArgumentException("Missing required key: " + key);
        }
        return object.get(key).getAsString();
    }

    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonList(GuiScreen screen) {
        try {
            java.lang.reflect.Field field = GuiScreen.class.getDeclaredField("buttonList");
            field.setAccessible(true);
            return (List<GuiButton>) field.get(screen);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to access GUI button list", exception);
        }
    }

    /**
     * Every {@link GuiButton} reachable from {@code screen}: the standard
     * {@code GuiScreen.buttonList}, plus — for libVulpes-style modular GUIs —
     * any per-module button lists. libVulpes {@code GuiModular} keeps its
     * sub-modules in a {@code modules} field, and container modules
     * ({@code ModuleContainerPan}, the planet-selector grid) keep their buttons
     * in their own {@code buttonList}/{@code staticButtonList} fields that never
     * reach {@code GuiScreen.buttonList}. Discovered purely reflectively, so the
     * framework keeps no compile dependency on libVulpes.
     */
    private static List<GuiButton> collectAllButtons(GuiScreen screen) {
        List<GuiButton> all = new ArrayList<>(buttonList(screen));
        Object modules = readFieldOrNull(screen, "modules");
        if (modules instanceof List) {
            for (Object module : (List<?>) modules) {
                collectModuleButtons(module, all);
            }
        }
        return all;
    }

    private static void collectModuleButtons(Object module, List<GuiButton> out) {
        if (module == null) {
            return;
        }
        for (String fieldName : new String[] {"buttonList", "staticButtonList"}) {
            Object value = readFieldOrNull(module, fieldName);
            if (value instanceof List) {
                for (Object element : (List<?>) value) {
                    if (element instanceof GuiButton) {
                        out.add((GuiButton) element);
                    }
                }
            }
        }
    }

    private static Object readFieldOrNull(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Dispatches a button through the screen's {@code actionPerformed} — the
     * same entry point MC invokes on a real click. libVulpes {@code GuiModular}
     * forwards it to every module, so module-local buttons are handled too.
     */
    private static void invokeActionPerformed(GuiScreen screen, GuiButton button) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "actionPerformed", GuiButton.class);
            method.setAccessible(true);
            method.invoke(screen, button);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to dispatch GUI button action", exception);
        }
    }

    private static void invokeHandleMouseClick(GuiContainer screen, Slot slot, int slotId, int mouseButton, ClickType type) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "handleMouseClick",
                    Slot.class, int.class, int.class, ClickType.class);
            method.setAccessible(true);
            method.invoke(screen, slot, slotId, mouseButton, type);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to click container slot", exception);
        }
    }

    private static void invokeMouseClicked(GuiScreen screen, int x, int y, int button) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "mouseClicked", int.class, int.class, int.class);
            method.setAccessible(true);
            method.invoke(screen, x, y, button);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to click GUI point", exception);
        }
    }

    private static void invokeKeyTyped(GuiScreen screen, char typedChar, int keyCode) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "keyTyped", char.class, int.class);
            method.setAccessible(true);
            method.invoke(screen, typedChar, keyCode);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to type into GUI", exception);
        }
    }

    private static void invokeMouseClickMove(GuiScreen screen, int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "mouseClickMove", int.class, int.class, int.class, long.class);
            method.setAccessible(true);
            method.invoke(screen, mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to drag GUI point", exception);
        }
    }

    private static void invokeMouseReleased(GuiScreen screen, int mouseX, int mouseY, int state) {
        try {
            java.lang.reflect.Method method = findMethod(screen.getClass(), "mouseReleased", int.class, int.class, int.class);
            method.setAccessible(true);
            method.invoke(screen, mouseX, mouseY, state);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to release GUI point", exception);
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> type, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private static GuiTextField textField(GuiScreen screen, String fieldName) {
        try {
            java.lang.reflect.Field field = screen.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(screen);
            if (!(value instanceof GuiTextField)) {
                throw new IllegalStateException("Field '" + fieldName + "' is not a GuiTextField");
            }
            return (GuiTextField) value;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to access GUI text field '" + fieldName + "'", exception);
        }
    }

    private static int intField(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to access integer field '" + fieldName + "' on " + target.getClass().getName(), exception);
        }
    }

    /**
     * Wipes the chat backlog and the action-bar overlay, and reports what was found there.
     *
     * <p>Shared by {@code reset_client_state} (which also closes the screen and releases keys)
     * and {@code clear_chat} (which does not). The chat backlog is the dangerous channel in a
     * shared harness: an assertion of the form "the player was told X" searches the last N
     * lines, so a previous scenario's identical line — or one of the harness's own
     * {@code FORGE_TEST_DONE} markers — satisfies it with no stimulus behind it at all.</p>
     *
     * <p>{@code overlayMessageTime} is the real gate for the action bar: the overlay STRING
     * lingers after expiry, so only the countdown says "still on screen".</p>
     */
    private static void clearChatAndOverlay(Minecraft mc, JsonObject response) {
        int clearedLines = 0;
        String clearedOverlay = "";
        int clearedOverlayTicks = 0;
        if (mc.ingameGUI != null) {
            try {
                net.minecraft.client.gui.GuiNewChat chat = mc.ingameGUI.getChatGUI();
                java.lang.reflect.Field linesF = findField(chat.getClass(), "chatLines");
                linesF.setAccessible(true);
                clearedLines = ((List<?>) linesF.get(chat)).size();
                chat.clearChatMessages(true);

                java.lang.reflect.Field overlayF =
                        findField(mc.ingameGUI.getClass(), "overlayMessage");
                overlayF.setAccessible(true);
                clearedOverlay = String.valueOf(overlayF.get(mc.ingameGUI));
                overlayF.set(mc.ingameGUI, "");

                java.lang.reflect.Field overlayTimeF =
                        findField(mc.ingameGUI.getClass(), "overlayMessageTime");
                overlayTimeF.setAccessible(true);
                clearedOverlayTicks = overlayTimeF.getInt(mc.ingameGUI);
                overlayTimeF.setInt(mc.ingameGUI, 0);
            } catch (Throwable t) {
                throw new IllegalStateException("clear chat/overlay: " + t, t);
            }
        }
        response.addProperty("clearedChatLines", clearedLines);
        response.addProperty("clearedOverlay", clearedOverlay);
        response.addProperty("clearedOverlayTicks", clearedOverlayTicks);
    }

    private static java.lang.reflect.Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static final class SoundRecorder {
        @SubscribeEvent
        public void onPlaySound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
            net.minecraft.client.audio.ISound sound = event.getSound();
            if (sound == null || sound.getSoundLocation() == null) {
                return;
            }
            String location = sound.getSoundLocation().toString();
            synchronized (SOUND_LOG_LOCK) {
                PLAYED_SOUNDS.addLast(location);
                while (PLAYED_SOUNDS.size() > PLAYED_SOUNDS_CAP) {
                    PLAYED_SOUNDS.removeFirst();
                }
                SOUNDS_TOTAL.incrementAndGet();
            }
        }
    }

    private static final class TickCounter {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                if (CLIENT_TICKS.get() == 0L) {
                    // First END-phase tick: Display.create() has returned and
                    // the LWJGL window is up. Honour the start-state override.
                    applyInitialWindowState();
                    installNonWarpingMouseHelper();
                }
                CLIENT_TICKS.incrementAndGet();
                // Deferred connection teardown: this event runs on the client thread OUTSIDE
                // the scheduled-task drain, so closing the channel here cannot deadlock
                // against an inbound packet handler (see PENDING_CONNECTION_ACTION).
                Runnable action = PENDING_CONNECTION_ACTION.getAndSet(null);
                if (action != null) {
                    action.run();
                }
            }
        }
    }

    private static final AtomicBoolean MOUSE_HELPER_INSTALLED = new AtomicBoolean(false);

    /**
     * Stop the test client from ever moving the developer's OS cursor.
     *
     * <p>Vanilla warps the physical pointer on BOTH transitions, and this harness runs its window
     * parked at {@code -32000,-32000}, so both warps land off the desktop and Windows clamps the
     * cursor to a screen edge — the machine's owner is typing in another window and their pointer
     * jumps into the corner:</p>
     *
     * <ul>
     *   <li>{@code grabMouseCursor()} — {@code Mouse.setGrabbed(true)}, on the client taking
     *       in-game focus. Forge already guards this one behind {@code -Dfml.noGrab=true}, which
     *       {@code RealClientHarness} passes.</li>
     *   <li>{@code ungrabMouseCursor()} — {@code Mouse.setCursorPosition(width/2, height/2)}, on
     *       every loss of focus. <b>Nothing guards this one</b>, and losing focus is exactly what
     *       happens the moment the developer clicks somewhere else, so it fires repeatedly.</li>
     * </ul>
     *
     * <p>Replacing the helper closes both halves at their source and keeps the grab-independent
     * part ({@code mouseXYChange}) working. The harness needs no OS cursor at all: look is driven
     * through {@code setLook} and raw mouse deltas go straight to the client's own static entry
     * points, neither of which reads the pointer.</p>
     */
    private static void installNonWarpingMouseHelper() {
        if (!MOUSE_HELPER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        // The escape hatch, and the only way to observe the behaviour this method removes: set
        // -Dforge.test.client.cursor.warp=true (with -Dfml.noGrab=false) and the client warps the
        // desktop cursor exactly like vanilla. That is the control leg for "the fix does anything"
        // — it will physically take the pointer, so it is opt-in and never the default.
        if (Boolean.parseBoolean(System.getProperty("forge.test.client.cursor.warp", "false"))) {
            System.out.println("[forge-test] cursor warp ENABLED by request — vanilla MouseHelper "
                    + "kept; this client may move the desktop cursor");
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            mc.mouseHelper = new net.minecraft.util.MouseHelper() {
                @Override
                public void grabMouseCursor() {
                    // no-op: never take the pointer away from whoever is using this desktop.
                }

                @Override
                public void ungrabMouseCursor() {
                    // no-op: vanilla warps the pointer to the window centre here, and this window
                    // is off-screen, so the warp reads as "my cursor jumped to the corner".
                }
            };
            System.out.println("[forge-test] installed non-warping MouseHelper "
                    + "(the client will not move the desktop cursor)");
        } catch (Throwable ignored) {
            // Best-effort, exactly like the window state: a client that cannot swap its helper is
            // still a usable client, and failing the run over a cursor would be worse.
        }
    }

    private static final AtomicBoolean WINDOW_STATE_APPLIED = new AtomicBoolean(false);

    /**
     * Minimises the LWJGL client window after Display.create() so tests don't
     * steal focus from concurrent local work. LWJGL2's native createWindow
     * calls {@code ShowWindow(SW_SHOW)} directly, ignoring our
     * {@code STARTUPINFO.wShowWindow} hint — so we have to issue
     * {@code ShowWindow(SW_MINIMIZE)} ourselves once the window exists.
     *
     * <p>Controlled by system property {@code forge.test.client.window.startState}
     * (default {@code minimized}). Set to {@code normal} to keep the window
     * visible. No-op on non-Windows hosts.</p>
     */
    private static void applyInitialWindowState() {
        if (!WINDOW_STATE_APPLIED.compareAndSet(false, true)) {
            return;
        }
        String state = System.getProperty("forge.test.client.window.startState", "minimized")
                .toLowerCase(Locale.ROOT);
        if (!"minimized".equals(state)) {
            return;
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try {
            Class<?> displayClass = Class.forName("org.lwjgl.opengl.Display");
            java.lang.reflect.Method isCreated = displayClass.getMethod("isCreated");
            if (!Boolean.TRUE.equals(isCreated.invoke(null))) {
                return;
            }
            // Move the window completely off-screen first, so the visible
            // "flash" between Display.create() and our minimize call doesn't
            // pop up over the user's other monitors. setLocation(int,int) is
            // public LWJGL2 API and is honoured immediately by the native side.
            try {
                java.lang.reflect.Method setLocation =
                        displayClass.getMethod("setLocation", int.class, int.class);
                setLocation.invoke(null, -32000, -32000);
            } catch (Throwable ignored) {
                // Older/newer LWJGL2 variants — fall back to minimize-only.
            }
            java.lang.reflect.Field implField = displayClass.getDeclaredField("display_impl");
            implField.setAccessible(true);
            Object impl = implField.get(null);
            java.lang.reflect.Method getHwndMethod = impl.getClass().getDeclaredMethod("getHwnd");
            getHwndMethod.setAccessible(true);
            Object hwndObject = getHwndMethod.invoke(impl);
            long hwnd = ((Number) hwndObject).longValue();
            if (hwnd == 0L) {
                return;
            }
            // SW_FORCEMINIMIZE rather than SW_MINIMIZE so the call still works
            // if some future Forge change moves ClientTickEvent off the LWJGL-
            // owning thread (MSDN: "use when minimizing windows from a
            // different thread"). On the same-thread path it behaves identically
            // to SW_MINIMIZE.
            final int SW_FORCEMINIMIZE = 11;
            User32Native.INSTANCE.ShowWindow(new com.sun.jna.Pointer(hwnd), SW_FORCEMINIMIZE);
        } catch (Throwable t) {
            // Best-effort — never break the test run because the cosmetic
            // minimise call failed.
            System.err.println("[forge-test] applyInitialWindowState failed: " + t);
        }
    }

    private interface User32Native extends com.sun.jna.Library {
        User32Native INSTANCE = (User32Native) com.sun.jna.Native.loadLibrary("user32", User32Native.class);

        boolean ShowWindow(com.sun.jna.Pointer hwnd, int nCmdShow);
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        private TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            first.flush();
            second.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                first.close();
            } finally {
                second.close();
            }
        }
    }
}

