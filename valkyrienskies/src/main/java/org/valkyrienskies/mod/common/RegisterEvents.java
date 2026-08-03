package org.valkyrienskies.mod.common;

import javax.annotation.Nonnull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.valkyrienskies.mod.client.BaseModel;
import org.valkyrienskies.mod.common.entity.EntityMountable;
import org.valkyrienskies.mod.common.entity.EntityMountableChair;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;
import zmaster587.advancedRocketry.network.EntityNetworkIds;

@Mod.EventBusSubscriber(modid = ValkyrienSkiesMod.HOST_MOD_ID)
public class RegisterEvents {

    private static final Logger logger = LogManager.getLogger(RegisterEvents.class);

    @SubscribeEvent
    public static void registerBlocks(@Nonnull final RegistryEvent.Register<Block> event) {
        logger.debug("Registering blocks");
        Block[] blockArray = ValkyrienSkiesMod.BLOCKS.toArray(new Block[0]);
        event.getRegistry().registerAll(blockArray);
    }

    @SubscribeEvent
    public static void registerItems(@Nonnull final RegistryEvent.Register<Item> event) {
        logger.debug("Registering items");
        event.getRegistry().registerAll(ValkyrienSkiesMod.ITEMS.toArray(new Item[0]));
    }

    @SubscribeEvent
    public static void registerRecipes(@Nonnull final RegistryEvent.Register<IRecipe> event) {
        ValkyrienSkiesMod.INSTANCE.registerRecipes(event);
    }

    @SubscribeEvent
    public static void onModelRegister(ModelRegistryEvent event) {
        for (Item item : ValkyrienSkiesMod.ITEMS) {
            if (item instanceof BaseModel) {
                ((BaseModel) item).registerModels();
            }
        }

        for (Block block : ValkyrienSkiesMod.BLOCKS) {
            if (block instanceof BaseModel) {
                ((BaseModel) block).registerModels();
            }
        }
    }

    /**
     * This method will be called by Forge when it is time for the mod to register its entity
     * entries.
     */
    @SubscribeEvent
    public static void onRegisterEntitiesEvent(
            @Nonnull final RegistryEvent.Register<EntityEntry> event) {
        final ResourceLocation physicsWrapperEntity = new ResourceLocation(ValkyrienSkiesMod.MOD_ID,
                "PhysWrapper");
        final ResourceLocation entityMountable = new ResourceLocation(ValkyrienSkiesMod.MOD_ID,
                "entity_mountable");
        final ResourceLocation entityMountableChair = new ResourceLocation(ValkyrienSkiesMod.MOD_ID,
                "entity_mountable_chair");
        // Entity network ids are resolved PER MOD CONTAINER, and this code is vendored into the
        // host mod instead of being loaded as its own: these ids therefore share one numbering
        // space with the host's entities and with anything else vendored beside them. A local
        // counter starting from 0 collided with the host's first two entities, and a receiving
        // client - which resolves an id to the FIRST registration under the mod carrying it -
        // rebuilt every mountable as one of those, then applied this entity's synced fields to it
        // slot by slot. The host owns the shared space: declare an entity in EntityNetworkIds and
        // read its id back here. Never count locally - a local counter cannot see the space.
        event.getRegistry()
                .registerAll(
                        EntityEntryBuilder.create()
                                .entity(EntityMountable.class)
                                .id(entityMountable, EntityNetworkIds.of(entityMountable))
                                .name(entityMountable.getResourcePath())
                                .tracker(ValkyrienSkiesMod.VS_ENTITY_LOAD_DISTANCE, 1, false)
                                .build(),
                        EntityEntryBuilder.create()
                                .entity(EntityMountableChair.class)
                                .id(entityMountableChair, EntityNetworkIds.of(entityMountableChair))
                                .name(entityMountableChair.getResourcePath())
                                .tracker(ValkyrienSkiesMod.VS_ENTITY_LOAD_DISTANCE, 1, false)
                                .build()
                );
    }
}
