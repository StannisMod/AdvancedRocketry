package com.github.stannismod.forge.testing.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class ClientBot implements Closeable {

    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    ClientBot(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        // Load-scaled: a starved client thread queue stretches every command round-trip.
        this.socket.setSoTimeout(com.github.stannismod.forge.testing.TestTimeouts
                .scaledMillis(Duration.ofMinutes(2).toMillis()));
        this.reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        awaitReady(com.github.stannismod.forge.testing.TestTimeouts.scaled(Duration.ofMinutes(2)));
    }

    public void waitForWorld() throws IOException {
        assertOk(execute(command("wait_world")));
    }

    /**
     * A REAL relog: quits the current server connection and reconnects to the same address,
     * exactly as the player's disconnect + rejoin would. The server performs a full logout
     * (player data saved to disk) and a fresh login; the client rebuilds its world and player
     * entity. Follow with {@link #waitForWorld()} - the reconnect is asynchronous.
     *
     * <p>The address is read off the live connection, so this reconnects to the server the
     * client is attached to RIGHT NOW. It therefore cannot follow a server that has been
     * restarted in between: each harness boot reserves a fresh port, so the old address is
     * dead. To span a restart, run two harness lifecycles against the same workDir and start
     * a second client instead.</p>
     */
    public void reconnect() throws IOException {
        assertOk(execute(command("reconnect")));
    }

    /**
     * The DISCONNECT half of {@link #reconnect()}: quits the current server connection and stays
     * at the main menu. The server performs a full player logout (data saved to disk) and keeps
     * running WITHOUT the player - use this when a test must act on the server while the player
     * is genuinely offline (e.g. someone takes his seat), then {@link #connect()} back. The
     * server address is remembered inside the client for that later connect.
     */
    public void disconnect() throws IOException {
        assertOk(execute(command("disconnect")));
    }

    /**
     * The CONNECT half: rejoins the server a prior {@link #disconnect()} left - the server sees
     * a fresh login and re-reads the player's saved data. Asynchronous like {@link #reconnect()}:
     * follow with {@link #waitForWorld()}. Fails if no disconnect ran before it. Like reconnect,
     * this cannot span a server restart (each harness boot reserves a fresh port).
     */
    public void connect() throws IOException {
        assertOk(execute(command("connect")));
    }

    public void waitTicks(int ticks) throws IOException {
        JsonObject command = command("wait_ticks");
        command.addProperty("ticks", ticks);
        assertOk(execute(command));
    }

    public void selectHotbar(int slot) throws IOException {
        JsonObject command = command("select_hotbar");
        command.addProperty("slot", slot);
        assertOk(execute(command));
    }

    public void rightClickBlock(int x, int y, int z, EnumFacing face, EnumHand hand) throws IOException {
        JsonObject command = command("right_click_block");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        command.addProperty("face", face.name());
        command.addProperty("hand", hand.name());
        assertOk(execute(command));
    }

    public void clickScreenPoint(int x, int y, int button) throws IOException {
        JsonObject command = command("click_screen_point");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("button", button);
        assertOk(execute(command));
    }

    public void clickButton(int index) throws IOException {
        JsonObject command = command("click_button");
        command.addProperty("index", index);
        assertOk(execute(command));
    }

    public void clickButtonAtRatio(int index, double ratio) throws IOException {
        JsonObject command = command("click_button_ratio");
        command.addProperty("index", index);
        command.addProperty("ratio", ratio);
        assertOk(execute(command));
    }

    /**
     * Lists every {@link net.minecraft.client.gui.GuiButton} on the open GUI:
     * each entry carries {@code id}, {@code text}, {@code x}/{@code y}/{@code width}/
     * {@code height}, {@code enabled} and {@code visible}. Use the stable
     * {@code id} (assigned by the mod, not the list position) to drive
     * {@link #clickButtonById(int)}.
     */
    public JsonObject reportButtons() throws IOException {
        return assertOk(execute(command("report_buttons")));
    }

    /**
     * Clicks the GUI button whose {@code GuiButton.id} equals {@code id} —
     * robust against button-list ordering. Fails if no such button exists or it
     * is hidden / disabled.
     */
    public void clickButtonById(int id) throws IOException {
        JsonObject command = command("click_button_id");
        command.addProperty("id", id);
        assertOk(execute(command));
    }

    /**
     * Lists every slot of the open {@link net.minecraft.client.gui.inventory.GuiContainer}:
     * each entry carries {@code slot} (the container slot number), {@code x}/
     * {@code y}, {@code playerSlot} (true for the player-inventory portion),
     * {@code hasStack}, {@code item} (registry name) and {@code count}.
     */
    public JsonObject reportSlots() throws IOException {
        return assertOk(execute(command("report_slots")));
    }

    /**
     * Performs a container slot interaction, mirroring
     * {@code GuiContainer.handleMouseClick}. {@code mode} is a
     * {@link net.minecraft.inventory.ClickType} name — {@code PICKUP} for a
     * normal click, {@code QUICK_MOVE} for shift-click, etc.
     */
    public void clickSlot(int slot, int button, String mode) throws IOException {
        JsonObject command = command("click_slot");
        command.addProperty("slot", slot);
        command.addProperty("button", button);
        command.addProperty("mode", mode);
        assertOk(execute(command));
    }

    public void dragScreenPoint(int startX, int startY, int endX, int endY, int button) throws IOException {
        JsonObject command = command("drag_screen_point");
        command.addProperty("startX", startX);
        command.addProperty("startY", startY);
        command.addProperty("endX", endX);
        command.addProperty("endY", endY);
        command.addProperty("button", button);
        assertOk(execute(command));
    }

    public void focusField(String fieldName) throws IOException {
        JsonObject command = command("focus_field");
        command.addProperty("field", fieldName);
        assertOk(execute(command));
    }

    public void typeText(String text) throws IOException {
        JsonObject command = command("type_text");
        command.addProperty("text", text);
        assertOk(execute(command));
    }

    public void pressEnterAfterTyping(String text) throws IOException {
        JsonObject command = command("type_text");
        command.addProperty("text", text);
        command.addProperty("pressEnter", true);
        assertOk(execute(command));
    }

    public JsonObject reportState() throws IOException {
        return assertOk(execute(command("report_state")));
    }

    /**
     * Client-side view of the entity the player is currently riding. Reports
     * {@code riding} (bool), and when riding: {@code entityClass}, {@code entityId},
     * {@code posX}/{@code posY}/{@code posZ} and {@code motionX}/{@code motionY}/
     * {@code motionZ}. This is the authoritative way to assert what the player's
     * CLIENT actually renders — distinct from a server-side entity query — so it
     * catches client-side position-sync / interpolation regressions.
     */
    public JsonObject reportRidingEntity() throws IOException {
        return assertOk(execute(command("report_riding_entity")));
    }

    /**
     * Injects a real key-binding press/release on the client, exactly as the
     * keyboard would. Drives {@code KeyBinding.isKeyDown()} (held movement keys),
     * a single {@code isPressed()} edge, and Forge's {@code InputEvent.KeyInputEvent}
     * (fired per key event by the real keyboard loop), so mod input handlers fire
     * their real packet path whether they poll on {@code ClientTickEvent} or
     * subscribe to {@code KeyInputEvent} — not a server-side shortcut.
     *
     * <p>The event carries no key code (it never did): a handler that reads
     * {@code Keyboard.getEventKey()} directly instead of polling its own
     * {@code KeyBinding} sees LWJGL's empty event state and stays unreachable.</p>
     *
     * @param keyCode LWJGL key code (e.g. {@link org.lwjgl.input.Keyboard#KEY_Z})
     * @param pressed true to hold the key down, false to release it
     */
    public void setKey(int keyCode, boolean pressed) throws IOException {
        JsonObject command = command("set_key");
        command.addProperty("keyCode", keyCode);
        command.addProperty("pressed", pressed);
        assertOk(execute(command));
    }

    /** Convenience: hold a key down ({@link #setKey(int, boolean) setKey(keyCode, true)}). */
    public void holdKey(int keyCode) throws IOException {
        setKey(keyCode, true);
    }

    /** Convenience: release a key ({@link #setKey(int, boolean) setKey(keyCode, false)}). */
    public void releaseKey(int keyCode) throws IOException {
        setKey(keyCode, false);
    }

    /**
     * Sets the client player's look direction, exactly as the mouse would after
     * accumulating movement. Drives {@code EntityPlayerSP.rotationYaw/rotationPitch}
     * (and the prev-tick fields, so there is no render interpolation jump), so mod
     * code that reads the player's look on {@code ClientTickEvent} (e.g. a flight
     * controller that aims a craft at where the pilot is looking) exercises its
     * real path — not a server-side shortcut.
     *
     * @param yaw   absolute yaw in degrees
     * @param pitch absolute pitch in degrees (negative = up, MC convention)
     */
    public void setLook(float yaw, float pitch) throws IOException {
        JsonObject command = command("set_look");
        command.addProperty("yaw", yaw);
        command.addProperty("pitch", pitch);
        assertOk(execute(command));
    }

    /**
     * Turns the client player's look by a RAW mouse delta, through the game's own
     * {@code Entity.turn} - the exact method the real mouse handler feeds accumulated
     * movement into. Unlike {@link #setLook} (which writes the rotation fields directly,
     * like a teleport), this exercises every mod hook installed on the turn path, e.g.
     * a frame-relative look transform for a player standing on a moving platform.
     *
     * <p>Deltas are in vanilla mouse units: {@code rotationYaw += deltaYaw * 0.15},
     * {@code rotationPitch -= deltaPitch * 0.15} (positive {@code deltaPitch} looks UP),
     * pitch clamped to +-90.</p>
     */
    public void turnLook(float deltaYaw, float deltaPitch) throws IOException {
        JsonObject command = command("turn_look");
        command.addProperty("deltaYaw", deltaYaw);
        command.addProperty("deltaPitch", deltaPitch);
        assertOk(execute(command));
    }

    /**
     * Reflectively reads a static field on the client and returns its
     * {@code String.valueOf(...)} as {@code value} (plus {@code isNull},
     * {@code type}). Lets a test assert arbitrary client-side mod state (HUD
     * text, render flags, …) without the framework depending on the mod.
     *
     * @param className fully-qualified class name (loaded on the client classpath)
     * @param fieldName a static field on that class or a superclass
     */
    public JsonObject readStaticField(String className, String fieldName) throws IOException {
        JsonObject command = command("read_static_field");
        command.addProperty("className", className);
        command.addProperty("fieldName", fieldName);
        return assertOk(execute(command));
    }

    /**
     * Call a static {@code void}/value method with {@code int} parameters on the CLIENT thread.
     *
     * <p>The mouse counterpart of {@link #setKey}: a bot has no window, so it cannot feed LWJGL's
     * mouse queue any more than it can feed the key queue. Pointing this at the mod's own raw-delta
     * entry point runs exactly the code a real mouse move runs.</p>
     */
    public JsonObject invokeStaticInt(String className, String methodName, int... args)
            throws IOException {
        JsonObject command = command("invoke_static_int");
        command.addProperty("className", className);
        command.addProperty("methodName", methodName);
        com.google.gson.JsonArray intArgs = new com.google.gson.JsonArray();
        for (int arg : args) {
            intArgs.add(arg);
        }
        command.add("intArgs", intArgs);
        return assertOk(execute(command));
    }

    /**
     * Capture what the client is actually drawing, to {@code <gameDir>/screenshots/<name>.png}.
     *
     * <p>The bot cannot press F2 - vanilla dispatches that from the raw LWJGL key-event queue, which
     * {@link #setKey} does not feed - so this calls {@code ScreenShotHelper} on the client thread
     * instead. The game directory is the harness {@code root()}, which is deleted after the test, so
     * read or copy the file before teardown.</p>
     *
     * @return {@code path}, {@code exists}, {@code bytes}, {@code width}, {@code height}, and
     *         {@code framebuffer} (whether the FBO capture path was used)
     */
    public JsonObject screenshot(String name) throws IOException {
        JsonObject command = command("screenshot");
        command.addProperty("name", name);
        return assertOk(execute(command));
    }

    /**
     * Turn the client's framebuffer object on or off at runtime, returning its {@code previous} value.
     *
     * <p>{@link #screenshot} can only see real pixels when it is on: otherwise the last frame lives in a
     * back buffer whose contents the driver may discard at the swap. The harness leaves it off for
     * driver safety, so a test that means to LOOK at the frame enables it, renders a few
     * ({@link #waitTicks}), captures, and puts it back.</p>
     *
     * <p><b>Known limit — enabling it here does NOT reliably capture the WORLD.</b> Measured 2026-07-29:
     * after a runtime enable, the recreated framebuffer receives the HUD pass but not the world pass, so
     * a capture comes back as the framebuffer's own clear colour (opaque WHITE) with only the HUD drawn
     * over it. It looks exactly like "the world rendered nothing", which is how it cost a session: three
     * runs read an empty instrument as a verdict about a renderer. A test that measures WORLD pixels must
     * start the client with the FBO already on ({@code -PclientFbo=true}, i.e.
     * {@code -Dforge.test.client.fbo=true}) and carry a control frame from a scene it knows is
     * non-empty.</p>
     */
    public JsonObject setFramebuffer(boolean enabled) throws IOException {
        JsonObject command = command("set_framebuffer");
        command.addProperty("enabled", enabled);
        return assertOk(execute(command));
    }

    /**
     * Set the client's render distance in chunks at runtime, returning its {@code previous} value.
     *
     * <p>The sibling of {@link #setFramebuffer}, and needed for the same kind of reason: vanilla runs the
     * whole SKY pass - and therefore a dimension's custom {@code IRenderHandler} - only when
     * {@code renderDistanceChunks >= 4}. The harness pins it at 2, so a test that captures a sky must
     * raise it first (and restore it after), or it will screenshot an empty frame for a reason that has
     * nothing to do with what it is testing. The value also drives the sky projection's far plane
     * ({@code farPlaneDistance * 2}), so pick one that clears the geometry's radius.</p>
     *
     * @return {@code previous}, {@code chunks}, and {@code skyPassEnabled} (whether the new value passes
     *         vanilla's sky gate)
     */
    public JsonObject setRenderDistance(int chunks) throws IOException {
        JsonObject command = command("set_render_distance");
        command.addProperty("chunks", chunks);
        return assertOk(execute(command));
    }

    /**
     * Hide or show the whole in-game HUD (vanilla's F1), returning its {@code previous} value.
     *
     * <p>Required before a {@link #screenshot} that MEASURES the rendered world. The chat overlay carries
     * this harness's own per-command completion markers, so it changes between two captures; the
     * crosshair inverts to white over a dark background. Both are frame differences the test did not
     * cause. Hiding also drains the toast queue, because toasts are drawn outside vanilla's hideGUI gate
     * - so call this immediately before each capture, not once at the start.</p>
     */
    public JsonObject setHudHidden(boolean hidden) throws IOException {
        JsonObject command = command("set_hud_hidden");
        command.addProperty("hidden", hidden);
        return assertOk(execute(command));
    }

    /**
     * Right-clicks the HELD item with no block target: routes through
     * {@code PlayerControllerMP.processRightClick} (the real
     * {@code CPacketPlayerTryUseItem} path), so {@code Item.onItemRightClick}
     * runs on both sides against the real player. Returns the client-side
     * {@code EnumActionResult} name under {@code result}.
     */
    public JsonObject useItem() throws IOException {
        return assertOk(execute(command("use_item")));
    }

    /**
     * Recent lines of the client chat overlay, newest first, i18n already
     * resolved — exactly the text the player reads. The honest observation
     * for "the player received a chat message". The response also carries
     * {@code overlay} — the last ACTION-BAR message ({@code setOverlayMessage}
     * / the GAME_INFO chat type, which never enters the chat list) — and
     * {@code overlayTicks} (&gt; 0 while it is still on screen).
     */
    public JsonObject reportChat(int limit) throws IOException {
        JsonObject command = command("report_chat");
        command.addProperty("limit", limit);
        return assertOk(execute(command));
    }

    /**
     * Client-side view of the player's held / offhand / armor / main-inventory
     * stacks ({@code id}, {@code count}, {@code nbt} string). This is the
     * synced state the HUD and inventory screen render from.
     */
    public JsonObject reportPlayerItems() throws IOException {
        return assertOk(execute(command("report_player_items")));
    }

    /**
     * Entities in the CLIENT world within {@code radius} of the player whose
     * class name contains {@code classContains} (empty = all). Pins "the
     * client actually sees the entity" — spawn sync, tracking range, render
     * presence — which no server-side query can.
     */
    public JsonObject reportEntities(String classContains, double radius) throws IOException {
        JsonObject command = command("report_entities");
        command.addProperty("classContains", classContains);
        command.addProperty("radius", radius);
        return assertOk(execute(command));
    }

    /**
     * Right-clicks a block exactly as the player would: routes through
     * {@code PlayerControllerMP.processRightClickBlock} on the client thread,
     * which sends the real {@code CPacketPlayerTryUseItemOnBlock} — so the
     * server runs its production interaction path (reach checks,
     * {@code Block.onBlockActivated}, bed {@code trySleep}, …) with the real
     * player. Returns the client-side {@code EnumActionResult} name under
     * {@code result}.
     */
    public JsonObject interactBlock(int x, int y, int z) throws IOException {
        JsonObject command = command("interact_block");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return assertOk(execute(command));
    }

    /**
     * What the client's crosshair is currently pointing at — {@code mc.objectMouseOver},
     * the very field vanilla's {@code rightClickMouse()} reads to decide what a
     * right-click hits. Reports {@code present}, {@code typeOfHit}
     * ({@code MISS}/{@code BLOCK}/{@code ENTITY}), and when a block position is
     * carried: {@code hasBlockPos}, {@code blockX}/{@code blockY}/{@code blockZ},
     * {@code block} (registry id at that position in the client world),
     * {@code sideHit} and {@code hitX}/{@code hitY}/{@code hitZ}; for an entity hit,
     * {@code entityClass} / {@code entityId}.
     *
     * <p>The honest observation for "the bot is actually AIMED at the thing" before it
     * presses use — without it, an aim that missed and a click the server refused are
     * indistinguishable. Note the raytrace runs in whatever coordinate space the world's
     * collision hooks provide, so on a physics-mod ship the reported position is the
     * ship's own block position, not the rendered world one.</p>
     */
    public JsonObject reportMouseOver() throws IOException {
        return assertOk(execute(command("report_mouse_over")));
    }

    /**
     * Forge mod registry as the CLIENT sees it: {@code loadedCount} /
     * {@code activeCount} (the two numbers the vanilla main menu renders as
     * "N mods loaded, M mods active" via {@code FMLCommonHandler.getBrandings})
     * plus {@code loadedModIds}. Lets a test pin loaded/active parity and the
     * presence/absence of specific containers at the layer the player reads.
     */
    public JsonObject reportMods() throws IOException {
        return assertOk(execute(command("report_mods")));
    }

    /**
     * Sends one chat line exactly as if the player typed it — leading-{@code /}
     * commands included. Routes through {@code EntityPlayerSP.sendChatMessage}
     * (the real {@code CPacketChatMessage} path), so the server handles it with
     * a PLAYER sender: permission checks, the sender's world/dimension, and
     * {@code CommandEvent} hooks all run their production path. This is the
     * canonical way to e2e a command whose behaviour depends on where the
     * player stands — console-driven commands can't reproduce that.
     */
    public void sendChat(String message) throws IOException {
        JsonObject command = command("send_chat");
        command.addProperty("message", message);
        assertOk(execute(command));
    }

    /**
     * Client-side view of vanilla weather state for whatever dim the player is
     * currently in. Reports {@code dim}, {@code worldInfoClass}, {@code isRaining},
     * {@code isThundering}, {@code rainTime}, {@code thunderTime},
     * {@code rainStrength} (post-SPacketChangeGameState lerp), {@code thunderStrength}.
     * If the client world isn't ready yet, only {@code worldReady=false} is set.
     */
    public JsonObject reportWeather() throws IOException {
        return assertOk(execute(command("report_weather")));
    }

    public JsonObject blockState(int x, int y, int z) throws IOException {
        JsonObject command = command("block_state");
        command.addProperty("x", x);
        command.addProperty("y", y);
        command.addProperty("z", z);
        return assertOk(execute(command));
    }

    public void closeScreen() throws IOException {
        assertOk(execute(command("close_screen")));
    }

    /**
     * Return the CLIENT-owned state a scenario can dirty to what a freshly-booted client holds:
     * closes any open screen, clears the chat backlog, clears the action-bar overlay and its
     * countdown, and releases every held key.
     *
     * <p>Only needed when one harness carries more than one scenario. Measured 2026-08-06: on the
     * second scenario of a shared run, ALL FOUR of those channels were still carrying the first
     * scenario's state. The chat backlog is the dangerous one — a scenario that asserts "the player
     * was told X" by searching the last N lines passes on the PREVIOUS scenario's identical line,
     * with no stimulus behind it.</p>
     *
     * <p>Position and inventory are NOT reset here — they are the server's, and a caller resets
     * them through the command channel (a teleport, a clear). The response reports what was found
     * dirty ({@code clearedScreen}, {@code clearedChatLines}, {@code clearedOverlay},
     * {@code clearedOverlayTicks}) so a caller can assert on the reset rather than trust it.</p>
     */
    public JsonObject resetClientState() throws IOException {
        return assertOk(execute(command("reset_client_state")));
    }

    public void shutdown() throws IOException {
        assertOk(execute(command("shutdown")));
    }

    @Override
    public void close() throws IOException {
        try {
            if (socket.isConnected() && !socket.isClosed()) {
                try {
                    shutdown();
                } catch (IOException ignored) {
                    // The client may already be gone.
                }
            }
        } finally {
            socket.close();
        }
    }

    private JsonObject execute(JsonObject command) throws IOException {
        synchronized (writer) {
            writer.write(command.toString());
            writer.newLine();
            writer.flush();
        }

        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Client bridge closed unexpectedly");
        }

        JsonElement parsed = new JsonParser().parse(line);
        if (!parsed.isJsonObject()) {
            throw new IOException("Malformed client bridge response: " + line);
        }
        return parsed.getAsJsonObject();
    }

    private JsonObject assertOk(JsonObject response) throws IOException {
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
            String message = response.has("error") ? response.get("error").getAsString() : "unknown client bridge error";
            throw new IOException(message);
        }
        return response;
    }

    private void awaitReady(Duration timeout) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Client bridge disconnected before signaling readiness");
        }
        if ("READY".equals(line)) {
            return;
        }
        throw new IOException("Timed out waiting for client bridge readiness");
    }

    private static JsonObject command(String command) {
        JsonObject object = new JsonObject();
        object.addProperty("command", Objects.requireNonNull(command, "command"));
        return object;
    }
}

