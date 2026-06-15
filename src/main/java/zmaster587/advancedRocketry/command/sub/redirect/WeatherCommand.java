package zmaster587.advancedRocketry.command.sub.redirect;

import com.google.common.base.Preconditions;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.world.provider.WorldProviderPlanet;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class WeatherCommand extends ARCommand {
    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.weather.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 1 && args.length <= 2) {
            int i = (300 + (new Random()).nextInt(600)) * 20;

            if (args.length == 2) {
                i = parseInt(args[1], 1, 1000000) * 20;
            }

            if (!(sender.getEntityWorld().provider instanceof WorldProviderPlanet)) {
                throw new WrongUsageException("commands.advancedrocketry.weather.invalid", sender.getEntityWorld().provider.getDimension());
            }

            World world = sender.getEntityWorld();
            DimensionProperties props = ((WorldProviderPlanet) world.provider).getDimensionProperties();
            Preconditions.checkNotNull(props);

            // A planet whose atmosphere is below the rain threshold can never
            // hold precipitation: WorldProviderPlanet.updateWeather() forces it
            // clear every tick, so refuse rain/thunder up front instead of
            // "succeeding" and being silently reverted on the next tick.
            final boolean canRain = props.getAtmosphereDensity()
                    >= ARConfiguration.getCurrentConfig().minAtmosphereDensityForRain;

            WorldInfo worldinfo = world.getWorldInfo();

            String action = args[0].toLowerCase(java.util.Locale.ROOT);
            if (!"clear".equals(action) && !"rain".equals(action) && !"thunder".equals(action)) {
                throw new WrongUsageException("commands.weather.usage");
            }

            // Refuse up front anything WorldProviderPlanet.updateWeather() would
            // revert next tick (a forcing marker, or — for rain/thunder — an
            // atmosphere too thin to hold precipitation), instead of "succeeding"
            // and being silently undone.
            String refusal = weatherRefusalKey(action, props.getRainMarker(),
                    props.getThunderMarker(), canRain);
            if (refusal != null) {
                notifyCommandListener(sender, this, refusal);
                return;
            }

            if ("clear".equals(action)) {
                worldinfo.setCleanWeatherTime(i);
                worldinfo.setRainTime(0);
                worldinfo.setThunderTime(0);
                worldinfo.setRaining(false);
                worldinfo.setThundering(false);
                notifyCommandListener(sender, this, "commands.weather.clear");
            } else if ("rain".equals(action)) {
                worldinfo.setCleanWeatherTime(0);
                worldinfo.setRainTime(i);
                worldinfo.setThunderTime(i);
                worldinfo.setRaining(true);
                worldinfo.setThundering(false);
                notifyCommandListener(sender, this, "commands.weather.rain");
            } else { // thunder
                worldinfo.setCleanWeatherTime(0);
                worldinfo.setRainTime(i);
                worldinfo.setThunderTime(i);
                worldinfo.setRaining(true);
                worldinfo.setThundering(true);
                notifyCommandListener(sender, this, "commands.weather.thunder");
            }
        } else {
            throw new WrongUsageException("commands.weather.usage");
        }
    }

    /**
     * Whether {@code /advancedrocketry weather <action>} can take effect on a
     * planet, or would be reverted next tick by
     * {@link WorldProviderPlanet#updateWeather()}. Returns the lang key of the
     * refusal message, or {@code null} if the action is allowed. Pure function of
     * the planet's weather markers + the atmosphere can-rain gate, so it is
     * unit-tested directly ({@code WeatherCommandRefusalTest}).
     *
     * <ul>
     *   <li><b>clear</b> — refused when a marker forces rain/thunder always-on;</li>
     *   <li><b>rain</b> — refused when the rain marker is "never" ({@code -1}) or
     *       the atmosphere is too thin to hold rain;</li>
     *   <li><b>thunder</b> — refused when the thunder marker is "never", or when
     *       rain itself is impossible (rain marker "never" or thin atmosphere),
     *       since {@code updateWeather} clears thunder whenever it is not raining.</li>
     * </ul>
     */
    public static String weatherRefusalKey(String action, int rainMarker, int thunderMarker, boolean canRain) {
        if ("clear".equalsIgnoreCase(action)) {
            return (rainMarker == 1 || thunderMarker == 1) ? "commands.weather.always_not_clear" : null;
        }
        if ("rain".equalsIgnoreCase(action)) {
            if (rainMarker == -1) return "commands.weather.cannot_rain";
            if (!canRain) return "commands.weather.cannot_rain_atmosphere";
            return null;
        }
        if ("thunder".equalsIgnoreCase(action)) {
            if (thunderMarker == -1) return "commands.weather.cannot_thunder";
            if (rainMarker == -1 || !canRain) return "commands.weather.cannot_thunder_norain";
            return null;
        }
        return null;
    }

    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "clear", "rain", "thunder");
        }
        return Collections.emptyList();
    }
}
