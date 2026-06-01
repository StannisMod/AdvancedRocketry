package zmaster587.advancedRocketry.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import zmaster587.advancedRocketry.entity.EntityRocket;
import zmaster587.advancedRocketry.tile.hatch.TileSatelliteHatch;
import zmaster587.advancedRocketry.tile.TileGuidanceComputer;
import zmaster587.libVulpes.LibVulpes;
import zmaster587.libVulpes.inventory.TextureResources;
import zmaster587.libVulpes.inventory.modules.IButtonInventory;
import zmaster587.libVulpes.inventory.modules.ModuleBase;
import zmaster587.libVulpes.inventory.modules.ModuleButton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RocketGuiNavigation {

    public static final int BUTTON_BACK_TO_ROCKET = 42000;

    private static final long RETURN_CONTEXT_TTL_TICKS = 20L * 60L; // 60 seconds

    private static final int DEFAULT_MODULAR_GUI_WIDTH = 176;
    private static final int BACK_BUTTON_SIZE = 18;
    private static final int BACK_BUTTON_MARGIN = 4;

    private static final int BACK_BUTTON_X =
            DEFAULT_MODULAR_GUI_WIDTH - BACK_BUTTON_MARGIN - BACK_BUTTON_SIZE;

    private static final int BACK_BUTTON_Y = BACK_BUTTON_MARGIN;

    private static final ResourceLocation[] BACK_BUTTON_TEXTURE =
            TextureResources.buttonBuild;

    private static final Map<UUID, ReturnContext> SERVER_CONTEXTS = new HashMap<>();

    private RocketGuiNavigation() {}

    public static void rememberIfRocketGuiReturnTile(EntityPlayer player, EntityRocket rocket, TileEntity tile) {
        if (player == null || rocket == null || tile == null) return;
        if (player.world == null || player.world.isRemote) return;

        if (isRocketGuiReturnTile(tile)) {
            remember(player, rocket, tile);
        }
    }

    private static boolean isRocketGuiReturnTile(TileEntity tile) {
        return tile instanceof TileGuidanceComputer
                || tile instanceof TileSatelliteHatch;
    }

    private static void remember(EntityPlayer player, EntityRocket rocket, TileEntity sourceTile) {
        if (rocket.world == null || sourceTile.getWorld() == null) return;

        ReturnContext ctx = new ReturnContext(
                rocket.world.provider.getDimension(),
                rocket.getEntityId(),
                sourceTile.getWorld().provider.getDimension(),
                sourceTile.getPos().toImmutable(),
                player.world.getTotalWorldTime() + RETURN_CONTEXT_TTL_TICKS
        );

        SERVER_CONTEXTS.put(player.getUniqueID(), ctx);
    }

    public static void addBackButtonIfApplicable(List<ModuleBase> modules, EntityPlayer player, IButtonInventory owner) {
        if (modules == null || owner == null) return;

        ModuleButton back = new ModuleButton(
                BACK_BUTTON_X,
                BACK_BUTTON_Y,
                BUTTON_BACK_TO_ROCKET,
                "<",
                owner,
                BACK_BUTTON_TEXTURE,
                LibVulpes.proxy.getLocalizedString("msg.guidanceComputer.backtorocket"),
                BACK_BUTTON_SIZE,
                BACK_BUTTON_SIZE
        );

        back.setColor(0x00FF00);
        modules.add(back);
    }

    public static boolean openRocketGuiFromReturnContext(
            EntityPlayerMP player,
            int sourceTileDimensionId,
            BlockPos sourceTilePos
    ) {
        if (player == null || player.getServer() == null || sourceTilePos == null) return false;

        ReturnContext ctx = getValidContext(player);
        if (ctx == null) return false;

        if (!ctx.matchesSourceTile(sourceTileDimensionId, sourceTilePos)) return false;

        int dimensionId = ctx.dimensionId;
        int rocketEntityId = ctx.rocketEntityId;

        if (player.world.provider.getDimension() != dimensionId) return false;

        WorldServer world = player.getServer().getWorld(dimensionId);
        if (world == null) return false;

        Entity entity = world.getEntityByID(rocketEntityId);
        if (!(entity instanceof EntityRocket)) return false;

        EntityRocket rocket = (EntityRocket) entity;

        if (rocket.isDead) return false;
        if (rocket.storage == null) return false;
        if (rocket.getDistance(player) >= 64) return false;

        SERVER_CONTEXTS.remove(player.getUniqueID());

        rocket.openGui(player);
        return true;
    }

    private static ReturnContext getValidContext(EntityPlayerMP player) {
        ReturnContext ctx = SERVER_CONTEXTS.get(player.getUniqueID());

        if (ctx == null) return null;

        if (player.world.getTotalWorldTime() > ctx.expiresAtWorldTime) {
            SERVER_CONTEXTS.remove(player.getUniqueID());
            return null;
        }

        return ctx;
    }

    private static final class ReturnContext {
        private final int dimensionId;
        private final int rocketEntityId;
        private final int sourceTileDimensionId;
        private final BlockPos sourceTilePos;
        private final long expiresAtWorldTime;

        private ReturnContext(
                int dimensionId,
                int rocketEntityId,
                int sourceTileDimensionId,
                BlockPos sourceTilePos,
                long expiresAtWorldTime
        ) {
            this.dimensionId = dimensionId;
            this.rocketEntityId = rocketEntityId;
            this.sourceTileDimensionId = sourceTileDimensionId;
            this.sourceTilePos = sourceTilePos;
            this.expiresAtWorldTime = expiresAtWorldTime;
        }

        private boolean matchesSourceTile(int dimensionId, BlockPos pos) {
            return this.sourceTileDimensionId == dimensionId
                    && this.sourceTilePos.equals(pos);
        }
    }
}