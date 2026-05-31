package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.network.PacketDimInfo;
import zmaster587.libVulpes.network.PacketHandler;

public class PlanetDeleteCommand extends ARCommand {
    @Override
    public String getName() {
        return "delete";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.delete.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        int dimId = parseInt(args[0]);
        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Planet with id", dimId);
        }
        WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(dimId);
        if (world == null || world.playerEntities.isEmpty()) {
            DimensionManager.getInstance().deleteDimension(dimId);
            PacketHandler.sendToAll(new PacketDimInfo(dimId, null));
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.delete.success", dimId));
        } else {
            //If the world still has players abort and list players
            ITextComponent message = new TextComponentTranslation("commands.advancedrocketry.planet.delete.invalid");
            for (EntityPlayer player : world.playerEntities) {
                message.appendText("\n");
                message.appendSibling(player.getDisplayName());
            }
            sender.sendMessage(message);
        }
    }
}
