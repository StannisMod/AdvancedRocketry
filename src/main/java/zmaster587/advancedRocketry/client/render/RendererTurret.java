package zmaster587.advancedRocketry.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import zmaster587.advancedRocketry.api.AdvancedRocketryBlocks;
import zmaster587.advancedRocketry.tile.weapon.TileTurret;

/**
 * Draws the barrel a turret cannot have as blocks.
 *
 * <h3>Why the barrel is drawn rather than built</h3>
 * <p>A block occupies a grid cell at one of a handful of fixed orientations, so a gun made of blocks
 * cannot point at anything that is not on those axes. The mount's bearing is therefore a pair of
 * angles the game keeps, and this renderer is the only thing that turns them into something a player
 * can see. The parts a player builds are the gun's EQUIPMENT — feed, cooling, barrel sections that
 * lengthen it — and the barrel drawn here is as long as the build earned.</p>
 *
 * <h3>It draws the client's own mechanism</h3>
 * <p>The client runs the same traverse the server does, from the command it was sent, so the barrel
 * swings at the declared rate instead of teleporting between synced poses. When the two disagree the
 * next command corrects the client silently — nothing here is authoritative for anything.</p>
 */
@SideOnly(Side.CLIENT)
public class RendererTurret extends TileEntitySpecialRenderer<TileTurret> {

    /** Barrel thickness, as a fraction of a block. */
    private static final float BORE = 0.42F;

    @Override
    public void render(TileTurret turret, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        if (turret == null || !turret.getSpec().isOperable() && turret.getBarrelLength() <= 1) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 0.5D, y + 0.5D, z + 0.5D);
        // Minecraft's yaw is clockwise from south, which is the opposite sense to a GL rotation about
        // +Y; the negation is that difference and nothing more.
        GlStateManager.rotate((float) -turret.getMechanism().getYaw(), 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate((float) turret.getMechanism().getPitch(), 1.0F, 0.0F, 0.0F);

        int length = turret.getBarrelLength();
        BlockPos pos = turret.getPos();
        int brightness = turret.getWorld() == null ? 0xF000F0
                : turret.getWorld().getCombinedLight(pos.up(), 0);
        GlStateManager.scale(BORE, BORE, 1.0F);
        for (int segment = 0; segment < length; segment++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(-0.5D, -0.5D, segment + 0.5D);
            Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlockBrightness(
                    AdvancedRocketryBlocks.blockGunBarrel.getDefaultState(),
                    brightnessToFloat(brightness));
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    /**
     * A block's own model is drawn at a brightness, not at a light level. Feeding the packed value
     * straight in would light every barrel as if it were in the sun.
     */
    private static float brightnessToFloat(int combinedLight) {
        int block = (combinedLight >> 4) & 0xF;
        int sky = (combinedLight >> 20) & 0xF;
        return Math.max(0.25F, Math.max(block, sky) / 15.0F);
    }
}
