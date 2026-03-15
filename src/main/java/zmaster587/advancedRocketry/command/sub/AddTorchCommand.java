package zmaster587.advancedRocketry.command.sub;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.ARConfiguration;

import java.util.Arrays;
import java.util.List;

public class AddTorchCommand extends ARCommand {
    @Override
    public String getName() {
        return "addTorch";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("addtorch");
    }
    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.addtorch.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        Block block = Block.getBlockFromItem(player.getHeldItemMainhand().getItem());
        if (block == Blocks.AIR) {
            throw new CommandException("commands.advancedrocketry.addtorch.invalid");
        }
        if (ARConfiguration.getCurrentConfig().torchBlocks.contains(block)) {
            throw new CommandException("commands.advancedrocketry.addtorch.exists", block.getLocalizedName());
        }
        ARConfiguration.getCurrentConfig().addTorchblock(block);
        sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.addtorch.success", block.getLocalizedName()));
    }
}
