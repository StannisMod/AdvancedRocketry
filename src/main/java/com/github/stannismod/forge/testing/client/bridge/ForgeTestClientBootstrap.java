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

    private ForgeTestClientBootstrap() {
    }

    public static void bootstrap() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }

        installClientLogFile();
        FMLCommonHandler.instance().bus().register(new TickCounter());
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
                    JsonObject response = ok();
                    response.addProperty("keyCode", keyCode);
                    response.addProperty("pressed", pressed);
                    return response;
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

