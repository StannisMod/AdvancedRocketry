package zmaster587.advancedRocketry.command.sub.planet;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.universe.BodyProfile;
import zmaster587.advancedRocketry.universe.PlanetDerivation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * {@code /ar planet generate <starId|planetId> [moon] <name>} — mint one world in a system.
 *
 * <p><b>It derives, it does not roll.</b> This command used to front the legacy random generator:
 * three "randomness" arguments fed {@code new Random(System.currentTimeMillis())}, so the same command
 * on the same world produced a different planet every time and the mod carried two world-making
 * models that answered the same question differently. The randomness arguments are gone with the
 * model behind them, and the world now comes from the ONE derivation everything else uses
 * ({@link PlanetDerivation}), keyed on the star and on how many worlds it already has — so running
 * this twice on a fresh world of the same seed gives the same two planets, in the same order.</p>
 *
 * <p>What survives unchanged: the name is the operator's, the {@code moon} form parents the new world
 * on an existing planet, and exactly one dimension is registered per invocation.</p>
 */
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
        if (args.length < 2 || args.length > 3) {
            throw wrongUsage(sender);
        }
        int id = parseInt(args[0]);
        int offset = 1;
        boolean moon = args.length == 3 && args[offset].equalsIgnoreCase("moon");
        if (moon) {
            offset++;
        }
        if (offset >= args.length) {
            throw wrongUsage(sender);
        }
        String name = args[offset];

        int starId;
        DimensionProperties parent = null;
        if (moon) {
            parent = DimensionManager.getInstance().getDimensionProperties(id);
            if (parent == null || !DimensionManager.getInstance().isDimensionCreated(id)) {
                throw invalidValue("Planet with id", id);
            }
            starId = parent.getStarId();
            // The parent's star must exist before anything is derived from it: the derivation reads
            // the star's own physics, and a planet whose star id resolves to nothing would otherwise
            // fail deep inside it rather than here, where the operator can read why.
            if (DimensionManager.getInstance().getStar(starId) == null) {
                throw invalidValue("Star with id", starId);
            }
        } else {
            starId = id;
            if (DimensionManager.getInstance().getStar(starId) == null) {
                throw invalidValue("Star with id", starId);
            }
        }

        StellarBody star = DimensionManager.getInstance().getStar(starId);
        // The index is how many worlds this star already holds, so a second call derives a DIFFERENT
        // world rather than the same one again — and the sequence is reproducible on a fresh world.
        int index = star.getNumPlanets();
        GalacticCoord anchor = GalacticCoord.ofSectorLocal(starId, 0L, 0L, 0L, 0L, 0L);
        int orbit = PlanetDerivation.orbitalDistanceOf(server.getWorld(0).getSeed(), anchor, index,
                Math.max(1, index + 1), star);
        BodyProfile profile = PlanetDerivation.derive(server.getWorld(0).getSeed(), anchor, anchor,
                index, star, moon, orbit);

        int dimId = DimensionManager.getInstance().getNextFreeDim(2);
        DimensionProperties props = new DimensionProperties(dimId);
        props.setName(name);
        props.setStar(star);
        props.orbitalDist = orbit;
        props.setBulk(profile.massEarths(), profile.radiusEarths());
        props.gravitationalMultiplier = profile.gravityPercent() / 100f;
        props.setAtmosphereDensityDirect(profile.pressure());
        props.averageTemperature = profile.temperatureKelvin();
        props.initDefaultAttributes();
        if (moon) {
            props.setParentPlanet(parent);
        }
        if (!DimensionManager.getInstance().registerDim(props, true)) {
            throw new CommandException("commands.advancedrocketry.planet.generate.invalid", name);
        }
        sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.planet.generate.success", name));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        return Collections.emptyList();
    }
}
