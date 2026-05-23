package zmaster587.advancedRocketry.event;
// This code does not work - it should display the earth below rockets at start but it does not.
// The detailed map is scaled too small and it is ugly even with correct scale
// maybe just use leo as earth? 


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.armor.IFillableArmor;
import zmaster587.advancedRocketry.atmosphere.AtmosphereHandler;
import zmaster587.advancedRocketry.client.KeyBindings;
import zmaster587.advancedRocketry.client.render.ClientDynamicTexture;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.inventory.TextureResources;
import zmaster587.advancedRocketry.util.ItemAirUtils;
import zmaster587.libVulpes.api.IArmorComponent;
import zmaster587.libVulpes.api.IModularArmor;
import zmaster587.libVulpes.client.ResourceIcon;
import zmaster587.libVulpes.render.RenderHelper;

import javax.annotation.Nonnull;
import java.nio.IntBuffer;
import java.util.List;

public class RocketEventHandler extends Gui {


    private static final int numTicksToDisplay = 100;
    public static GuiBox suitPanel = new GuiBox(8, 8, 24, 24);
    public static GuiBox oxygenBar = new GuiBox(8, -57, 80, 48);
    public static GuiBox hydrogenBar = new GuiBox(8, -74, 80, 48);
    public static GuiBox atmBar = new GuiBox(8, 27, 200, 48);
    private static String displayString = "";
    private static long lastDisplayTime = -1000;
    private ResourceLocation background = TextureResources.rocketHud;
    private static long suppressSuffocationWarningUntil = Long.MIN_VALUE;
    private static int lastSuffocationWarningDim = Integer.MIN_VALUE;


    @SideOnly(Side.CLIENT)
    public static void setOverlay(long endTime, String msg) {
        displayString = msg;
        lastDisplayTime = endTime;
    }

    @SubscribeEvent
    public void playerTeleportEvent(PlayerEvent.PlayerChangedDimensionEvent event) {
        //Fix O2, space elevator popup displaying after teleporting
        lastDisplayTime = -1000;
    }

    @SubscribeEvent
    public void onScreenRender(RenderGameOverlayEvent.Post event) {
        Entity ride;
        if (event.getType() == ElementType.HOTBAR) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) {
                return;
            }
            if ((ride = mc.player.getRidingEntity()) instanceof EntityRocket) {
                EntityRocket rocket = (EntityRocket) ride;

                GlStateManager.enableBlend();

                mc.renderEngine.bindTexture(background);

                //Draw BG
                this.drawTexturedModalRect(0, 0, 0, 0, 17, 252);

                //Draw altitude indicator
                float percentOrbit = MathHelper.clamp((float) ((rocket.posY - rocket.world.provider.getAverageGroundLevel()) / (float) (ARConfiguration.getCurrentConfig().orbit - rocket.world.provider.getAverageGroundLevel())), 0f, 1f);
                this.drawTexturedModalRect(3, 8 + (int) (79 * (1 - percentOrbit)), 17, 0, 6, 6); //6 to 83

                //Draw Velocity indicator
                this.drawTexturedModalRect(3, 94 + (int) (69 * (0.5 - (MathHelper.clamp((float) (rocket.motionY), -1f, 1f) / 2f))), 17, 0, 6, 6); //94 to 161

                //Draw fuel indicator
                int size = (int) (68 * rocket.getNormallizedProgress(0));
                this.drawTexturedModalRect(3, 242 - size, 17, 75 - size, 3, size); //94 to 161

                GlStateManager.disableBlend();
                String str = rocket.getTextOverlay();
                if (!str.isEmpty()) {

                    String[] strs = str.split("\n");
                    int vertPos = 0;
                    for (String strPart : strs) {

                        FontRenderer fontRenderer = mc.fontRenderer;

                        float scale = str.length() < 50 ? 1f : 0.5f;

                        int screenX = (int) ((event.getResolution().getScaledWidth() / (scale * 6) - fontRenderer.getStringWidth(strPart) / 2));
                        int screenY = (int) ((event.getResolution().getScaledHeight() / 18) / scale) + 18 * vertPos;


                        GL11.glPushMatrix();
                        GL11.glScalef(scale * 3, scale * 3, scale * 3);

                        fontRenderer.drawStringWithShadow(strPart, screenX, screenY, 0xFFFFFF);

                        GL11.glPopMatrix();

                        vertPos++;
                    }
                }
                // New bottom-right hint
                if (mc.currentScreen == null) { // no GUI open
                    FontRenderer fontRenderer = mc.fontRenderer;
                    String keyName = GameSettings.getKeyDisplayString(
                            KeyBindings.getOpenRocketUI().getKeyCode()
                    );
                    String hint = I18n.format("msg.entity.rocket.openGuiHint", keyName);

                    int scaledW = event.getResolution().getScaledWidth();
                    int scaledH = event.getResolution().getScaledHeight();
                    int textWidth = fontRenderer.getStringWidth(hint);
                    int textHeight = fontRenderer.FONT_HEIGHT;

                    float scale = 1.0F;
                    float x = (scaledW - 4 - textWidth * scale) / scale;
                    float y = (scaledH - 4 - textHeight * scale) / scale;

                    GL11.glPushMatrix();
                    GL11.glScalef(scale, scale, scale);
                    fontRenderer.drawStringWithShadow(hint, x, y, 0xFFFFFF);
                    GL11.glPopMatrix();
                }               

            }

            //Draw the O2 Bar if needed
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator())) {
                ItemStack chestPiece = mc.player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
                IFillableArmor fillable = null;
                if (!chestPiece.isEmpty() && chestPiece.getItem() instanceof IFillableArmor)
                    fillable = (IFillableArmor) chestPiece.getItem();
                else if (ItemAirUtils.INSTANCE.isStackValidAirContainer(chestPiece))
                    fillable = new ItemAirUtils.ItemAirWrapper(chestPiece);

                if (fillable != null) {
                    float size = fillable.getAirRemaining(chestPiece) / (float) fillable.getMaxAir(chestPiece);

                    GlStateManager.enableBlend();
                    mc.renderEngine.bindTexture(background);
                    GlStateManager.color(1f, 1f, 1f);
                    int width = 83;
                    int screenX = oxygenBar.getRenderX();//+ 8;
                    int screenY = oxygenBar.getRenderY();//- 57;

                    //Draw BG
                    this.drawTexturedModalRect(screenX, screenY, 23, 0, width, 17);
                    this.drawTexturedModalRect(screenX, screenY, 23, 17, (int) (width * size), 17);
                }
            }

            //Draw module icons
            if (!(mc.player.capabilities.isCreativeMode || mc.player.isSpectator()) && !mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).isEmpty() && mc.player.getItemStackFromSlot(EntityEquipmentSlot.HEAD).getItem() instanceof IModularArmor) {
                for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                    renderModuleSlots(mc.player.getItemStackFromSlot(slot), 4 - slot.getIndex(), event);
                }
            }


            long worldTime = mc.world.getTotalWorldTime();

            if (mc.player.dimension != lastSuffocationWarningDim) {
                lastSuffocationWarningDim = mc.player.dimension;
                AtmosphereHandler.lastSuffocationTime = worldTime - numTicksToDisplay - 1;
                suppressSuffocationWarningUntil = worldTime + 40;
            }

            // In event of world change make sure the warning isn't displayed
            if (worldTime - AtmosphereHandler.lastSuffocationTime < 0) {
                AtmosphereHandler.lastSuffocationTime = worldTime - numTicksToDisplay - 1;
            }

            // Tell the player he's suffocating if needed
            if (worldTime >= suppressSuffocationWarningUntil &&
                    worldTime - AtmosphereHandler.lastSuffocationTime < numTicksToDisplay) {
                FontRenderer fontRenderer = mc.fontRenderer;
                String str = "";
                if (AtmosphereHandler.currentAtm != null) {
                    str = AtmosphereHandler.currentAtm.getDisplayMessage();
                }

                int screenX = event.getResolution().getScaledWidth() / 6 - fontRenderer.getStringWidth(str) / 2;
                int screenY = event.getResolution().getScaledHeight() / 18;

                GL11.glPushMatrix();
                GL11.glScalef(3, 3, 3);

                fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                GlStateManager.color(1f, 1f, 1f);
                mc.getTextureManager().bindTexture(TextureResources.progressBars);
                this.drawTexturedModalRect(screenX + fontRenderer.getStringWidth(str) / 2 - 8, screenY - 16, 0, 156, 16, 16);

                GL11.glPopMatrix();
            }

            //Draw arbitrary string
            if (mc.world.getTotalWorldTime() <= lastDisplayTime) {
                FontRenderer fontRenderer = mc.fontRenderer;
                GL11.glPushMatrix();
                GL11.glScalef(2, 2, 2);
                int loc = 0;
                for (String str : displayString.split("\n")) {

                    int screenX = event.getResolution().getScaledWidth() / 4 - fontRenderer.getStringWidth(str) / 2;
                    int screenY = event.getResolution().getScaledHeight() / 12 + loc * (event.getResolution().getScaledHeight()) / 12;


                    fontRenderer.drawStringWithShadow(str, screenX, screenY, 0xFF5656);
                    loc++;
                }

                GlStateManager.color(1f, 1f, 1f);
                GL11.glPopMatrix();
            }
        }
    }

    private void renderModuleSlots(@Nonnull ItemStack armorStack, int slot, RenderGameOverlayEvent event) {
        int index = 1;
        float color = 0.85f + 0.15F * MathHelper.sin(2f * (float) Math.PI * ((Minecraft.getMinecraft().world.getTotalWorldTime()) % 60) / 60f);
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float alpha = 0.6f;


        if (!armorStack.isEmpty()) {

            boolean modularArmorFlag = armorStack.getItem() instanceof IModularArmor;

            if (modularArmorFlag || ItemAirUtils.INSTANCE.isStackValidAirContainer(armorStack)) {

                int size = 24;
                int screenY = suitPanel.getRenderY() + (slot - 1) * (size + 8);
                int screenX = suitPanel.getRenderX();

                //Draw BG
                GlStateManager.color(1f, 1f, 1f, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size, screenY + size + 4, 0d, 0.5d, 0d, 1d);
                Tessellator.getInstance().draw();

                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + size, screenY - 3, screenX + 2 + size, screenY + size + 3, 0.5d, 0.5d, 0d, 0d);
                Tessellator.getInstance().draw();

                //Draw Icon
                GlStateManager.color(color, color, color, color);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.armorSlots[slot - 1]);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, 0d, 1d, 1d, 0d);
                Tessellator.getInstance().draw();

                if (modularArmorFlag) {
                    List<ItemStack> stacks = ((IModularArmor) armorStack.getItem()).getComponents(armorStack);
                    for (ItemStack stack : stacks) {
                        GlStateManager.color(1f, 1f, 1f, 1f);
                        ((IArmorComponent) stack.getItem()).renderScreen(stack, stacks, event, this);

                        ResourceIcon icon = ((IArmorComponent) stack.getItem()).getComponentIcon(stack);
                        ResourceLocation texture = null;
                        if (icon != null)
                            texture = icon.getResourceLocation();

                        //if(texture != null) {

                        screenX = suitPanel.getRenderX() + 4 + index * (size + 2);

                        //Draw BG

                        Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                        RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX - 4, screenY - 4, screenX + size - 2, screenY + size + 4, 0.5d, 0.5d, 0d, 1d);
                        Tessellator.getInstance().draw();


                        if (texture != null) {
                            //Draw Icon
                            Minecraft.getMinecraft().renderEngine.bindTexture(texture);
                            GlStateManager.color(color, color, color, alpha);
                            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                            RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX, screenY, screenX + size, screenY + size, icon.getMinU(), icon.getMaxU(), icon.getMaxV(), icon.getMinV());
                            Tessellator.getInstance().draw();
                        } else {
                            GL11.glPushMatrix();
                            GlStateManager.translate(screenX, screenY, 0);
                            GlStateManager.scale(1.5f, 1.5f, 1.5f);
                            Minecraft.getMinecraft().getRenderItem().renderItemIntoGUI(stack, 0, 0);
                            GL11.glPopMatrix();
                        }

                        index++;
                        //}
                    }
                }

                screenX = (index) * (size + 2) + suitPanel.getRenderX() - 12;
                //Draw BG
                GlStateManager.color(1, 1, 1, 1f);
                Minecraft.getMinecraft().renderEngine.bindTexture(TextureResources.frameHUDBG);
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
                RenderHelper.renderNorthFaceWithUV(buffer, this.zLevel - 1, screenX + 12, screenY - 4, screenX + size, screenY + size + 4, 0.75d, 1d, 0d, 1d);
                Tessellator.getInstance().draw();
            }
        }

        GlStateManager.disableAlpha();
    }

    public static class GuiBox {
        int modeX = -1;
        int modeY = -1;
        int sizeX, sizeY;
        boolean isVisible = true;
        private int x;
        private int y;

        public GuiBox(int x, int y, int sizeX, int sizeY) {
            this.setRawX(x);
            this.setRawY(y);
            this.sizeX = sizeX;
            this.sizeY = sizeY;
        }

        public int getX(int scaledW) {

            if (modeX == 1)
                return scaledW - getRawX();
            else if (modeX == 0) {
                return scaledW / 2 - getRawX();
            }
            return getRawX();
        }

        public int getY(int scaledH) {

            if (modeY == 1)
                return scaledH - getRawY();
            else if (modeY == 0) {
                return scaledH / 2 - getRawY();
            }
            return getRawY();
        }

        public int getRenderX() {
            ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
            int i = scaledresolution.getScaledWidth();

            if (modeX == 1) {
                return i - getRawX();
            } else if (modeX == 0) {
                return i / 2 - getRawX();
            }
            return this.getRawX();
        }

        public int getRenderY() {
            ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
            int i = scaledresolution.getScaledHeight();

            if (modeY == 1) {
                return i - getRawY();
            } else if (modeY == 0) {
                return i / 2 - getRawY();
            }
            return this.getRawY();
        }

        public int getRawX() {
            return x;
        }

        public void setRawX(int x) {
            this.x = x;
        }

        public int getRawY() {
            return y;
        }

        public void setRawY(int y) {
            this.y = y;
        }

        public void setSizeModeX(int int1) {
            modeX = int1;
        }

        public void setSizeModeY(int int1) {
            modeY = int1;
        }
    }
}