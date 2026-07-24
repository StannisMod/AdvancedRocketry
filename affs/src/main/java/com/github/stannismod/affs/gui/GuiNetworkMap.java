package com.github.stannismod.affs.gui;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public class GuiNetworkMap extends GuiAffsBase {

    private static final int MAP_LEFT = 18;
    private static final int MAP_TOP = 16;
    private static final int MAP_WIDTH = 264;
    private static final int MAP_HEIGHT = 190;
    private static final double MAP_BASE_SCALE = 10.0D;
    private static final double MAP_MIN_ZOOM = 0.35D;
    private static final double MAP_MAX_ZOOM = 5.0D;

    private final TileEntity tile;
    private final INetworkMapSource source;
    private double mapPanX = 0.0D;
    private double mapPanY = 0.0D;
    private double mapZoom = 1.0D;
    private double prevMapPanX = 0.0D;
    private double prevMapPanY = 0.0D;
    private double prevMapZoom = 1.0D;
    private boolean mapDragging = false;
    private int dragStartMouseX = 0;
    private int dragStartMouseY = 0;
    private double dragStartPanX = 0.0D;
    private double dragStartPanY = 0.0D;

    public GuiNetworkMap(Container container, TileEntity tile, INetworkMapSource source) {
        super(container);
        this.tile = tile;
        this.source = source;
        this.xSize = 300;
        this.ySize = 222;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (isInsideMap(mouseX, mouseY) && mouseButton == 0) {
            mapDragging = true;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartPanX = mapPanX;
            dragStartPanY = mapPanY;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapDragging && clickedMouseButton == 0) {
            mapPanX = dragStartPanX + (mouseX - dragStartMouseX);
            mapPanY = dragStartPanY + (mouseY - dragStartMouseY);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        mapDragging = false;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (isInsideMap(mouseX, mouseY)) {
                zoomMap(mouseX, mouseY, wheel > 0 ? 1.15D : 1.0D / 1.15D);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawNetworkMap(mouseX, mouseY, partialTicks);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        prevMapPanX = mapPanX;
        prevMapPanY = mapPanY;
        prevMapZoom = mapZoom;
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawFlatGrayBackground();
        drawMapPanelBackground();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        drawTitle(I18n.format("gui.affs.network_map"));
        fontRenderer.drawString(I18n.format("gui.affs.network_map_hint"), CONTENT_LEFT, ySize - 12, MUTED_COLOR);
    }

    private void drawMapPanelBackground() {
        int left = guiLeft + MAP_LEFT;
        int top = guiTop + MAP_TOP;
        int right = left + MAP_WIDTH;
        int bottom = top + MAP_HEIGHT;
        drawRect(left, top, right, bottom, 0xFF1F2D3D);
        drawRect(left + 1, top + 1, right - 1, bottom - 1, 0xFF29435A);
        drawRect(left + 2, top + 2, right - 2, bottom - 2, 0xFF16212E);
    }

    private void drawNetworkMap(int mouseX, int mouseY, float partialTicks) {
        List<NetworkMapMarker> markers = source.getMapMarkers();
        if (markers.isEmpty()) {
            return;
        }

        int left = guiLeft + MAP_LEFT + 2;
        int top = guiTop + MAP_TOP + 2;
        int width = MAP_WIDTH - 4;
        int height = MAP_HEIGHT - 4;
        int centerX = left + width / 2;
        int centerY = top + height / 2;
        double panX = stableLerp(prevMapPanX, mapPanX, partialTicks);
        double panY = stableLerp(prevMapPanY, mapPanY, partialTicks);
        double zoom = stableLerp(prevMapZoom, mapZoom, partialTicks);
        double scale = MAP_BASE_SCALE * zoom;
        BlockPos origin = tile.getPos();

        enableMapScissor(left, top, width, height);
        try {
            drawMapGrid(left, top, width, height, centerX, centerY, scale, panX, panY);
            drawAreaLayer(markers, centerX, centerY, scale, origin, panX, panY);
            drawCableLayer(markers, centerX, centerY, scale, origin, panX, panY);
            drawNodeLayer(markers, centerX, centerY, scale, origin, panX, panY);
            drawMapBorder(isInsideMap(mouseX, mouseY), left, top, width, height);
        } finally {
            disableScissor();
        }

        if (isInsideMap(mouseX, mouseY)) {
            drawMapTooltips(mouseX, mouseY, markers, centerX, centerY, scale, origin, panX, panY);
        }
    }

    private void enableMapScissor(int left, int top, int width, int height) {
        ScaledResolution scaled = new ScaledResolution(mc);
        int scaleFactor = scaled.getScaleFactor();
        int scissorX = left * scaleFactor;
        int scissorY = mc.displayHeight - (top + height) * scaleFactor;
        int scissorW = width * scaleFactor;
        int scissorH = height * scaleFactor;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scissorX, scissorY, scissorW, scissorH);
        GlStateManager.pushMatrix();
    }

    private void disableScissor() {
        GlStateManager.popMatrix();
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawMapGrid(int left, int top, int width, int height, int centerX, int centerY, double scale, double panX, double panY) {
        int stepBlocks = scale >= 18.0D ? 1 : scale >= 10.0D ? 2 : scale >= 5.0D ? 4 : 8;
        int stepPixels = Math.max(4, (int) Math.round(stepBlocks * scale));
        int startX = centerX + Math.floorMod((int) Math.floor(panX), stepPixels);
        int startY = centerY + Math.floorMod((int) Math.floor(panY), stepPixels);
        int gridColor = 0x223A5D7A;
        for (int x = startX; x >= left; x -= stepPixels) {
            drawRect(x, top, x + 1, top + height, gridColor);
        }
        for (int x = startX + stepPixels; x <= left + width; x += stepPixels) {
            drawRect(x, top, x + 1, top + height, gridColor);
        }
        for (int y = startY; y >= top; y -= stepPixels) {
            drawRect(left, y, left + width, y + 1, gridColor);
        }
        for (int y = startY + stepPixels; y <= top + height; y += stepPixels) {
            drawRect(left, y, left + width, y + 1, gridColor);
        }
    }

    private void drawAreaLayer(List<NetworkMapMarker> markers, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        for (NetworkMapMarker marker : markers) {
            if (!marker.isArea()) {
                continue;
            }
            if (marker.getKind() == NetworkMapMarker.KIND_FIELD) {
                drawFieldArea(marker, centerX, centerY, scale, origin, panX, panY);
            } else if (marker.getKind() == NetworkMapMarker.KIND_CONTOUR) {
                drawContourArea(marker, centerX, centerY, scale, origin, panX, panY);
            }
        }
    }

    private void drawFieldArea(NetworkMapMarker marker, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        double px = projectX(marker.getX(), origin.getX(), centerX, scale, panX);
        double py = projectY(marker.getZ(), origin.getZ(), centerY, scale, panY);
        int radius = Math.max(3, (int) Math.round((marker.getMaxX() - marker.getMinX()) * 0.5D * scale));
        if (radius <= 0) {
            radius = Math.max(3, (int) Math.round(scale));
        }
        drawFilledCircle((int) Math.round(px), (int) Math.round(py), radius, 0x1A88BFFF);
        drawCircleOutline((int) Math.round(px), (int) Math.round(py), radius, 0xFFBFE6FF);
    }

    private void drawContourArea(NetworkMapMarker marker, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        double x1 = projectX(marker.getMinX(), origin.getX(), centerX, scale, panX);
        double y1 = projectY(marker.getMinZ(), origin.getZ(), centerY, scale, panY);
        double x2 = projectX(marker.getMaxX(), origin.getX(), centerX, scale, panX);
        double y2 = projectY(marker.getMaxZ(), origin.getZ(), centerY, scale, panY);
        int minX = (int) Math.floor(Math.min(x1, x2));
        int minY = (int) Math.floor(Math.min(y1, y2));
        int maxX = (int) Math.ceil(Math.max(x1, x2));
        int maxY = (int) Math.ceil(Math.max(y1, y2));
        int fill = 0x224C7AD9;
        int edge = 0xFF9BD0FF;
        drawRect(minX, minY, maxX + 1, maxY + 1, fill);
        drawRect(minX, minY, maxX + 1, minY + 1, edge);
        drawRect(minX, maxY, maxX + 1, maxY + 1, edge);
        drawRect(minX, minY, minX + 1, maxY + 1, edge);
        drawRect(maxX, minY, maxX + 1, maxY + 1, edge);
    }

    private void drawCableLayer(List<NetworkMapMarker> markers, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        for (int i = 0; i < markers.size(); i++) {
            NetworkMapMarker a = markers.get(i);
            if (a.getKind() != NetworkMapMarker.KIND_CABLE || a.isArea()) {
                continue;
            }
            for (int j = i + 1; j < markers.size(); j++) {
                NetworkMapMarker b = markers.get(j);
                if (b.getKind() != NetworkMapMarker.KIND_CABLE || b.isArea()) {
                    continue;
                }
                if (!areAdjacent(a, b)) {
                    continue;
                }
                int x1 = (int) Math.round(projectX(a.getX(), origin.getX(), centerX, scale, panX));
                int y1 = (int) Math.round(projectY(a.getZ(), origin.getZ(), centerY, scale, panY));
                int x2 = (int) Math.round(projectX(b.getX(), origin.getX(), centerX, scale, panX));
                int y2 = (int) Math.round(projectY(b.getZ(), origin.getZ(), centerY, scale, panY));
                drawThinLine(x1, y1, x2, y2, 0xFF77A9FF);
            }
        }

        for (NetworkMapMarker marker : markers) {
            if (marker.getKind() != NetworkMapMarker.KIND_CABLE || marker.isArea()) {
                continue;
            }
            int px = (int) Math.round(projectX(marker.getX(), origin.getX(), centerX, scale, panX));
            int py = (int) Math.round(projectY(marker.getZ(), origin.getZ(), centerY, scale, panY));
            drawRect(px - 1, py - 1, px + 2, py + 2, 0xFF4C7AD9);
            drawCableIcon(px, py);
        }
    }

    private void drawNodeLayer(List<NetworkMapMarker> markers, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        for (NetworkMapMarker marker : markers) {
            if (marker.isArea() || marker.getKind() == NetworkMapMarker.KIND_CABLE) {
                continue;
            }
            int px = (int) Math.round(projectX(marker.getX(), origin.getX(), centerX, scale, panX));
            int py = (int) Math.round(projectY(marker.getZ(), origin.getZ(), centerY, scale, panY));
            int size = Math.max(4, (int) Math.round(scale * 0.9D));
            int color = blockColor(marker.getKind());
            drawRect(px - size / 2, py - size / 2, px + size / 2 + 1, py + size / 2 + 1, color);
            drawBlockIcon(marker.getKind(), px, py);
        }
    }

    private void drawMapTooltips(int mouseX, int mouseY, List<NetworkMapMarker> markers, int centerX, int centerY, double scale, BlockPos origin, double panX, double panY) {
        NetworkMapMarker hovered = null;
        for (NetworkMapMarker marker : markers) {
            if (marker.isArea()) {
                if (marker.getKind() == NetworkMapMarker.KIND_FIELD) {
                    int px = (int) Math.round(projectX(marker.getX(), origin.getX(), centerX, scale, panX));
                    int py = (int) Math.round(projectY(marker.getZ(), origin.getZ(), centerY, scale, panY));
                    int radius = Math.max(3, (int) Math.round((marker.getMaxX() - marker.getMinX()) * 0.5D * scale));
                    double dx = mouseX - px;
                    double dy = mouseY - py;
                    if (dx * dx + dy * dy <= (double) radius * radius) {
                        hovered = marker;
                        break;
                    }
                } else {
                    double x1 = projectX(marker.getMinX(), origin.getX(), centerX, scale, panX);
                    double y1 = projectY(marker.getMinZ(), origin.getZ(), centerY, scale, panY);
                    double x2 = projectX(marker.getMaxX(), origin.getX(), centerX, scale, panX);
                    double y2 = projectY(marker.getMaxZ(), origin.getZ(), centerY, scale, panY);
                    int minX = (int) Math.floor(Math.min(x1, x2));
                    int minY = (int) Math.floor(Math.min(y1, y2));
                    int maxX = (int) Math.ceil(Math.max(x1, x2));
                    int maxY = (int) Math.ceil(Math.max(y1, y2));
                    if (mouseX >= minX && mouseX <= maxX && mouseY >= minY && mouseY <= maxY) {
                        hovered = marker;
                        break;
                    }
                }
            } else {
                int px = (int) Math.round(projectX(marker.getX(), origin.getX(), centerX, scale, panX));
                int py = (int) Math.round(projectY(marker.getZ(), origin.getZ(), centerY, scale, panY));
                int hitRadius = Math.max(4, (int) Math.round(scale * 0.7D));
                if (Math.abs(mouseX - px) <= hitRadius && Math.abs(mouseY - py) <= hitRadius) {
                    hovered = marker;
                    break;
                }
            }
        }

        if (hovered == null) {
            return;
        }

        List<String> tooltip = new ArrayList<>();
        tooltip.add(getMarkerLabel(hovered));
        if (hovered.isArea()) {
            tooltip.add(String.format("X %d..%d, Z %d..%d", hovered.getMinX(), hovered.getMaxX(), hovered.getMinZ(), hovered.getMaxZ()));
            tooltip.add(String.format("Y %d..%d", hovered.getMinY(), hovered.getMaxY()));
        } else {
            tooltip.add(String.format("X %d, Y %d, Z %d", hovered.getX(), hovered.getY(), hovered.getZ()));
        }
        drawHoveringText(tooltip, mouseX, mouseY);
    }

    private void drawMapBorder(boolean hovered, int left, int top, int width, int height) {
        int border = hovered ? 0xFF8FC2FF : 0xFF55708A;
        drawRect(left, top, left + width, top + 1, border);
        drawRect(left, top + height - 1, left + width, top + height, border);
        drawRect(left, top, left + 1, top + height, border);
        drawRect(left + width - 1, top, left + width, top + height, border);
    }

    private void drawThinLine(int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps <= 0) {
            drawRect(x1, y1, x1 + 1, y1 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            drawRect(x, y, x + 1, y + 1, color);
        }
    }

    private void drawFilledCircle(int centerX, int centerY, int radius, int color) {
        int size = radius * 2 + 1;
        for (int dy = 0; dy < size; dy++) {
            int y = centerY - radius + dy;
            int relY = y - centerY;
            int horizontal = (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - relY * relY)));
            drawRect(centerX - horizontal, y, centerX + horizontal + 1, y + 1, color);
        }
    }

    private void drawCircleOutline(int centerX, int centerY, int radius, int color) {
        int segments = Math.max(24, radius * 4);
        int prevX = centerX + radius;
        int prevY = centerY;
        for (int i = 1; i <= segments; i++) {
            double angle = (Math.PI * 2.0D * i) / segments;
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            drawThinLine(prevX, prevY, x, y, color);
            prevX = x;
            prevY = y;
        }
    }

    private double projectX(int worldX, int originX, int centerX, double scale, double panX) {
        return centerX + panX + (worldX - originX) * scale;
    }

    private double projectY(int worldZ, int originZ, int centerY, double scale, double panY) {
        return centerY + panY + (worldZ - originZ) * scale;
    }

    private boolean areAdjacent(NetworkMapMarker a, NetworkMapMarker b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx + dy + dz == 1;
    }

    private int blockColor(byte kind) {
        switch (kind) {
            case NetworkMapMarker.KIND_FIELD:
                return 0xFF8FD5FF;
            case NetworkMapMarker.KIND_CONTOUR:
                return 0xFF7EA6D8;
            case NetworkMapMarker.KIND_INJECTOR:
                return 0xFF9ED8FF;
            case NetworkMapMarker.KIND_GENERATOR:
                return 0xFF81CFFF;
            case NetworkMapMarker.KIND_SOURCE:
                return 0xFF77D9FF;
            case NetworkMapMarker.KIND_SINK:
                return 0xFF7EC6FF;
            case NetworkMapMarker.KIND_CONSOLE:
                return 0xFFE8F2FF;
            case NetworkMapMarker.KIND_FRAME:
                return 0xFFBED4E8;
            default:
                return 0xFFA8BACB;
        }
    }

    private String getMarkerLabel(NetworkMapMarker marker) {
        if (marker.isArea()) {
            switch (marker.getKind()) {
                case NetworkMapMarker.KIND_FIELD:
                    return "Shield field";
                case NetworkMapMarker.KIND_CONTOUR:
                    return "Contour field";
                default:
                    return "Network area";
            }
        }
        switch (marker.getKind()) {
            case NetworkMapMarker.KIND_FIELD:
                return "Shield injector";
            case NetworkMapMarker.KIND_CABLE:
                return "Shield cable";
            case NetworkMapMarker.KIND_GENERATOR:
                return "Shield generator";
            case NetworkMapMarker.KIND_SOURCE:
                return "Shield source";
            case NetworkMapMarker.KIND_SINK:
                return "Shield sink";
            case NetworkMapMarker.KIND_CONSOLE:
                return "Console";
            case NetworkMapMarker.KIND_INJECTOR:
                return "Contour injector";
            case NetworkMapMarker.KIND_FRAME:
                return "Contour frame";
            case NetworkMapMarker.KIND_CONTOUR:
                return "Contour";
            default:
                return "Network element";
        }
    }

    private void drawBlockIcon(byte kind, int x, int y) {
        switch (kind) {
            case NetworkMapMarker.KIND_FIELD:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFD8F7FF);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF5AA9FF);
                drawRect(x, y - 1, x + 1, y + 2, 0xFFEAF6FF);
                break;
            case NetworkMapMarker.KIND_CONSOLE:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFEAF6FF);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF2F5D82);
                break;
            case NetworkMapMarker.KIND_GENERATOR:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFD8F0FF);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF5AA9FF);
                drawCableIcon(x, y);
                break;
            case NetworkMapMarker.KIND_INJECTOR:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFD8F7FF);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF8FD5FF);
                drawRect(x - 1, y + 1, x + 2, y + 2, 0xFFEAF6FF);
                break;
            case NetworkMapMarker.KIND_SOURCE:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFD8FFE8);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF6CD68F);
                break;
            case NetworkMapMarker.KIND_SINK:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFFFE2E2);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFFE06D6D);
                break;
            case NetworkMapMarker.KIND_FRAME:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFD4E3F2);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF7A91A8);
                break;
            default:
                drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFF0F0F0);
                drawRect(x - 1, y - 1, x + 2, y + 2, 0xFFAAB5C0);
                break;
        }
    }

    private void drawCableIcon(int x, int y) {
        drawRect(x - 1, y - 3, x + 2, y - 2, 0xFFEAF6FF);
        drawRect(x - 1, y + 2, x + 2, y + 3, 0xFFEAF6FF);
        drawRect(x - 3, y - 1, x - 2, y + 2, 0xFFEAF6FF);
        drawRect(x + 2, y - 1, x + 3, y + 2, 0xFFEAF6FF);
    }

    private boolean isInsideMap(int mouseX, int mouseY) {
        int left = guiLeft + MAP_LEFT;
        int top = guiTop + MAP_TOP;
        return mouseX >= left && mouseX < left + MAP_WIDTH && mouseY >= top && mouseY < top + MAP_HEIGHT;
    }

    private void zoomMap(int mouseX, int mouseY, double factor) {
        double oldZoom = mapZoom;
        double newZoom = Math.max(MAP_MIN_ZOOM, Math.min(MAP_MAX_ZOOM, oldZoom * factor));
        if (Math.abs(newZoom - oldZoom) < 1.0E-6D) {
            return;
        }
        int left = guiLeft + MAP_LEFT + 2;
        int top = guiTop + MAP_TOP + 2;
        int width = MAP_WIDTH - 4;
        int height = MAP_HEIGHT - 4;
        int centerX = left + width / 2;
        int centerY = top + height / 2;
        double oldScale = MAP_BASE_SCALE * oldZoom;
        double newScale = MAP_BASE_SCALE * newZoom;
        double worldX = (mouseX - centerX - mapPanX) / oldScale;
        double worldZ = (mouseY - centerY - mapPanY) / oldScale;
        mapZoom = newZoom;
        mapPanX = mouseX - centerX - worldX * newScale;
        mapPanY = mouseY - centerY - worldZ * newScale;
    }

    private static double lerp(double start, double end, float partialTicks) {
        return start + (end - start) * partialTicks;
    }

    private static double stableLerp(double start, double end, float partialTicks) {
        if (Math.abs(end - start) < 1.0E-5D) {
            return end;
        }
        return lerp(start, end, partialTicks);
    }
}
