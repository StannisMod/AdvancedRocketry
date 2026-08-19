package zmaster587.advancedRocketry.command.sub.universe;

import net.minecraft.command.ICommandSender;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

/**
 * Operator commands for the world model a save was generated under: what it is, and how to move a
 * world onto a newer one deliberately.
 */
public class UniverseCommand extends CommandTreeBase {

    public UniverseCommand() {
        addSubcommand(new UniverseStatusCommand());
        addSubcommand(new UniverseUpgradeCommand());
        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "universe";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.universe.usage";
    }
}
