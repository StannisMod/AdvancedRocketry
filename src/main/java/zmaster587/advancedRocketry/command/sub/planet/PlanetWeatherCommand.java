package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import org.apache.commons.lang3.math.NumberUtils;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.world.weather.PlanetWeatherManager;
import zmaster587.advancedRocketry.world.weather.PlanetWeatherState;

/**
 * {@code /ar planet weather [dimId]} — prints a planet's weather profile (the
 * static markers + acidic flag) together with its live state and the tick
 * countdown to the next rain/thunder change. The profile values themselves are
 * also editable through {@code /ar planet get|set <dim> rainMarker|...}; this
 * command exists so a player can read the runtime state, which is not stored on
 * {@link DimensionProperties}.
 */
public class PlanetWeatherCommand extends ARCommand {

    @Override
    public String getName() {
        return "weather";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.weather.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        int dimId = args.length >= 1 && NumberUtils.isParsable(args[0])
                ? parseInt(args[0])
                : sender.getEntityWorld().provider.getDimension();

        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Dimension", dimId);
        }

        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);

        float density = props.getAtmosphereDensity();
        float threshold = ARConfiguration.getCurrentConfig().minAtmosphereDensityForRain;
        boolean canRain = density >= threshold;

        send(sender, "=== Weather: " + props.getName() + " (dim " + dimId + ") ===");
        send(sender, "Profile: rain=" + markerWord(props.getRainMarker())
                + ", thunder=" + markerWord(props.getThunderMarker())
                + ", acidic=" + (props.isAcidicRain() ? "yes" : "no"));
        send(sender, "Atmosphere: density " + (int) density + "/100 — rain "
                + (canRain ? "allowed" : "suppressed") + " (min " + (int) threshold + ")");

        PlanetWeatherState state = PlanetWeatherManager.getOrCreate(server, dimId);
        if (state == null) {
            send(sender, "Now: <state unavailable — overworld not loaded>");
            return;
        }

        String now = state.isThundering() ? "thunderstorm" : (state.isRaining() ? "raining" : "clear");
        send(sender, "Now: " + now);
        send(sender, "Next change: rain in " + state.getRainTime() + "t, thunder in "
                + state.getThunderTime() + "t");
    }

    private static String markerWord(int marker) {
        if (marker > 0) return "always";
        if (marker < 0) return "never";
        return "dynamic";
    }

    private static void send(ICommandSender sender, String line) {
        sender.sendMessage(new TextComponentString(line));
    }
}
