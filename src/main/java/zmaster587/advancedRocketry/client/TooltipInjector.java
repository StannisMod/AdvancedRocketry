package zmaster587.advancedRocketry.client;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.ARConfiguration;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry;
import zmaster587.advancedRocketry.api.fuel.FuelRegistry.FuelType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Constants.modId, value = Side.CLIENT)
public final class TooltipInjector {

    private TooltipInjector() {}

    /** Maps exact registry IDs -> base tooltip lang key */
    private static final Map<String, String> KEY_BY_ID = new HashMap<>();
    /** Fallback: maps the unlocalized-name tail -> base tooltip lang key */
    private static final Map<String, String> KEY_BY_SUFFIX = new HashMap<>();
    /** Optional: dynamic args for formatted lines (usually used in .alt.2) */
    @FunctionalInterface interface ArgProvider { Object[] get(ItemStack s); }
    private static final Map<String, ArgProvider> ARGS_BY_BASEKEY = new HashMap<>();
    /** For items that need per-stack (e.g., meta) keys */
    private static final Map<String, java.util.function.Function<ItemStack, String>> KEY_RESOLVER_BY_ID = new HashMap<>();


    static {
        // ---- CO2 Scrubber / Oxygen Vent ---- ----
        KEY_BY_ID.put("advancedrocketry:oxygenscrubber", "tooltip.advancedrocketry.scrubber");
        KEY_BY_ID.put("advancedrocketry:oxygenvent",  "tooltip.advancedrocketry.oxygenvent");

        ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.oxygenvent",
                s -> new Object[] { ARConfiguration.getCurrentConfig().oxygenVentSize });

        KEY_BY_ID.put("advancedrocketry:carbonscrubbercartridge", "tooltip.advancedrocketry.scrubbercart");
        KEY_BY_ID.put("libvulpes:linker", "tooltip.advancedrocketry.libvulpes.linker");


        // ---- Structure Tower ----
        KEY_BY_ID.put("advancedrocketry:structuretower", "tooltip.advancedrocketry.structuretower");
        KEY_BY_ID.put("libvulpes:structuremachine", "tooltip.libvulpes.structuremachine");
        KEY_BY_ID.put("libvulpes:advstructuremachine", "tooltip.libvulpes.advstructuremachine");


        // --- ItemUpgrade (meta-based) 6 Space Suit Components---
        KEY_RESOLVER_BY_ID.put("advancedrocketry:itemupgrade",
                s -> "tooltip.advancedrocketry.itemupgrade." + s.getItemDamage());
        KEY_RESOLVER_BY_ID.put("advancedrocketry:item_upgrade",
                s -> "tooltip.advancedrocketry.itemupgrade." + s.getItemDamage());

        // ---- Guidance Computer ----
        KEY_BY_ID.put("advancedrocketry:guidancecomputer", "tooltip.advancedrocketry.guidancecomputer");
        KEY_BY_ID.put("advancedrocketry:servicemonitor", "tooltip.advancedrocketry.servicemonitor");
        KEY_BY_ID.put("advancedrocketry:servicestation", "tooltip.advancedrocketry.servicestation");
        KEY_BY_ID.put("advancedrocketry:oxygencharger", "tooltip.advancedrocketry.oxygencharger");


        // --- Station Controllers 
        KEY_BY_ID.put("advancedrocketry:orientationcontroller", "tooltip.advancedrocketry.orientationctrl");
        KEY_BY_ID.put("advancedrocketry:gravitycontroller",     "tooltip.advancedrocketry.gravityctrl");
        KEY_BY_ID.put("advancedrocketry:altitudecontroller",    "tooltip.advancedrocketry.altitudectrl");


        KEY_BY_ID.put("advancedrocketry:smallairlockdoor", "tooltip.advancedrocketry.smallairlock");
        KEY_BY_ID.put("advancedrocketry:planetselector",       "tooltip.advancedrocketry.planetselector");
        KEY_BY_ID.put("advancedrocketry:planetholoselector",   "tooltip.advancedrocketry.planetholoselector");
        KEY_BY_ID.put("advancedrocketry:circlelight", "tooltip.advancedrocketry.circlelight");
        KEY_BY_ID.put("advancedrocketry:monitoringstation",  "tooltip.advancedrocketry.monitoringstation");
        KEY_BY_ID.put("advancedrocketry:satellitebuilder", "tooltip.advancedrocketry.satellitebuilder");
        KEY_BY_ID.put("advancedrocketry:satellitecontrolcenter",   "tooltip.advancedrocketry.satellitecontrolcenter");


        // --- Satellite Primary Function (metas 0..6)
        KEY_RESOLVER_BY_ID.put("advancedrocketry:satelliteprimaryfunction", s -> {
            switch (s.getMetadata() & 7) {
                case 0: return "tooltip.advancedrocketry.satfunc.optical";
                case 1: return "tooltip.advancedrocketry.satfunc.composition";
                case 2: return "tooltip.advancedrocketry.satfunc.mass";
                case 3: return "tooltip.advancedrocketry.satfunc.microwave";
                case 4: return "tooltip.advancedrocketry.satfunc.oremapping";
                case 5: return "tooltip.advancedrocketry.satfunc.biomechanger";
                case 6: return "tooltip.advancedrocketry.satfunc.weather";
                default: return null;
            }
        });
        // camelCase fallback
        KEY_RESOLVER_BY_ID.put("advancedrocketry:satellitePrimaryFunction",
            KEY_RESOLVER_BY_ID.get("advancedrocketry:satelliteprimaryfunction"));

        // --- Satellite Power Source (metas 0..1)
        KEY_RESOLVER_BY_ID.put("advancedrocketry:satellitepowersource", s -> {
            switch (s.getMetadata() & 1) {
                case 0: return "tooltip.advancedrocketry.satpower.0"; // Basic solar
                case 1: return "tooltip.advancedrocketry.satpower.1"; // Advanced solar
                default: return null;
            }
        });
        KEY_RESOLVER_BY_ID.put("advancedrocketry:satellitePowerSource",
        KEY_RESOLVER_BY_ID.get("advancedrocketry:satellitepowersource"));

        // ---- LibVulpes Battery (meta 0..1) ----
        KEY_RESOLVER_BY_ID.put("libvulpes:battery", s -> "tooltip.libvulpes.battery." + (s.getMetadata() & 1));

        // ---- ID Chips / Chips ----
        KEY_BY_ID.put("advancedrocketry:satelliteidchip",   "tooltip.advancedrocketry.satidchip");
        KEY_BY_ID.put("advancedrocketry:planetidchip",      "tooltip.advancedrocketry.planetidchip");
        KEY_BY_ID.put("advancedrocketry:stationchip",       "tooltip.advancedrocketry.stationchip");
        KEY_BY_ID.put("advancedrocketry:spacestationchip",  "tooltip.advancedrocketry.stationchip");
        KEY_BY_ID.put("advancedrocketry:elevatorchip",      "tooltip.advancedrocketry.elevatorchip");
        KEY_BY_ID.put("advancedrocketry:asteroidchip",      "tooltip.advancedrocketry.asteroidchip");


        // ---- Energy multiblocks ----
        KEY_BY_ID.put("advancedrocketry:blackholegenerator", "tooltip.advancedrocketry.blackholegen");
        KEY_BY_ID.put("advancedrocketry:microwavereciever",  "tooltip.advancedrocketry.microwavereceiver");
        KEY_BY_ID.put("advancedrocketry:solarpanel",  "tooltip.advancedrocketry.solarpanel");
        KEY_BY_ID.put("advancedrocketry:solararray",         "tooltip.advancedrocketry.solararray");
        KEY_BY_ID.put("advancedrocketry:solararraypanel",    "tooltip.advancedrocketry.solararraypanel");

        // Advanced Data Bus
        KEY_BY_ID.put("advancedrocketry:databusbig",    "tooltip.advancedrocketry.databusbig");
        // ---- BlockARHatch (registered as advancedrocketry:loader), meta 0..6 ----
        KEY_RESOLVER_BY_ID.put("advancedrocketry:loader", s -> {
            final int v = s.getMetadata() & 7; // strip redstone/state bit
            switch (v) {
                case 0: return "tooltip.advancedrocketry.hatch.databus";
                case 1: return "tooltip.advancedrocketry.hatch.satellite";
                case 2: return "tooltip.advancedrocketry.hatch.item_unloader";
                case 3: return "tooltip.advancedrocketry.hatch.item_loader";
                case 4: return "tooltip.advancedrocketry.hatch.fluid_unloader";
                case 5: return "tooltip.advancedrocketry.hatch.fluid_loader";
                case 6: return "tooltip.advancedrocketry.hatch.gca";
                default: return null;
            }
        });

        // ---- Processing / Machines / Multiblocks----
        KEY_BY_ID.put("advancedrocketry:arcfurnace", "tooltip.advancedrocketry.arcfurnace");
        KEY_BY_ID.put("advancedrocketry:rollingmachine",     "tooltip.advancedrocketry.rollingmachine");
        KEY_BY_ID.put("advancedrocketry:lathe",              "tooltip.advancedrocketry.lathe");
        KEY_BY_ID.put("advancedrocketry:crystallizer",       "tooltip.advancedrocketry.crystallizer");
        KEY_BY_ID.put("advancedrocketry:cuttingmachine",     "tooltip.advancedrocketry.cuttingmachine");
        KEY_BY_ID.put("advancedrocketry:precisionassemblingmachine", "tooltip.advancedrocketry.precisionassembler");
        KEY_BY_ID.put("advancedrocketry:electrolyser",       "tooltip.advancedrocketry.electrolyser");
        KEY_BY_ID.put("advancedrocketry:chemicalreactor",        "tooltip.advancedrocketry.chemreactor");
        KEY_BY_ID.put("advancedrocketry:precisionlaseretcher","tooltip.advancedrocketry.precisionlaseretcher");
        KEY_BY_ID.put("advancedrocketry:observatory",        "tooltip.advancedrocketry.observatory");
        KEY_BY_ID.put("advancedrocketry:planetanalyser",     "tooltip.advancedrocketry.planetanalyser");
        KEY_BY_ID.put("advancedrocketry:centrifuge",         "tooltip.advancedrocketry.centrifuge");
        KEY_BY_ID.put("advancedrocketry:orbitalregistry",         "tooltip.advancedrocketry.orbitalregistry");
        KEY_BY_ID.put("advancedrocketry:warpcore",           "tooltip.advancedrocketry.warpcore");
        KEY_BY_ID.put("advancedrocketry:beacon",             "tooltip.advancedrocketry.beacon");
        KEY_BY_ID.put("advancedrocketry:biomescanner",       "tooltip.advancedrocketry.biomescan");
        KEY_BY_ID.put("advancedrocketry:railgun",            "tooltip.advancedrocketry.railgun");
        KEY_BY_ID.put("advancedrocketry:spaceelevatorcontroller", "tooltip.advancedrocketry.spaceelevatorctrl");
        KEY_BY_ID.put("advancedrocketry:terraformer", "tooltip.advancedrocketry.atmosterraformer");
        KEY_BY_ID.put("advancedrocketry:gravitymachine", "tooltip.advancedrocketry.gravitymachine");
        KEY_BY_ID.put("advancedrocketry:spacelaser",  "tooltip.advancedrocketry.spacelaser");

        // ---- Building / components ----
        KEY_BY_ID.put("advancedrocketry:concrete",        "tooltip.advancedrocketry.concrete");
        KEY_BY_ID.put("advancedrocketry:blastbrick",      "tooltip.advancedrocketry.blastbrick");
        KEY_BY_ID.put("advancedrocketry:iquartzcrucible",       "tooltip.advancedrocketry.qcrucible");
        KEY_BY_ID.put("advancedrocketry:sawblade",        "tooltip.advancedrocketry.sawblade");
        KEY_BY_ID.put("advancedrocketry:vacuumlaser", "tooltip.advancedrocketry.vacuumlaser");
        
        KEY_BY_ID.put("advancedrocketry:hovercraft", "tooltip.advancedrocketry.hovercraft");

        // ---- Pump ----
        KEY_BY_ID.put("advancedrocketry:blockpump", "tooltip.advancedrocketry.pump");

        // ---- Remotes ----
        KEY_BY_ID.put("advancedrocketry:biomechanger", "tooltip.advancedrocketry.biomechangerremote");
        KEY_BY_ID.put("advancedrocketry:weathercontroller", "tooltip.advancedrocketry.weathercontrollerremote");
        KEY_BY_ID.put("advancedrocketry:orescanner", "tooltip.advancedrocketry.orescanner");


        // ---- Crafting items ----
        KEY_BY_ID.put("advancedrocketry:sawbladeiron", "tooltip.advancedrocketry.sawbladeiron");
        KEY_BY_ID.put("advancedrocketry:wafer",        "tooltip.advancedrocketry.wafer");
        KEY_BY_ID.put("advancedrocketry:itemcircuitplate", "tooltip.advancedrocketry.circuitplate");
        KEY_BY_ID.put("advancedrocketry:lens",          "tooltip.advancedrocketry.itemlens");
        KEY_BY_ID.put("advancedrocketry:ic",           "tooltip.advancedrocketry.circuitic");
        KEY_BY_ID.put("advancedrocketry:miscpart",     "tooltip.advancedrocketry.miscpart");
        KEY_BY_ID.put("advancedrocketry:misc",     "tooltip.advancedrocketry.misc");

        // ---- Assemblers ----
        KEY_BY_ID.put("advancedrocketry:rocketbuilder", "tooltip.advancedrocketry.rocketassembler");
        KEY_BY_ID.put("advancedrocketry:stationbuilder", "tooltip.advancedrocketry.stationassembler");
        KEY_BY_ID.put("advancedrocketry:deployablerocketbuilder", "tooltip.advancedrocketry.deployablerocketassembler");

        // ---- LibVulpes blocks ----
        KEY_BY_ID.put("libvulpes:coalgenerator", "tooltip.advancedrocketry.libvulpes.coalgenerator"); 
        KEY_BY_ID.put("libvulpes:hatch", "tooltip.advancedrocketry.libvulpes.hatch");
        KEY_BY_ID.put("libvulpes:forgepowerinput", "tooltip.advancedrocketry.libvulpes.forgepowerinput"); 
        KEY_BY_ID.put("libvulpes:forgepoweroutput", "tooltip.advancedrocketry.libvulpes.forgepoweroutput"); 
        KEY_BY_ID.put("libvulpes:creativepowerbattery", "tooltip.advancedrocketry.libvulpes.creativepowerbattery");

        // ---- Fuel Tanks ----
        KEY_BY_ID.put("advancedrocketry:fueltank", "tooltip.advancedrocketry.fueltank");
        KEY_BY_ID.put("advancedrocketry:bipropellantfueltank", "tooltip.advancedrocketry.bipropfueltank");
        KEY_BY_ID.put("advancedrocketry:oxidizerfueltank", "tooltip.advancedrocketry.oxidizerfueltank");
        KEY_BY_ID.put("advancedrocketry:nuclearfueltank", "tooltip.advancedrocketry.nuclearfueltank");

        // Monoprop tank
        ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.fueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_MONOPROPELLANT, 6) });
        // Biprop fuel tank
        ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.bipropfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_BIPROPELLANT, 6) });
        // Oxidizer tank
        ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.oxidizerfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.LIQUID_OXIDIZER, 6) });
        // Nuclear working fluid tank
        ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.nuclearfueltank",
            s -> new Object[]{ listFluidsFor(FuelType.NUCLEAR_WORKING_FLUID, 6) });

        // Example for adding more items later (no code changes beyond these lines):
        // KEY_BY_ID.put("advancedrocketry:carbonscrubbercartridge", "tooltip.advancedrocketry.scrubbercart");
        // KEY_BY_SUFFIX.put("carbonScrubberCartridge",              "tooltip.advancedrocketry.scrubbercart");
        // ARGS_BY_BASEKEY.put("tooltip.advancedrocketry.scrubbercart", s -> new Object[]{ Math.max(0, s.getMaxDamage() - s.getItemDamage()) });
    }

    @SubscribeEvent
    public static void onModels(ModelRegistryEvent e) { /* no-op */ }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        final ItemStack stack = e.getItemStack();
        if (stack.isEmpty()) return;

        final List<String> tooltip = e.getToolTip();

        // Insert before the advanced "modid:item" line when advanced tooltips are on
        final int insertAt = (e.getFlags().isAdvanced() && tooltip.size() > 1)
                ? tooltip.size() - 1
                : tooltip.size();

        final Item item = stack.getItem();
        @Nullable final ResourceLocation id = item.getRegistryName();
        String baseKey = null;

        if (id != null) {
            java.util.function.Function<ItemStack, String> res = KEY_RESOLVER_BY_ID.get(id.toString());
            if (res != null) {
                baseKey = res.apply(stack); // e.g., tooltip.advancedrocketry.itemupgrade.3
            }
            if (baseKey == null) {
                baseKey = KEY_BY_ID.get(id.toString());
            }
        }

        // Fallback: tail of unlocalized name (1.12 style)
        if (baseKey == null) {
            final String transKey = item.getUnlocalizedName(stack);
            final int dot = transKey.lastIndexOf('.');
            if (dot > 0) {
                final String tail = transKey.substring(dot + 1);
                baseKey = KEY_BY_SUFFIX.get(tail);
            }
        }

        if (baseKey != null) {
            renderShiftAlt(stack, tooltip, baseKey, insertAt);
        }
    }

    // ----- Generic renderer for base/shift/alt blocks -----
    @SideOnly(Side.CLIENT)
    public static void renderShiftAlt(ItemStack s, List<String> t, String baseKey, int idx) {
        final ArgProvider ap = ARGS_BY_BASEKEY.get(baseKey);
        final boolean hasShift = I18n.hasKey(baseKey + ".shift.1");
        final boolean hasAlt   = I18n.hasKey(baseKey + ".alt.1") || ap != null;

        // Base block: base, base.1, base.2, ...
        for (int i = 0; i <= 8; i++) {
            final String k = (i == 0) ? baseKey : (baseKey + "." + i);
            if (!I18n.hasKey(k)) {
                if (i == 0) continue; // no bare base line; try .1 anyway
                break;                // stop when sequence ends
            }
            t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
        }

        // Shift block (shift.1..N)
        if (hasShift) {
            if (GuiScreen.isShiftKeyDown()) {
                for (int i = 1; i <= 8; i++) {
                    final String k = baseKey + ".shift." + i;
                    if (!I18n.hasKey(k)) break;
                    t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
                }
            } else if (I18n.hasKey("tooltip.advancedrocketry.hold_shift")) {
                t.add(idx++, TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC +
                        I18n.format("tooltip.advancedrocketry.hold_shift"));
            }
        }

        // Alt block (alt.1..N)
        if (hasAlt) {
            if (isAltDown()) {
                for (int i = 1; i <= 8; i++) {
                    final String k = baseKey + ".alt." + i;
                    if (!I18n.hasKey(k)) break;
                    t.add(idx++, TextFormatting.GRAY + (ap != null ? I18n.format(k, ap.get(s)) : I18n.format(k)));
                }
            } else if (I18n.hasKey("tooltip.advancedrocketry.hold_alt")) {
                t.add(idx++, TextFormatting.DARK_GRAY.toString() + TextFormatting.ITALIC +
                        I18n.format("tooltip.advancedrocketry.hold_alt"));
            }
        }
    }



    // ----- Helpers -----

    @SideOnly(Side.CLIENT)
    private static String listFluidsFor(FuelType type, int max) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (net.minecraftforge.fluids.Fluid f : FluidRegistry.getRegisteredFluids().values()) {
            try {
                if (FuelRegistry.instance.isFuel(type, f)) {
                    // Localized name (1 bucket)
                    names.add(new FluidStack(f, 1000).getLocalizedName());
                    if (names.size() >= max) break;
                }
            } catch (Throwable t) {
                // be defensive against any odd registry states
            }
        }
        if (names.isEmpty()) return I18n.hasKey("tooltip.advancedrocketry.none") ? I18n.format("tooltip.advancedrocketry.none") : "None";
        // if there are more than max, add an ellipsis
        int total = 0;
        for (net.minecraftforge.fluids.Fluid f : FluidRegistry.getRegisteredFluids().values())
            if (FuelRegistry.instance.isFuel(type, f)) total++;
        String s = String.join(", ", names);
        return (total > names.size()) ? (s + ", …") : s;
    }

    private static int addIfPresentGray(List<String> t, String key, int idx) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.GRAY + I18n.format(key));
            return idx + 1;
        }
        return idx;
    }

    private static int addIfPresentDarkGray(List<String> t, String key, int idx) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.DARK_GRAY + I18n.format(key));
            return idx + 1;
        }
        return idx;
    }

    private static int addIfPresentDarkGrayFmt(List<String> t, String key, int idx, Object... args) {
        if (I18n.hasKey(key)) {
            t.add(idx, TextFormatting.DARK_GRAY + I18n.format(key, args));
            return idx + 1;
        }
        return idx;
    }

    @SideOnly(Side.CLIENT)
    public static int computeInsertIndex(List<String> tooltip, boolean advanced) {
        return (advanced && tooltip.size() > 1) ? tooltip.size() - 1 : tooltip.size();
    }    

    @SideOnly(Side.CLIENT)
    public static boolean isAltDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }
}
