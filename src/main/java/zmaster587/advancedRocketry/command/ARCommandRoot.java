package zmaster587.advancedRocketry.command;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;
import zmaster587.advancedRocketry.command.sub.AddTorchCommand;
import zmaster587.advancedRocketry.command.sub.FillDataCommand;
import zmaster587.advancedRocketry.command.sub.ReloadRecipesCommand;
import zmaster587.advancedRocketry.command.sub.SetGravityCommand;
import zmaster587.advancedRocketry.command.sub.dev.DevCommand;
import zmaster587.advancedRocketry.command.sub.planet.PlanetCommand;
import zmaster587.advancedRocketry.command.sub.redirect.WeatherCommand;
import zmaster587.advancedRocketry.command.sub.star.StarCommand;
import zmaster587.advancedRocketry.command.sub.station.StationCommand;
import zmaster587.advancedRocketry.command.sub.teleport.FetchCommand;
import zmaster587.advancedRocketry.command.sub.teleport.GoToCommand;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class ARCommandRoot extends CommandTreeBase {
    private final List<String> aliases;

    public ARCommandRoot() {
        aliases = new ArrayList<>();
        aliases.add("advancedrocketry");
        aliases.add("advrocketry");
        aliases.add("ar");

        addSubcommand(new WeatherCommand());
        addSubcommand(new AddTorchCommand());
        addSubcommand(new ReloadRecipesCommand());
        addSubcommand(new SetGravityCommand());
        addSubcommand(new FetchCommand());
        addSubcommand(new PlanetCommand());
        addSubcommand(new StarCommand());
        addSubcommand(new StationCommand());
        addSubcommand(new GoToCommand());
        addSubcommand(new FillDataCommand());
        addSubcommand(new DevCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "advancedrocketry";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/advancedrocketry [subcommand]";
    }

    @Override
    public List<String> getAliases() {
        return aliases;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    public static String[] shiftArgs(@Nullable String[] s, int shift)
    {
        if(s == null || s.length - shift <= 0)
        {
            return new String[0];
        }

        String[] s1 = new String[s.length - shift];
        System.arraycopy(s, shift, s1, 0, s1.length);
        return s1;
    }
}
