package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

public class PlanetGenerateCommand extends ARCommand {
    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.planet.generate.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1 || args.length > 10) {
            throw wrongUsage(sender);
        }
        int starId = parseInt(args[0]);
        // Offset beginning after the id
        int modOffset = 1;
        boolean moon = false;
        boolean gas = false;
        if (args.length > modOffset && args[modOffset].equalsIgnoreCase("moon")) {
            modOffset++;
            moon = true;
            if (!DimensionManager.getInstance().isDimensionCreated(starId)) {
                throw invalidValue("Planet with id", starId);
            }
        } else if (DimensionManager.getInstance().getStar(starId) == null) {
            throw invalidValue("Star with id", starId);
        }

        if (args.length > modOffset && args[modOffset].equalsIgnoreCase("gas")) {
            modOffset++;
            gas = true;
        }

        // First 3 args are randomness, last 3 args are base value
        boolean randArgs = args.length == modOffset + 1 + 3;
        boolean fullArgs = args.length == modOffset + 1 + 6;
        if (randArgs || fullArgs) {
            int planetId = starId;
            if (moon) {
                starId = DimensionManager.getInstance().getDimensionProperties(planetId).getStarId();
                // The moon branch skips the non-moon star-existence guard (see the
                // else-if above), then feeds this re-derived starId to generateRandom
                // (which dereferences getStar) and to getStar(...).removePlanet below
                // — both NPE if the parent planet's star id resolves to no star.
                // Fail with a clean command error instead, mirroring the non-moon guard.
                if (DimensionManager.getInstance().getStar(starId) == null) {
                    throw invalidValue("Star with id", starId);
                }
            }
            DimensionProperties props;
            int argsOffset = modOffset;
            if (gas) {
                if (randArgs) {
                    // Defaults are from DimensionManager#generateRandomPlanets()
                    props = DimensionManager.getInstance().generateRandomGasGiant(starId, args[argsOffset++],
                            150, 180, 125,
                            parseInt(args[argsOffset++]), parseInt(args[argsOffset++]), parseInt(args[argsOffset]));
                } else {
                    // Method params are flipped...
                    String name = args[argsOffset++];
                    int atmosphereFactor = parseInt(args[argsOffset++]);
                    int distanceFactor = parseInt(args[argsOffset++]);
                    int gravityFactor = parseInt(args[argsOffset++]);
                    props = DimensionManager.getInstance().generateRandomGasGiant(starId, name,
                            parseInt(args[argsOffset++]), parseInt(args[argsOffset++]), parseInt(args[argsOffset]),
                            atmosphereFactor, distanceFactor, gravityFactor);
                }
            } else {
                if (randArgs) {
                    props = DimensionManager.getInstance().generateRandom(starId, args[argsOffset++],
                            parseInt(args[argsOffset++]), parseInt(args[argsOffset++]), parseInt(args[argsOffset]));
                } else {
                    // Method params are flipped...
                    String name = args[argsOffset++];
                    int atmosphereFactor = parseInt(args[argsOffset++]);
                    int distanceFactor = parseInt(args[argsOffset++]);
                    int gravityFactor = parseInt(args[argsOffset++]);
                    props = DimensionManager.getInstance().generateRandom(starId, name,
                            parseInt(args[argsOffset++]), parseInt(args[argsOffset++]), parseInt(args[argsOffset]),
                            atmosphereFactor, distanceFactor, gravityFactor);
                }
            }
            if (props == null) {
                throw new CommandException("commands.advancedrocketry.planet.generate.invalid", args[modOffset]);
            } else {
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.generate.success", args[modOffset]));
            }

            // If [moon] specified, the generated dim should be a moon orbiting planetId instead of a planet orbiting starId.
            if (moon) {
                props.setParentPlanet(DimensionManager.getInstance().getDimensionProperties(planetId));
                DimensionManager.getInstance().getStar(starId).removePlanet(props);
            }
        } else {
            throw wrongUsage(sender);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "moon", "gas");
        }
        if (args.length == 3) {
            return Collections.singletonList("gas");
        }
        return Collections.emptyList();
    }
}
