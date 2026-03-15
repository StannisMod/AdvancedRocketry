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
import zmaster587.advancedRocketry.item.ItemMultiData;

import javax.annotation.Nullable;
import java.util.*;

public class FillDataCommand extends ARCommand {
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
        if (args.length != 2) {
            throw wrongUsage(sender);
        }
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        ItemStack stack = player.getHeldItem(EnumHand.MAIN_HAND);
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
            String[] possible = Arrays.stream(DataStorage.DataType.values())
                    .filter(data -> !data.name().equals("UNDEFINED"))
                    .map(data -> data.name().toLowerCase())
                    .toArray(String[]::new);
            return getListOfStringsMatchingLastWord(args, possible);
        }
        return Collections.emptyList();
    }
}
