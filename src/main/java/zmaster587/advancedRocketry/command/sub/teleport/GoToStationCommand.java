package zmaster587.advancedRocketry.command.sub.teleport;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.world.util.BasicTeleporter;
import zmaster587.libVulpes.util.HashedBlockPosition;

public class GoToStationCommand extends ARCommand {
    @Override
    public String getName() {
        return "station";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.goto.station.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        int dim = ARConfiguration.getCurrentConfig().spaceDimId;
        int stationId = parseInt(args[0]);
        ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(stationId);

        if (spaceObject != null) {
            if (player.world.provider.getDimension() != ARConfiguration.getCurrentConfig().spaceDimId) {
                player.changeDimension(dim, new BasicTeleporter(player.getPosition()));
            }
            HashedBlockPosition vec = spaceObject.getSpawnLocation();
            player.setPositionAndUpdate(vec.x, vec.y, vec.z);
        } else {
            throw invalidValue(getName(), dim);
        }
    }
}
