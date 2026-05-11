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
        this.socket.setSoTimeout((int) Duration.ofMinutes(2).toMillis());
        this.reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        awaitReady(Duration.ofMinutes(2));
    }

    public void waitForWorld() throws IOException {
        assertOk(execute(command("wait_world")));
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

