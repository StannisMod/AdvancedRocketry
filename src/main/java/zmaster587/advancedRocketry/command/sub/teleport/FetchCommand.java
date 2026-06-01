package zmaster587.advancedRocketry.command.sub.teleport;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.world.util.BasicTeleporter;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class FetchCommand extends ARCommand {
    @Override
    public String getName() {
        return "fetch";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.fetch.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        EntityPlayer destPlayer = getCommandSenderAsPlayer(sender);
        EntityPlayer otherPlayer = getPlayer(server, sender, args[0]);

        otherPlayer.changeDimension(destPlayer.dimension, new BasicTeleporter(destPlayer.getPosition()));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1;
    }
}
