package zmaster587.advancedRocketry.command.sub.planet;

import com.google.common.primitives.Primitives;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.math.NumberUtils;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.network.PacketDimInfo;
import zmaster587.libVulpes.network.PacketHandler;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class PlanetSetCommand extends ARCommand {
    @Override
    public String getName() {
        return "set";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.set.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw wrongUsage(sender);
        }
        String propName;
        int dimId;
        int argsOffset;
        // Parse dimension id
        if (NumberUtils.isParsable(args[0])) {
            dimId = parseInt(args[0]);
            if (args.length < 2) {
                throw wrongUsage(sender);
            }
            propName = args[1];
            argsOffset = 2;
        } else {
            dimId = sender.getEntityWorld().provider.getDimension();
            propName = args[0];
            argsOffset = 1;
        }
        // Validate AR dimension
        if (!DimensionManager.getInstance().isDimensionCreated(dimId)) {
            throw invalidValue("Dimension", dimId);
        }
        DimensionProperties props = DimensionManager.getInstance().getDimensionProperties(dimId);
        if (propName.equalsIgnoreCase("atmosphereDensity")) {
            int atmosphereDensity = parseInt(args[argsOffset]);
            props.setAtmosphereDensityDirect(atmosphereDensity);
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.set.success",
                    dimId, propName, atmosphereDensity));
            return;
        }

        // Generate property setter
        DimensionProperties.PropLookup lookup = new DimensionProperties.PropLookup(props);
        MethodHandle propSetter;
        try {
            propSetter = lookup.getPropertySetter(propName);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new CommandException("commands.advancedrocketry.planet.set.invalid");
        }
        if (propSetter == null) {
            throw invalidValue("Field", propName);
        }

        MethodType type = propSetter.type();
        Class<?> propType = type.parameterType(type.parameterCount() - 1);
        try {
            if (propType.isArray()) {
                // Parse arg value
                MethodHandle propGetter = lookup.getPropertyGetter(propName);
                if (propGetter == null) {
                    throw invalidValue("Field", propName);
                }
                String[] arrayArgs = Arrays.copyOfRange(args, argsOffset, args.length);
                int propArrLength = Array.getLength(propGetter.invoke(props));
                Object[] propValues = parseArrayArgs(arrayArgs, propType, propArrLength, propName);
                // Set array property
                propSetter.invoke(props, ArrayUtils.toPrimitive(propValues));
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.set.success",
                        dimId, propName, Arrays.toString(propValues)));
            } else {
                // Parse arg value
                Object propValue = parseArg(args[argsOffset], propType, propName);
                // Set property
                propSetter.invoke(props, propValue);
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.set.success",
                        dimId, propName, propValue));
            }
        } catch (Throwable e) {
            if (e instanceof CommandException) {
                throw (CommandException) e;
            }
            e.printStackTrace();
            throw new CommandException("commands.advancedrocketry.planet.set.invalid");
        }
        PacketHandler.sendToAll(new PacketDimInfo(dimId, props));
    }

    private Object parseArg(String arg, Class<?> propTypeIn, String propName) throws CommandException {
        // Parse directly as String
        if (propTypeIn.equals(String.class)) {
            return arg;
        }
        Class<?> propType = Primitives.wrap(propTypeIn);
        // Parse boolean
        if (propType.equals(Boolean.class)) {
            Boolean asBool = BooleanUtils.toBooleanObject(arg);
            if (asBool != null) {
                return asBool;
            }
        }
        // Parse number
        else if (Number.class.isAssignableFrom(propType) && NumberUtils.isParsable(arg)) {
            return NumberUtils.createNumber(arg);
        }
        // Property is unsupported type or arg is wrong type
        throw new CommandException("commands.advancedrocketry.planet.set.mismatch",
                propName, propTypeIn.getSimpleName(), arg);
    }

    private Object[] parseArrayArgs(String[] args, Class<?> propTypeIn, int expectedLength, String propName) throws CommandException {
        if (args.length != expectedLength) {
            throw new CommandException("commands.advancedrocketry.planet.set.wronglength", expectedLength, args.length);
        }
        // Parse directly as String array
        if (propTypeIn.equals(String[].class)) {
            return args;
        }
        Class<?> propType = Primitives.wrap(propTypeIn.getComponentType());
        // Parse boolean
        if (propType.equals(Boolean.class)) {
            Boolean[] asBools = Arrays.stream(args)
                    .map(BooleanUtils::toBooleanObject)
                    .filter(Objects::nonNull)
                    .toArray(Boolean[]::new);
            if (asBools.length == expectedLength) {
                return asBools;
            }
        }
        // Parse number
        if (Number.class.isAssignableFrom(propType)) {
            Object[] asNumbers = Arrays.stream(args)
                    .filter(NumberUtils::isParsable)
                    .map(NumberUtils::createNumber)
                    .filter(propType::isInstance)
                    .map(propType::cast)
                    .toArray(length -> (Object[]) Array.newInstance(propType, length));
            if (asNumbers.length == expectedLength) {
                return asNumbers;
            }
        }
        // Property is unsupported type or one of the args is wrong type
        throw new CommandException("commands.advancedrocketry.planet.set.mismatch",
                propName, propTypeIn.getSimpleName(), Arrays.toString(args));
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
            return getListOfStringsMatchingLastWord(args, DimensionProperties.PropLookup.getPropertyNames(true));
        }
        return Collections.emptyList();
    }
}
