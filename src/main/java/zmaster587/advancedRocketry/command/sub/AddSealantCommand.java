package zmaster587.advancedRocketry.command.sub;

import net.minecraft.block.Block;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.util.SealableBlockHandler;

public class AddSealantCommand extends ARCommand {
    @Override
    public String getName() {
        return "addSealant";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.addsealant.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        Block block = Block.getBlockFromItem(player.getHeldItemMainhand().getItem());
        if (block == Blocks.AIR) {
            throw new CommandException("commands.advancedrocketry.addsealant.invalid");
        }
        if (SealableBlockHandler.INSTANCE.getOverriddenSealableBlocks().contains(block)) {
            throw new CommandException("commands.advancedrocketry.addsealant.exists", block.getLocalizedName());
        }
        ARConfiguration.getCurrentConfig().addSealedBlock(block);
        sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.addsealant.success", block.getLocalizedName()));
    }
}
