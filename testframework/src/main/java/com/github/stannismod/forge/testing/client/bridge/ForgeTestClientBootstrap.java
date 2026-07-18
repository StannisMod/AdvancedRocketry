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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

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
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);

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
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);

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
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
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
            return task.get(2, TimeUnit.MINUTES);
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
                }
                CLIENT_TICKS.incrementAndGet();
            }
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

