package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;

public class PlanetListCommand extends ARCommand {
    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.list.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        ITextComponent message = new TextComponentTranslation("commands.advancedrocketry.planet.list.dimensions");
        for (int i : DimensionManager.getInstance().getRegisteredDimensions()) {
            message.appendText("\n");
            message.appendSibling(new TextComponentTranslation("commands.advancedrocketry.planet.list.entry",
                    i, DimensionManager.getInstance().getDimensionProperties(i).getName()));
        }
        sender.sendMessage(message);
    }
}
