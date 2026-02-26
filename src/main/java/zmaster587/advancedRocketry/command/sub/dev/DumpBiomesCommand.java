package zmaster587.advancedRocketry.command.sub.dev;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.biome.Biome;
import zmaster587.advancedRocketry.command.sub.ARCommand;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DumpBiomesCommand extends ARCommand {
    @Override
    public String getName() {
        return "dumpBiomes";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.dev.dumpbiomes.usage";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 0) {
            throw wrongUsage(sender);
        }
        try {
            String fileName = "./BiomeDump.txt";
            Path path = Paths.get(fileName);
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(path)) {
                writer.append("ID\tResource name\n");
                for (Biome biome : Biome.REGISTRY) {
                    writer.append(String.valueOf(Biome.getIdForBiome(biome)))
                            .append("\t")
                            .append(biome.getRegistryName().toString())
                            .append("\n");
                }
            }
            sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.dev.dumpbiomes.success"));
        } catch (IOException e) {
            throw new CommandException(e.toString());
        }
    }

}
