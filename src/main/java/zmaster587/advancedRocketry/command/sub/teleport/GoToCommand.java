package zmaster587.advancedRocketry.command.sub.teleport;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.stations.ISpaceObject;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.world.util.TeleporterNoPortal;
import zmaster587.advancedRocketry.world.util.TeleporterNoPortalSeekBlock;
import zmaster587.libVulpes.util.HashedBlockPosition;

public class GoToCommand extends CommandTreeBase {
    public GoToCommand() {
        addSubcommand(new GoToDimensionCommand());
        addSubcommand(new GoToStationCommand());
        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "goto";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.goto.usage";
    }

    public static class GoToDimensionCommand extends CommandBase {
        @Override
        public String getName() {
            return "dimension";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "commands.advancedrocketry.goto.dimension.usage";
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length != 1) {
                throw new WrongUsageException(getUsage(sender));
            }
            Entity entity = sender.getCommandSenderEntity();
            if (!(entity instanceof EntityPlayerMP)) {
                throw new WrongUsageException(getUsage(sender));
            }
            EntityPlayerMP player = (EntityPlayerMP) entity;
            String id = args[0];
            if (!NumberUtils.isParsable(id)) {
                throw new WrongUsageException("commands.advancedrocketry.notnumeric", id);
            }
            int dim = NumberUtils.toInt(id);
            if (DimensionManager.isDimensionRegistered(dim)) {
                if (DimensionManager.getWorld(dim) == null) {
                    DimensionManager.initDimension(dim);
                }
                sender.getServer().getPlayerList().transferPlayerToDimension(player, dim, new TeleporterNoPortalSeekBlock(DimensionManager.getWorld(dim)));
            } else {
                throw new WrongUsageException("commands.advancedrocketry.invaliddest", id, StringUtils.capitalize(getName()));
            }
        }
    }

    public static class GoToStationCommand extends CommandBase {
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
                throw new WrongUsageException(getUsage(sender));
            }
            Entity entity = sender.getCommandSenderEntity();
            if (!(entity instanceof EntityPlayerMP)) {
                throw new WrongUsageException(getUsage(sender));
            }
            EntityPlayerMP player = (EntityPlayerMP) entity;
            String id = args[0];
            if (!NumberUtils.isParsable(id)) {
                throw new WrongUsageException("commands.advancedrocketry.notnumeric", id);
            }
            int dim = ARConfiguration.getCurrentConfig().spaceDimId;
            int stationId = NumberUtils.toInt(id);
            ISpaceObject spaceObject = SpaceObjectManager.getSpaceManager().getSpaceStation(stationId);

            if (spaceObject != null) {
                if (player.world.provider.getDimension() != ARConfiguration.getCurrentConfig().spaceDimId) {
                    player.getServer().getPlayerList().transferPlayerToDimension(player, dim, new TeleporterNoPortal((WorldServer) player.world));
                }
                HashedBlockPosition vec = spaceObject.getSpawnLocation();
                player.setPositionAndUpdate(vec.x, vec.y, vec.z);
            } else {
                throw new WrongUsageException("commands.advancedrocketry.invaliddest", id, StringUtils.capitalize(getName()));
            }
        }
    }
}
