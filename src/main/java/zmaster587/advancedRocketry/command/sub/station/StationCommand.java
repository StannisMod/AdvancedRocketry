package zmaster587.advancedRocketry.command.sub.station;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

public class StationCommand extends CommandTreeBase {
    public StationCommand() {
        addSubcommand(new CreateStationCommand());
        addSubcommand(new GiveStationCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "station";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.station.usage";
    }
}
