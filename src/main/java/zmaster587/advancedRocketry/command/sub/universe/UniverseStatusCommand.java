package zmaster587.advancedRocketry.command.sub.universe;

import java.util.List;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.universe.UniverseSchemas;

/**
 * What world model this save runs on, what the pack currently states, and how much of the universe has
 * already been frozen by being seen.
 *
 * <p>Read-only, and the first thing to run when a load has been refused: it names both sides of the
 * comparison that refused it.</p>
 */
public class UniverseStatusCommand extends ARCommand {

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.universe.status.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        UniverseRegistry registry = UniverseRegistry.get(server);
        if (registry == null) {
            throw new CommandException("commands.advancedrocketry.universe.unavailable");
        }
        GalaxyGenConfig pack = UniverseRegistry.packGalaxyConfig();
        String packFingerprint = UniverseRegistry.fingerprintOf(pack);

        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.status.schema",
                registry.schemaVersion(), UniverseSchemas.CURRENT));
        UniverseRegistry.activeSchema().ifPresent(schema -> sender.sendMessage(
                new TextComponentTranslation(schema.isStable()
                        ? "commands.advancedrocketry.universe.status.stable"
                        : "commands.advancedrocketry.universe.status.alpha", schema.label())));
        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.status.config",
                registry.configFingerprint(), packFingerprint));
        sender.sendMessage(new TextComponentTranslation(
                registry.configFingerprint().equals(packFingerprint)
                        ? "commands.advancedrocketry.universe.status.agrees"
                        : "commands.advancedrocketry.universe.status.differs"));
        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.status.frozen", registry.pinnedSystemCount()));
        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.status.released",
                UniverseSchemas.released().toString()));
        if (registry.isUpgradeArmed()) {
            sender.sendMessage(new TextComponentTranslation(
                    "commands.advancedrocketry.universe.status.armed"));
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          net.minecraft.util.math.BlockPos targetPos) {
        return java.util.Collections.emptyList();
    }
}
