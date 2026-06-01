package zmaster587.advancedRocketry.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.command.ARCommandRoot;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

public class StarSetCommand extends ARCommand {
    @Override
    public String getName() {
        return "set";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.star.set.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || args.length >= 4) {
            throw wrongUsage(sender);
        }
        StarCommand.ActionType action = StarCommand.ActionType.byName(args[0]);
        // star set <temp:pos>
        if (action == null) {
            throw wrongUsage(sender);
        } else if (args.length == 1) {
            throw action.wrongUsageSet();
        }
        int starId = parseInt(args[1]);
        StellarBody star = DimensionManager.getInstance().getStar(starId);
        if (star == null)
            throw invalidValue("Star", starId);
        else {
            String[] propArgs = ARCommandRoot.shiftArgs(args, 2);
            if (action == StarCommand.ActionType.TEMP && propArgs.length != 1
                    || action == StarCommand.ActionType.POS && propArgs.length != 2) {
                throw action.wrongUsageSet();
            }
            action.set(sender, star, propArgs);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        String[] possible = Arrays.stream(new StarCommand.ActionType[]{StarCommand.ActionType.TEMP, StarCommand.ActionType.POS})
                .map(action -> action.name)
                .toArray(String[]::new);
        return getListOfStringsMatchingLastWord(args, possible);
    }
}
