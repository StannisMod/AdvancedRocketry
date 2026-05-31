package zmaster587.advancedRocketry.command.sub.redirect;

import com.google.common.base.Preconditions;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
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

            WorldInfo worldinfo = world.getWorldInfo();

            if ("clear".equalsIgnoreCase(args[0])) {
                // Check if clear weather is allowed
                if (props.getRainMarker() == 1 || props.getThunderMarker() == 1) {
                    notifyCommandListener(sender, this, "commands.weather.always_not_clear");
                    return;
                }

                worldinfo.setCleanWeatherTime(i);
                worldinfo.setRainTime(0);
                worldinfo.setThunderTime(0);
                worldinfo.setRaining(false);
                worldinfo.setThundering(false);
                notifyCommandListener(sender, this, "commands.weather.clear");
            } else if ("rain".equalsIgnoreCase(args[0])) {
                // Check if raining is allowed
                if (props.getRainMarker() == -1) {
                    notifyCommandListener(sender, this, "commands.weather.cannot_rain");
                    return;
                }

                worldinfo.setCleanWeatherTime(0);
                worldinfo.setRainTime(i);
                worldinfo.setThunderTime(i);
                worldinfo.setRaining(true);
                worldinfo.setThundering(false);
                notifyCommandListener(sender, this, "commands.weather.rain");
            } else {
                if (!"thunder".equalsIgnoreCase(args[0])) {
                    throw new WrongUsageException("commands.weather.usage");
                }
                // Check if thunder is allowed
                if (props.getThunderMarker() == -1) {
                    notifyCommandListener(sender, this, "commands.weather.cannot_thunder");
                    return;
                }

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

    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "clear", "rain", "thunder");
        }
        return Collections.emptyList();
    }
}
