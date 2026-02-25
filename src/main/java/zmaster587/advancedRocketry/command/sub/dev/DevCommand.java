package zmaster587.advancedRocketry.command.sub.dev;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

public class DevCommand extends CommandTreeBase {
    public DevCommand() {
        addSubcommand(new DumpBiomesCommand());
        addSubcommand(new RunTestsCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "dev";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.dev.usage";
    }
}
