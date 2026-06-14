package zmaster587.advancedRocketry.world.weather;

import net.minecraft.command.CommandWeather;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;

import java.util.StringJoiner;

/**
 * Three responsibilities:
 *
 * <ol>
 *   <li><b>Wrap fallback.</b> {@link MixinWorldServerMulti} is the primary wrap
 *       point (constructor RETURN), but at that point the world's provider may
 *       still be null and our AR-planet check can't run. {@link WorldEvent.Load}
 *       fires after the provider is installed and {@code init()} has run, so
 *       it catches every world the Mixin route missed. {@code wrapWorldInfoIfNeeded}
 *       is idempotent so running both paths is safe.</li>
 *   <li><b>Player sync.</b> Vanilla auto-syncs weather on join, but only the
 *       overworld's state — switching dims doesn't refresh the rain strength on
 *       the client, and respawn re-uses the join-time snapshot. The three
 *       explicit syncs below cover the gaps and make the client-visible
 *       weather match the wrapped {@link net.minecraft.world.storage.WorldInfo}
 *       of whichever dimension the player is actually in.</li>
 *   <li><b>{@code /weather} redirect.</b> Vanilla {@code CommandWeather}
 *       hard-codes {@code server.worlds[0]} — run on a planet it silently
 *       mutates the OVERWORLD and leaves the planet untouched. Redirect it to
 *       the per-dimension {@code /advancedrocketry weather} when the sender
 *       stands on an AR planet.</li>
 * </ol>
 */
public final class PlanetWeatherEventHandler {

    @SubscribeEvent
    public void redirectWeatherCommand(CommandEvent event) {
        if (!(event.getCommand() instanceof CommandWeather)) return;
        ICommandSender sender = event.getSender();
        if (!(sender.getEntityWorld().provider instanceof WorldProviderPlanet)) return;
        MinecraftServer server = sender.getServer();
        if (server == null) return;

        StringJoiner redirected = new StringJoiner(" ");
        redirected.add("advancedrocketry").add("weather");
        for (String param : event.getParameters()) {
            redirected.add(param);
        }

        event.setCanceled(true);
        server.getCommandManager().executeCommand(sender, redirected.toString());
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldServer) {
            PlanetWeatherManager.wrapWorldInfoIfNeeded((WorldServer) event.getWorld());
        }
    }

    // The three player-event syncs below are now belt-and-suspenders rather
    // than load-bearing: MixinPlayerList already fixes vanilla's buggy
    // updateTimeAndWeatherForPlayer (state code 1 vs 2 swap), so the client
    // sees the correct weather state immediately on join/dim-change. These
    // re-broadcasts catch the edges where the wrapped state changed between
    // vanilla's initial sync and the moment the player is "in" the new world.

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PlanetWeatherManager.syncToPlayer((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PlanetWeatherManager.syncToPlayer((EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PlanetWeatherManager.syncToPlayer((EntityPlayerMP) event.player);
        }
    }
}
