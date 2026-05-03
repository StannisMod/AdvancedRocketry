package zmaster587.advancedRocketry.command.sub;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.DataStorage;
import zmaster587.advancedRocketry.item.IDataItem;
import zmaster587.advancedRocketry.item.ItemAsteroidChip;
import zmaster587.advancedRocketry.item.ItemMultiData;

import javax.annotation.Nullable;
import java.util.*;

public class FillDataCommand extends ARCommand {
    private static final int ASTEROID_CHIP_FILL_AMOUNT = 1000;

    private static final EnumSet<DataStorage.DataType> ASTEROID_CHIP_DATA_TYPES =
            EnumSet.of(
                    DataStorage.DataType.COMPOSITION,
                    DataStorage.DataType.MASS,
                    DataStorage.DataType.DISTANCE
            );

    @Override
    public String getName() {
        return "fillData";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("filldata", "fd");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.filldata.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);

        if (args.length == 1 && "chip".equalsIgnoreCase(args[0])) {
            if (stack.isEmpty() || !(stack.getItem() instanceof ItemAsteroidChip)) {
                throw new CommandException("commands.advancedrocketry.filldata.chip.notheld");
            }

            ItemAsteroidChip item = (ItemAsteroidChip) stack.getItem();

            for (DataStorage.DataType dataType : ASTEROID_CHIP_DATA_TYPES) {
                item.setData(stack, ASTEROID_CHIP_FILL_AMOUNT, dataType);
            }

            sender.sendMessage(new TextComponentTranslation(
                    "commands.advancedrocketry.filldata.chip.success",
                    ASTEROID_CHIP_FILL_AMOUNT
            ));
            return;
        }

        if (args.length != 2) {
            throw wrongUsage(sender);
        }

        if (!stack.isEmpty() && (stack.getItem() instanceof IDataItem || stack.getItem() instanceof ItemMultiData)) {
            DataStorage.DataType dataType;

            try {
                dataType = DataStorage.DataType.valueOf(args[0].toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.filldata.invalid"));
                StringJoiner joiner = new StringJoiner(", ");
                Arrays.stream(DataStorage.DataType.values())
                        .filter(data -> !data.name().equals("UNDEFINED"))
                        .map(data -> data.name().toLowerCase())
                        .forEach(joiner::add);
                sender.sendMessage(new TextComponentString(joiner.toString()));
                throw wrongUsage(sender);
            }

            int dataAmount = parseInt(args[1]);

            if (stack.getItem() instanceof IDataItem) {
                IDataItem item = (IDataItem) stack.getItem();
                item.setData(stack, dataAmount, dataType);
            } else if (stack.getItem() instanceof ItemMultiData) {
                ItemMultiData item = (ItemMultiData) stack.getItem();
                item.setData(stack, dataAmount, dataType);
            }

            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.filldata.success"));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            List<String> possible = new ArrayList<>();
            possible.add("chip");

            Arrays.stream(DataStorage.DataType.values())
                    .filter(data -> !data.name().equals("UNDEFINED"))
                    .map(data -> data.name().toLowerCase())
                    .forEach(possible::add);

            return getListOfStringsMatchingLastWord(args, possible);
        }

        return Collections.emptyList();
    }
}
