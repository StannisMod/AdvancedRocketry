package com.github.stannismod.affs;

import com.github.stannismod.affs.block.*;
import com.github.stannismod.affs.config.ModConfig;
import com.github.stannismod.affs.entity.EntityLaserBolt;
import com.github.stannismod.affs.item.ItemCodeDevice;
import com.github.stannismod.affs.item.ItemLaserGun;
import com.github.stannismod.affs.network.*;
import com.github.stannismod.affs.te.*;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;
import zmaster587.advancedRocketry.AdvancedRocketry;
import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.network.EntityNetworkIds;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Vendored into Advanced Rocketry as its shield subsystem: no longer its own
// @Mod. The mod is folded into AR's single container — AR drives the lifecycle
// (preInit/init/postInit) from its own @Mod handlers, and the static registry
// handlers below register under AR's modid. The registry DOMAIN stays "affs".
@Mod.EventBusSubscriber(modid = Constants.modId)
public class AdvancedForceFieldSystem {

    public static final String MODID = "affs";
    public static final String MODNAME = "AdvancedForceFieldSystem";
    public static final String VERSION = "0.0.1";

    public static Logger LOG;
    public static final int GUI_FIELD_GENERATOR = 1;
    public static final int GUI_ADMIN_ENERGY_SOURCE = 2;
    public static final int GUI_CODE_DEVICE = 3;
    public static final int GUI_SHIELD_GENERATOR = 4;
    public static final int GUI_SHIELD_NETWORK = 5;
    public static final int GUI_SHIELD_CONSOLE = 6;
    public static final int GUI_NETWORK_MAP = 8;
    public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel(MODID);
    private static int packetId = 0;

    public static Item itemFieldGenerator;
    public static Item itemShieldGenerator;
    public static Item itemShieldAccumulator;
    public static Item itemShieldCable;
    public static Item itemAdminEnergySource;
    public static Item itemContourFrame;
    public static Item itemContourInjector;
    public static Item itemLaserGun;
    public static ItemCodeDevice ITEM_CODE_DEVICE;

    public static BlockFieldGenerator BLOCK_FIELD_GENERATOR;
    public static BlockShieldGenerator BLOCK_SHIELD_GENERATOR;
    public static BlockShieldAccumulator BLOCK_SHIELD_ACCUMULATOR;
    public static BlockShieldCable BLOCK_SHIELD_CABLE;
    public static BlockShieldConsole BLOCK_SHIELD_CONSOLE;
    public static BlockAdminEnergySource BLOCK_ADMIN_ENERGY_SOURCE;
    public static BlockContourFrame BLOCK_CONTOUR_FRAME;
    public static BlockContourInjector BLOCK_CONTOUR_INJECTOR;
    public static CreativeTabs tabAffs = new CreativeTabs("tabAffs") {
        @Override
        public ItemStack getTabIconItem() {
            Item icon = itemFieldGenerator != null ? itemFieldGenerator
                    : itemShieldGenerator != null ? itemShieldGenerator
                    : itemContourFrame;
            return icon == null ? ItemStack.EMPTY : new ItemStack(icon);
        }
    };

    // Self-initialised (ready at class-load, before any registry event) — the
    // guest is no longer a @Mod, so FML does not inject @Mod.Instance for it.
    public static final AdvancedForceFieldSystem INSTANCE = new AdvancedForceFieldSystem();

    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
        ModConfig.load(event.getSuggestedConfigurationFile());
        initContent();
        // GUI handler is registered by AR (AffsGuiRouter) — the guest is no longer its own mod
        // container, so it cannot own an IGuiHandler. GUIs are opened via openAffsGui below.
        NETWORK.registerMessage(PacketSetFieldRadius.Handler.class, PacketSetFieldRadius.class, packetId++, Side.SERVER);
        NETWORK.registerMessage(PacketSyncCodeValue.Handler.class, PacketSyncCodeValue.class, packetId++, Side.SERVER);
        NETWORK.registerMessage(PacketSyncActiveGenerators.Handler.class, PacketSyncActiveGenerators.class, packetId++, Side.CLIENT);
        NETWORK.registerMessage(PacketFieldTouchEffect.Handler.class, PacketFieldTouchEffect.class, packetId++, Side.CLIENT);
        NETWORK.registerMessage(PacketOpenGui.Handler.class, PacketOpenGui.class, packetId++, Side.SERVER);
        NETWORK.registerMessage(PacketSetShieldResistanceBias.Handler.class, PacketSetShieldResistanceBias.class, packetId++, Side.SERVER);
    }

    public void init(FMLInitializationEvent event) {
        GameRegistry.registerTileEntity(TileEntityFieldGenerator.class, new ResourceLocation(MODID, "field_generator"));
        GameRegistry.registerTileEntity(TileEntityShieldGenerator.class, new ResourceLocation(MODID, "shield_generator"));
        GameRegistry.registerTileEntity(TileEntityShieldAccumulator.class, new ResourceLocation(MODID, "shield_accumulator"));
        GameRegistry.registerTileEntity(TileEntityShieldCable.class, new ResourceLocation(MODID, "shield_cable"));
        GameRegistry.registerTileEntity(TileEntityShieldConsole.class, new ResourceLocation(MODID, "shield_console"));
        GameRegistry.registerTileEntity(TileEntityAdminEnergySource.class, new ResourceLocation(MODID, "admin_energy_source"));
        GameRegistry.registerTileEntity(TileEntityContourInjector.class, new ResourceLocation(MODID, "contour_injector"));
        // Owner is AR's mod instance (the guest is folded into AR's container), so this entity lives
        // in the host's one container-wide network id space and takes its id from the space's owner
        // instead of a number chosen here. Declare a new entity there before registering it.
        EntityNetworkIds.register(new ResourceLocation(MODID, "laser_bolt"), EntityLaserBolt.class, "laser_bolt", AdvancedRocketry.instance, 64, 10, true);
        if (event.getSide().isClient()) {
            com.github.stannismod.affs.client.ClientEntityRenderRegistry.init();
        }
    }

    public void postInit(FMLPostInitializationEvent event) {
    }

    /**
     * Opens an AFFS GUI. Because AFFS is folded into AR's single mod container, the owner
     * passed to {@code openGui} must be AR's mod instance, and the local GUI id is offset into
     * {@link zmaster587.advancedRocketry.integration.affs.AffsGuiRouter}'s AFFS range.
     */
    public static void openAffsGui(net.minecraft.entity.player.EntityPlayer player, int guiId,
                                   net.minecraft.world.World world, int x, int y, int z) {
        player.openGui(AdvancedRocketry.instance,
                zmaster587.advancedRocketry.integration.affs.AffsGuiRouter.AFFS_GUI_BASE + guiId,
                world, x, y, z);
    }

    private static List<Block> blocks;

    private static void initContent() {
        if (blocks != null) {
            return;
        }

        BLOCK_FIELD_GENERATOR = new BlockFieldGenerator("field_generator", Material.IRON);
        BLOCK_SHIELD_GENERATOR = new BlockShieldGenerator("shield_generator", Material.IRON);
        BLOCK_SHIELD_ACCUMULATOR = new BlockShieldAccumulator("shield_accumulator", Material.IRON);
        BLOCK_SHIELD_CABLE = new BlockShieldCable("shield_cable", Material.IRON);
        BLOCK_SHIELD_CONSOLE = new BlockShieldConsole("shield_console", Material.IRON);
        BLOCK_ADMIN_ENERGY_SOURCE = new BlockAdminEnergySource("admin_energy_source", Material.IRON);
        BLOCK_CONTOUR_FRAME = new BlockContourFrame("contour_frame", Material.IRON);
        BLOCK_CONTOUR_INJECTOR = new BlockContourInjector("contour_injector", Material.IRON);
        ITEM_CODE_DEVICE = new ItemCodeDevice();
        itemLaserGun = new ItemLaserGun();

        List<Block> content = new ArrayList<>();
        content.add(BLOCK_FIELD_GENERATOR);
        content.add(BLOCK_SHIELD_GENERATOR);
        content.add(BLOCK_SHIELD_ACCUMULATOR);
        content.add(BLOCK_SHIELD_CABLE);
        content.add(BLOCK_SHIELD_CONSOLE);
        content.add(BLOCK_ADMIN_ENERGY_SOURCE);
        content.add(BLOCK_CONTOUR_FRAME);
        content.add(BLOCK_CONTOUR_INJECTOR);
        blocks = Collections.unmodifiableList(content);
    }

    private static List<Block> getBlocks() {
        initContent();
        return blocks;
    }

    @SubscribeEvent
    public static void onRegisterItems(RegistryEvent.Register<Item> event) {
        initContent();
        event.getRegistry().register(ITEM_CODE_DEVICE);
        event.getRegistry().register(itemLaserGun);
        for (Block block : getBlocks()) {
            Item item = createItemBlock(block);
            if (item == null) {
                continue;
            }

            event.getRegistry().register(item);
            cacheRegisteredItem(item);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            PacketSyncActiveGenerators.sendFullSnapshotToPlayer((net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player instanceof net.minecraft.entity.player.EntityPlayerMP) {
            PacketSyncActiveGenerators.sendFullSnapshotToPlayer((net.minecraft.entity.player.EntityPlayerMP) event.player);
        }
    }

    @Nullable
    private static Item createItemBlock(Block block) {
        if (!(block instanceof IHasItemBlock)) {
            return new ItemBlock(block).setRegistryName(block.getRegistryName());
        }
        return ((IHasItemBlock) block).createItemBlock();
    }

    private static void cacheRegisteredItem(Item item) {
        ResourceLocation registryName = item.getRegistryName();
        if (registryName == null) {
            return;
        }

        String path = resourcePath(registryName);
        switch (path) {
            case "field_generator":
                itemFieldGenerator = item;
                break;
            case "shield_generator":
                itemShieldGenerator = item;
                break;
            case "shield_accumulator":
                itemShieldAccumulator = item;
                break;
            case "shield_cable":
                itemShieldCable = item;
                break;
            case "shield_console":
                break;
            case "admin_energy_source":
                itemAdminEnergySource = item;
                break;
            case "contour_frame":
                itemContourFrame = item;
                break;
            case "contour_injector":
                itemContourInjector = item;
                break;
            case "laser_gun":
                itemLaserGun = item;
                break;
        }
    }

    @SubscribeEvent
    public static void onRegisterBlocks(RegistryEvent.Register<Block> event) {
        initContent();
        for (Block block : getBlocks()) {
            event.getRegistry().register(block);
        }
    }

    @SideOnly(Side.CLIENT)
    @SubscribeEvent
    public static void onModelRegister(ModelRegistryEvent event) {
        initContent();
        registerModel(ITEM_CODE_DEVICE);
        registerModel(itemLaserGun);
        for (Block block : getBlocks()) {
            if (Item.getItemFromBlock(block) == Items.AIR) {
                continue;
            }
            if (isTieredBlock(block)) {
                registerTieredBlockModels(block);
                continue;
            }
            registerModel(block);
        }
    }

    private static void registerModel(Item item) {
        ModelLoader.setCustomModelResourceLocation(
            item,
            0,
            new ModelResourceLocation(item.getRegistryName().toString(), "inventory")
        );
    }

    private static void registerTieredBlockModels(Block block) {
        Item item = Item.getItemFromBlock(block);
        String basePath = resourcePath(block.getRegistryName());
        for (int tier = 0; tier < BlockFieldGenerator.TIER_COUNT; tier++) {
            registerModel(item, tier, new ResourceLocation(MODID, basePath + "_tier" + tier));
        }
    }

    private static void registerModel(Item item, int meta, ResourceLocation modelLocation) {
        ModelLoader.setCustomModelResourceLocation(
            item,
            meta,
            new ModelResourceLocation(modelLocation, "inventory")
        );
    }

    private static void registerModel(Block block) {
        ModelLoader.setCustomModelResourceLocation(
            Item.getItemFromBlock(block),
            0,
            new ModelResourceLocation(block.getRegistryName().toString(), "inventory")
        );
    }

    private static boolean isTieredBlock(Block block) {
        return block == BLOCK_FIELD_GENERATOR
                || block == BLOCK_SHIELD_GENERATOR
                || block == BLOCK_SHIELD_CABLE;
    }

    public static String resourcePath(@Nullable ResourceLocation location) {
        if (location == null) {
            return "unknown";
        }
        try {
            return (String) ResourceLocation.class.getMethod("getResourcePath").invoke(location);
        } catch (ReflectiveOperationException ignored) {
            try {
                return (String) ResourceLocation.class.getMethod("getPath").invoke(location);
            } catch (ReflectiveOperationException ignoredToo) {
                return location.toString();
            }
        }
    }

}
