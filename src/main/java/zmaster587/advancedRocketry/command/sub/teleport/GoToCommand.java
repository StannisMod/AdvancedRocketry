package zmaster587.advancedRocketry.command.sub.teleport;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

public class GoToCommand extends CommandTreeBase {
    public GoToCommand() {
        addSubcommand(new GoToDimensionCommand());
        addSubcommand(new GoToStationCommand());
        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "goto";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.goto.usage";
    }
}
