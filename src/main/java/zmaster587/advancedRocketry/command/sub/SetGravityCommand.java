package zmaster587.advancedRocketry.command.sub;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.api.AdvancedRocketryAPI;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class SetGravityCommand extends ARCommand {
    @Override
    public String getName() {
        return "setGravity";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("setgravity");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.setgravity.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1 || args.length > 2) {
            throw wrongUsage(sender);
        }
        Entity entity;
        if (args.length == 2) {
            entity = getPlayer(server, sender, args[1]);
        } else {
            entity = sender.getCommandSenderEntity();
        }
        if (entity == null) {
            throw wrongUsage(sender);
        }
        double multiplier = parseDouble(args[0]);
        if (multiplier == 0.0D) {
            AdvancedRocketryAPI.gravityManager.clearGravityEffect(entity);
        } else {
            AdvancedRocketryAPI.gravityManager.setGravityMultiplier(entity, multiplier);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return args.length == 2 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 2;
    }
}
