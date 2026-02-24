package zmaster587.advancedRocketry.command;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;
import zmaster587.advancedRocketry.command.sub.teleport.GoToCommand;
import zmaster587.advancedRocketry.command.sub.weather.WeatherCommand;

import java.util.ArrayList;
import java.util.List;

public class ARCommand extends CommandTreeBase {
    private final List<String> aliases;

    public ARCommand() {
        aliases = new ArrayList<>();
        aliases.add("advancedrocketry_n");
        aliases.add("advrocketry_n");
        aliases.add("ar_n");

        addSubcommand(new GoToCommand());
        addSubcommand(new WeatherCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "advancedrocketry_n";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/advancedrocketry_n [subcommand]";
    }

    @Override
    public List<String> getAliases() {
        return aliases;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
