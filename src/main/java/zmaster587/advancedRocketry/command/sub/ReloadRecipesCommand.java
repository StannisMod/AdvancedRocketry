package zmaster587.advancedRocketry.command.sub;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.integration.CompatibilityMgr;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

public class ReloadRecipesCommand extends ARCommand {
    @Override
    public String getName() {
        return "reloadRecipes";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("reloadrecipes");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.reloadrecipes.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        try {
            AdvancedRocketry.machineRecipes.clearAllMachineRecipes();
            AdvancedRocketry.machineRecipes.registerAllMachineRecipes();
            // NB: do NOT call createAutoGennedRecipes here. It registers
            // ShapedOreRecipe objects into Forge's recipe registry, which is
            // frozen after startup, so a runtime reload throws "being added too
            // late". Auto-genned recipes are registered once at init and persist;
            // the runtime reload only needs to refresh machine + XML recipes.
            AdvancedRocketry.machineRecipes.registerXMLRecipes();

            sender.sendMessage(new TextComponentString("Recipes reloaded"));

            //CompatibilityMgr.reloadRecipes();
        } catch (Exception e) {
            e.printStackTrace();
            ITextComponent message = new TextComponentString("");
            IntStream.range(1, 4)
                    .boxed()
                    .map(i -> "commands.advancedrocketry.reloadrecipes.error" + i)
                    .map(TextComponentTranslation::new)
                    .forEach(message::appendSibling);
            sender.sendMessage(message);
        }
    }
}
