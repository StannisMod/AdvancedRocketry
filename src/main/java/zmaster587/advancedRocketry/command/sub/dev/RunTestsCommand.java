package zmaster587.advancedRocketry.command.sub.dev;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.unit.IngameTestOrchestrator;

public class RunTestsCommand extends ARCommand {
    @Override
    public String getName() {
        return "runTests";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.dev.runtests.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (!IngameTestOrchestrator.registered) {
            MinecraftForge.EVENT_BUS.register(IngameTestOrchestrator.instance);
        }
        IngameTestOrchestrator.runTests(player.getEntityWorld(), player);
    }
}
