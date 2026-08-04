package zmaster587.advancedRocketry.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandTime;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Keeps {@code /time set} and {@code /time add} off the worlds whose skip is locked
 * ({@link TimeSkipPolicy}).
 *
 * <p><b>Why the command needs a guard of its own at all.</b> Vanilla's {@code /time} is not a
 * per-world command: {@code CommandTime.setAllWorldTimes} walks {@code server.worlds} and writes
 * every loaded one, so a player standing on the overworld moves every planet's clock with him and a
 * player standing on a planet does the same in reverse. There is no argument to say which world he
 * meant, so the policy has to be applied per world inside the loop — which means replacing the
 * loop.</p>
 *
 * <p><b>It applies, it does not refuse.</b> The command runs and does what it is allowed to do; the
 * worlds it may not touch are left alone and NAMED, in one line. A refusal would be the easier
 * implementation and the worse one: an all-worlds command that fails entirely because one of its
 * targets is locked would be a command a player cannot use at all once he has a base off-world.</p>
 *
 * <p>{@code /time query} is read-only and is never intercepted. Neither is a run in which no loaded
 * world is locked — there the vanilla command is exactly right and this handler stands aside, so the
 * common case pays nothing and behaves byte-for-byte as before.</p>
 *
 * <p>Registered on the Forge event bus beside the other world handlers; the interception seam is the
 * same one {@code /weather} already uses.</p>
 */
public final class TimeCommandGuard {

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!(event.getCommand() instanceof CommandTime)) {
            return;
        }
        String[] args = event.getParameters();
        if (args.length < 2) {
            return; // usage error, or `query` - vanilla's business
        }
        String verb = args[0].toLowerCase(Locale.ROOT);
        boolean isSet = "set".equals(verb);
        if (!isSet && !"add".equals(verb)) {
            return; // `query` and anything unrecognised go to vanilla untouched
        }
        ICommandSender sender = event.getSender();
        MinecraftServer server = sender == null ? null : sender.getServer();
        if (server == null) {
            return;
        }

        List<WorldServer> allowed = new ArrayList<>();
        List<WorldServer> locked = new ArrayList<>();
        for (WorldServer world : server.worlds) {
            if (world == null) {
                continue;
            }
            (TimeSkipPolicy.allows(world) ? allowed : locked).add(world);
        }
        if (locked.isEmpty()) {
            return; // nothing to protect: let vanilla run, unchanged
        }

        Integer amount = parseAmount(isSet, args[1]);
        if (amount == null) {
            return; // let vanilla produce its own usage error rather than invent one
        }

        event.setCanceled(true);
        for (WorldServer world : allowed) {
            world.setWorldTime(isSet ? (long) amount : world.getWorldTime() + (long) amount);
        }
        sender.sendMessage(new TextComponentTranslation("msg.timeskip.command.partial",
                allowed.size(), locked.size()));
    }

    /**
     * The value {@code /time set|add} is being given, or {@code null} when it is not one this guard
     * can apply — in which case the command is left to vanilla, error message and all.
     *
     * <p>The four named times are vanilla's own ({@code CommandTime}); they are duplicated here
     * because the guard replaces the write loop and therefore has to know what was asked for. A
     * negative or unparseable value is deliberately NOT handled: vanilla's own bounds check produces
     * the message a player expects, and inventing a second one would make the two disagree.</p>
     */
    private static Integer parseAmount(boolean isSet, String arg) {
        if (isSet) {
            String named = arg.toLowerCase(Locale.ROOT);
            if ("day".equals(named)) {
                return 1000;
            }
            if ("night".equals(named)) {
                return 13000;
            }
            if ("noon".equals(named)) {
                return 6000;
            }
            if ("midnight".equals(named)) {
                return 18000;
            }
        }
        try {
            int value = Integer.parseInt(arg);
            return value < 0 ? null : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
