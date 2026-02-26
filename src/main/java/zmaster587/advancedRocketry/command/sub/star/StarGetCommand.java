package zmaster587.advancedRocketry.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public class StarGetCommand extends ARCommand {
    @Override
    public String getName() {
        return "get";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.star.get.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw wrongUsage(sender);
        }
        String prop = args[0];
        int starId = parseInt(args[1]);
        StellarBody star = DimensionManager.getInstance().getStar(starId);
        if (star == null)
            throw invalidValue("Star", starId);
        else {
            StarCommand.ActionType action = StarCommand.ActionType.byName(prop);
            if (action != null) {
                action.get(sender, star);
            }
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        String[] possible = Arrays.stream(StarCommand.ActionType.values())
                .map(action -> action.name)
                .toArray(String[]::new);
        return getListOfStringsMatchingLastWord(args, possible);
    }
}
