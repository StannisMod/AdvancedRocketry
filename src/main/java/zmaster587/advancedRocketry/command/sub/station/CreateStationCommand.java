package zmaster587.advancedRocketry.command.sub.station;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.api.AdvancedRocketryItems;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.item.ItemStationChip;
import zmaster587.advancedRocketry.stations.SpaceObjectManager;
import zmaster587.advancedRocketry.stations.SpaceStationObject;
import zmaster587.advancedRocketry.world.util.BasicTeleporter;
import zmaster587.libVulpes.util.HashedBlockPosition;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class CreateStationCommand extends ARCommand {
    @Override
    public String getName() {
        return "create";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.station.create.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1 || args.length > 3) {
            throw wrongUsage(sender);
        }
        int orbitDimId = parseInt(args[1]);
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(orbitDimId);
        if (orbitDimId != Constants.INVALID_PLANET &&
                props == DimensionManager.overworldProperties && orbitDimId != props.getId()) {
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.station.create.tip"));
            throw new CommandException("commands.advancedrocketry.station.create.invalid", orbitDimId);
        }
        // Optional player + tp flag parsing
        EntityPlayerMP player = null;
        int idx = 2;

        if (args.length > idx && !args[idx].equalsIgnoreCase("tp")) {
            player = getPlayer(server, sender, args[idx]);
            idx++;
        }
        if (player == null) {
            player = getCommandSenderAsPlayer(sender);
        }

        boolean teleport = (args.length > idx && args[idx].equalsIgnoreCase("tp"));
        // Create + register station
        SpaceStationObject station = new SpaceStationObject();

        // MUST be true BEFORE registerSpaceObject sends PacketSpaceStationInfo
        station.beginTransition(0); // created=true

        SpaceObjectManager.getSpaceManager().registerSpaceObject(station, orbitDimId); // now the packet is correct

        int stationId = station.getId();
        HashedBlockPosition spawn = station.getSpawnLocation();

        // Ensure space world exists
        int spaceDim = ARConfiguration.getCurrentConfig().spaceDimId;
        if (net.minecraftforge.common.DimensionManager.getWorld(spaceDim) == null) {
            net.minecraftforge.common.DimensionManager.initDimension(spaceDim);
        }
        WorldServer spaceWorld = server.getWorld(spaceDim);

        // Load chunk and build a 3x3 cobble platform under spawn
        BlockPos spawnPos = new BlockPos(spawn.x, spawn.y, spawn.z);
        spaceWorld.getChunkFromBlockCoords(spawnPos);

        BlockPos base = spawnPos.down();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                spaceWorld.setBlockState(base.add(dx, 0, dz), Blocks.COBBLESTONE.getDefaultState(), 2);
            }
        }
        // Ensure the spawn block is clear
        spaceWorld.setBlockState(spawnPos, Blocks.AIR.getDefaultState(), net.minecraftforge.common.util.Constants.BlockFlags.DEFAULT);

        // Give a station chip
        ItemStack chip = new ItemStack(AdvancedRocketryItems.itemSpaceStationChip);
        ItemStationChip.setUUID(chip, stationId);
        player.inventory.addItemStackToInventory(chip);

        sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.station.create.success",
                stationId, orbitDimId, spawn.x, spawn.y, spawn.z));

        // Optional teleport
        if (teleport && player.world.provider.getDimension() != spaceDim) {
            player.changeDimension(spaceDim, new BasicTeleporter(spawnPos));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 3) {
            return Collections.singletonList("tp");
        } if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return args.length == 2 && index == 2;
    }
}
