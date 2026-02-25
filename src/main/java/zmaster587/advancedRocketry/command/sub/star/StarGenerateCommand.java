package zmaster587.advancedRocketry.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.network.PacketStellarInfo;
import zmaster587.libVulpes.network.PacketHandler;

public class StarGenerateCommand extends ARCommand {
    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.star.generate.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 4) {
            throw wrongUsage(sender);
        }
        String name = args[0];
        int temp = parseInt(args[1]);
        int x = parseInt(args[2]);
        int z = parseInt(args[3]);
        StellarBody star = new StellarBody();
        star.setTemperature(temp);
        star.setPosX(x);
        star.setPosZ(z);
        star.setName(name);
        star.setId(DimensionManager.getInstance().getNextFreeStarId());
        if (star.getId() != -1) {
            DimensionManager.getInstance().addStar(star);
            PacketHandler.sendToAll(new PacketStellarInfo(star.getId(), star));
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.generate.success"));
        } else {
            throw new CommandException("commands.advancedrocketry.star.generate.invalid");
        }
    }
}
