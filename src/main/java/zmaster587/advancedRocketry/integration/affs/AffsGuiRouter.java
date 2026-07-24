package zmaster587.advancedRocketry.integration.affs;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

/**
 * Routes GUI requests for the vendored Advanced Force Field System.
 *
 * <p>AFFS is folded into Advanced Rocketry's single mod container and can no longer
 * register its own {@link IGuiHandler} (FML keeps one handler per container, and
 * {@code openGui}/{@code registerGuiHandler} resolve the owner by mod instance).
 * AFFS therefore opens its GUIs through AR's instance with an id offset by
 * {@link #AFFS_GUI_BASE}; this router serves those ids from the AFFS handler (offset
 * removed) and lets every other id fall through to AR's own handler.</p>
 */
public class AffsGuiRouter implements IGuiHandler {

    /** Offset added to AFFS local GUI ids so they never collide with AR's own ids. */
    public static final int AFFS_GUI_BASE = 1000;

    private final IGuiHandler affs;
    private final IGuiHandler fallback;

    public AffsGuiRouter(IGuiHandler affs, IGuiHandler fallback) {
        this.affs = affs;
        this.fallback = fallback;
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id >= AFFS_GUI_BASE) {
            return affs == null ? null : affs.getServerGuiElement(id - AFFS_GUI_BASE, player, world, x, y, z);
        }
        return fallback == null ? null : fallback.getServerGuiElement(id, player, world, x, y, z);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id >= AFFS_GUI_BASE) {
            return affs == null ? null : affs.getClientGuiElement(id - AFFS_GUI_BASE, player, world, x, y, z);
        }
        return fallback == null ? null : fallback.getClientGuiElement(id, player, world, x, y, z);
    }
}
