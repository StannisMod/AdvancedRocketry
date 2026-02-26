package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import org.apache.commons.lang3.math.NumberUtils;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PlanetGetCommand extends ARCommand {
    @Override
    public String getName() {
        return "get";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.get.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw wrongUsage(sender);
        }
        String propName;
        int dimId;
        if (NumberUtils.isParsable(args[0])) {
            dimId = parseInt(args[0]);
            if (args.length < 2) {
                throw wrongUsage(sender);
            }
            propName = args[1];
        } else {
            dimId = sender.getEntityWorld().provider.getDimension();
            propName = args[0];
        }
        // Validate AR dimension
        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Dimension", dimId);
        }
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);
        DimensionProperties.PropLookup lookup = new DimensionProperties.PropLookup(props);
        String propValue;
        try {
            MethodHandle propGetter = lookup.getPropertyGetter(propName);
            if (propGetter == null) {
                throw invalidValue("Field", propName);
            }
            Object rawPropValue = propGetter.invoke(props);
            if (rawPropValue == null) {
                propValue = null;
            } else if (rawPropValue.getClass().isArray()) {
                propValue = Arrays.toString(boxedArray(rawPropValue));
            } else {
                propValue = rawPropValue.toString();
            }
        } catch (Throwable e) {
            if (e instanceof CommandException) {
                throw (CommandException) e;
            }
            e.printStackTrace();
            throw new CommandException("Field lookup failed");
        }
        sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.get.success",
                propName, propValue));
    }

    private Object[] boxedArray(Object array) {
        if (array instanceof Object[]) {
            return (Object[]) array;
        }
        int length = Array.getLength(array);
        Object[] wrapped = new Object[length];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = Array.get(array, i);
        }
        return wrapped;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        int dimId;
        if (args.length == 1) {
            dimId = sender.getEntityWorld().provider.getDimension();
        } else if (args.length == 2 && NumberUtils.isParsable(args[0])) {
            dimId = NumberUtils.toInt(args[0]);
        } else {
            return Collections.emptyList();
        }
        // Validate AR dimension
        if (DimensionManager.getInstance().isDimensionCreated(dimId)) {
            return getListOfStringsMatchingLastWord(args, DimensionProperties.PropLookup.getPropertyNames(false));
        }
        return Collections.emptyList();
    }
}
