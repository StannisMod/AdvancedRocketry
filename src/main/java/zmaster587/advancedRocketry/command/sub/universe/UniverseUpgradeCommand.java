package zmaster587.advancedRocketry.command.sub.universe;

import java.util.List;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.command.sub.ARCommand;
import zmaster587.advancedRocketry.item.ItemMemoryCrystal;
import zmaster587.advancedRocketry.navigation.CrystalEntry;
import zmaster587.advancedRocketry.universe.GalaxyGenConfig;
import zmaster587.advancedRocketry.universe.UniverseRegistry;
import zmaster587.advancedRocketry.universe.UniverseSchema;

/**
 * Move this world onto the model the pack and this build now state — deliberately, and only after
 * everything already seen has been frozen where it stands.
 *
 * <p><b>What it does, in order.</b> Every address anybody has written down is pinned first: the systems
 * already in the override store are immutable by construction, and every address on a memory crystal is
 * pinned here. Only then is the new stamp written. The result is a seam at the frontier of the
 * explored — charted space keeps its contents, unexplored space is re-derived under the new model —
 * and that seam is the player's own choice, which is why this is a command and not a migration that
 * runs itself at load.
 *
 * <p><b>What it cannot reach, and says so.</b> A crystal in a chest, in an unloaded chunk, or in the
 * inventory of a player who is offline is not readable from here. Bring the crystals that matter to
 * players who are online before running it.
 *
 * <p><b>What arrives without content.</b> Mechanics a newer model introduces do not retrofit into space
 * that is already frozen: a world upgraded halfway through a campaign keeps its charted systems exactly
 * as they were, and meets the new ones only further out. That belongs in a changelog, not in a fix.
 */
public class UniverseUpgradeCommand extends ARCommand {

    private static final String CONFIRM = "confirm";

    @Override
    public String getName() {
        return "upgrade";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.universe.upgrade.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {
        if (args.length > 1 || (args.length == 1 && !CONFIRM.equalsIgnoreCase(args[0]))) {
            throw wrongUsage(sender);
        }
        UniverseRegistry registry = UniverseRegistry.get(server);
        if (registry == null) {
            throw new CommandException("commands.advancedrocketry.universe.unavailable");
        }
        GalaxyGenConfig pack = UniverseRegistry.packGalaxyConfig();
        String target = UniverseRegistry.fingerprintOf(pack);

        if (args.length == 0) {
            sender.sendMessage(new TextComponentTranslation(
                    "commands.advancedrocketry.universe.upgrade.preview",
                    registry.configFingerprint(), target, registry.pinnedSystemCount()));
            sender.sendMessage(new TextComponentTranslation(
                    "commands.advancedrocketry.universe.upgrade.reach",
                    server.getPlayerList().getCurrentPlayerCount()));
            sender.sendMessage(new TextComponentTranslation(
                    "commands.advancedrocketry.universe.upgrade.confirm"));
            return;
        }

        int crystals = 0;
        int addresses = 0;
        int frozen = 0;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            for (ItemStack stack : carried(player)) {
                if (!ItemMemoryCrystal.isCrystal(stack)) {
                    continue;
                }
                crystals++;
                for (CrystalEntry entry : ItemMemoryCrystal.memoryOf(stack).list()) {
                    addresses++;
                    if (registry.pinSystem(entry.coord())) {
                        frozen++;
                    }
                }
            }
        }

        int wasVersion = registry.schemaVersion();
        UniverseSchema schema = registry.adoptSchema(pack);
        // A schema version can be moved here and now: this build carries the new one, so the world can
        // start deriving under it immediately rather than after a restart.
        UniverseRegistry.setGenerator(schema.generator(pack));
        // A CONFIGURATION change cannot be seen from inside a server that is running — a changed
        // <galaxyGen> stops the load before this command can be typed. So the permission is left here
        // for that load to spend.
        registry.armUpgrade();

        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.upgrade.done",
                crystals, addresses, frozen, wasVersion, schema.version(), target));
        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.upgrade.armed"));
        sender.sendMessage(new TextComponentTranslation(
                "commands.advancedrocketry.universe.upgrade.seam"));
    }

    /** Every stack a player has on him — held, worn, and in his ender chest. */
    private static Iterable<ItemStack> carried(EntityPlayerMP player) {
        List<ItemStack> all = new java.util.ArrayList<>();
        all.addAll(player.inventory.mainInventory);
        all.addAll(player.inventory.offHandInventory);
        all.addAll(player.inventory.armorInventory);
        for (int i = 0; i < player.getInventoryEnderChest().getSizeInventory(); i++) {
            all.add(player.getInventoryEnderChest().getStackInSlot(i));
        }
        return all;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          net.minecraft.util.math.BlockPos targetPos) {
        return args.length == 1
                ? getListOfStringsMatchingLastWord(args, CONFIRM)
                : java.util.Collections.<String>emptyList();
    }
}
