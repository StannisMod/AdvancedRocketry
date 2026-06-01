package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

public class PlanetCommand extends CommandTreeBase {
    public PlanetCommand() {
        addSubcommand(new PlanetResetCommand());
        addSubcommand(new PlanetListCommand());
        addSubcommand(new PlanetDeleteCommand());
        addSubcommand(new PlanetGenerateCommand());
        addSubcommand(new PlanetSetCommand());
        addSubcommand(new PlanetGetCommand());
        addSubcommand(new PlanetWeatherCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "planet";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.usage";
    }
}
