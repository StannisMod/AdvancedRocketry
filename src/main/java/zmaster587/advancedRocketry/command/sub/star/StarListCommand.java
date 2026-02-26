package zmaster587.advancedRocketry.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;

public class StarListCommand extends ARCommand {
    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.star.list.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        for (StellarBody star : DimensionManager.getInstance().getStars()) {
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.list.entry",
                    star.getId(), star.getName(), star.getNumPlanets()));
        }
    }
}
