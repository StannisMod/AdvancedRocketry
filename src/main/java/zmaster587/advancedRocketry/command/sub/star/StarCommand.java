package zmaster587.advancedRocketry.command.sub.star;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;

import javax.annotation.Nullable;

public class StarCommand extends CommandTreeBase {
    public StarCommand() {
        addSubcommand(new StarListCommand());
        addSubcommand(new StarGetCommand());
        addSubcommand(new StarSetCommand());
        addSubcommand(new StarGenerateCommand());

        addSubcommand(new CommandTreeHelp(this));
    }

    @Override
    public String getName() {
        return "star";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.advancedrocketry.star.usage";
    }

    enum ActionType {
        TEMP("temp") {
            @Override
            void get(ICommandSender sender, StellarBody star) {
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.temp.get", star.getTemperature()));
            }

            @Override
            void set(ICommandSender sender, StellarBody star, String[] args) throws CommandException {
                star.setTemperature(parseInt(args[0]));
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.temp.set", star.getTemperature()));
            }
        },
        PLANETS("planets") {
            @Override
            void get(ICommandSender sender, StellarBody star) {
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.planets.get"));
                for (IDimensionProperties planets : star.getPlanets()) {
                    sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.planets.get.entry",
                            planets.getId(), planets.getName()));
                }
            }

            @Override
            void set(ICommandSender sender, StellarBody star, String[] args) {
                throw new UnsupportedOperationException();
            }
        },
        POS("pos") {
            @Override
            void get(ICommandSender sender, StellarBody star) {
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.pos.get",
                        star.getPosX(), star.getPosZ()));
            }

            @Override
            void set(ICommandSender sender, StellarBody star, String[] args) throws CommandException {
                int x = parseInt(args[0]);
                int z = parseInt(args[1]);
                star.setPosX(x);
                star.setPosZ(z);
                sender.sendMessage(new TextComponentTranslation("commands.advancedrocketry.star.action.pos.set",
                        star.getPosX(), star.getPosZ()));
            }
        };

        final String name;

        ActionType(String name) {
            this.name = name;
        }

        @Nullable
        static ActionType byName(String nameIn)
        {
            for (ActionType actionType : values())
            {
                if (actionType.name.equals(nameIn))
                {
                    return actionType;
                }
            }

            return null;
        }

        WrongUsageException wrongUsageSet() {
            return new WrongUsageException("commands.advancedrocketry.star.set." + name + ".usage");
        }

        abstract void get(ICommandSender sender, StellarBody star) throws CommandException;
        abstract void set(ICommandSender sender, StellarBody star, String[] args) throws CommandException;
    }
}
