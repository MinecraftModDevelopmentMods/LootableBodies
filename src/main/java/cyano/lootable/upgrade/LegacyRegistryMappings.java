package cyano.lootable.upgrade;

import cyano.lootable.LootableBodies;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** The vanilla XP pickup sound was renamed after Minecraft 1.10. */
@Mod.EventBusSubscriber(modid=LootableBodies.MODID,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class LegacyRegistryMappings {
    public static ResourceLocation itemReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return new ResourceLocation("minecraft:oak_sign");
            case "rose_red": return new ResourceLocation("minecraft:red_dye");
            case "cactus_green": return new ResourceLocation("minecraft:green_dye");
            case "dandelion_yellow": return new ResourceLocation("minecraft:yellow_dye");
            case "zombie_pigman_spawn_egg":return new ResourceLocation("minecraft:zombified_piglin_spawn_egg");
            default: return null;
        }
    }
    public static ResourceLocation blockReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return new ResourceLocation("minecraft:oak_sign");
            case "wall_sign": return new ResourceLocation("minecraft:oak_wall_sign");
            default: return null;
        }
    }
    private static final ResourceLocation OLD_XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.touch");
    private static final ResourceLocation XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.pickup");
    private LegacyRegistryMappings() { }
    static <T> T requireMappingTarget(java.util.function.Supplier<T> lookup) {
        try {T target=lookup.get();if(target!=null)return target;}
        catch(RuntimeException failure){throw new UnsafeRegistryUpgrade(failure);}
        throw new UnsafeRegistryUpgrade(null);
    }
    static <T> T requireMappingTarget(boolean present,java.util.function.Supplier<T> lookup) {
        if(!present)throw new UnsafeRegistryUpgrade(null);
        return requireMappingTarget(lookup);
    }
    private static final class UnsafeRegistryUpgrade extends Error {
        private static final long serialVersionUID=1L;
        UnsafeRegistryUpgrade(Throwable cause){super("Lootable Bodies stopped an unsafe vanilla registry conversion. Keep the original world and upgrade journal.",cause);}
    }

    public static ResourceLocation entityReplacement(ResourceLocation old) {
        return old.equals(new ResourceLocation("minecraft:zombie_pigman"))?new ResourceLocation("minecraft:zombified_piglin"):null;
    }
    public static ResourceLocation soundReplacement(ResourceLocation old) {
        if(old.equals(OLD_XP_SOUND))return XP_SOUND;
        if(!old.getNamespace().equals("minecraft"))return null;
        if(old.getPath().equals("music.nether"))return new ResourceLocation("minecraft:music.nether.nether_wastes");
        for(String suffix:new String[]{"ambient","angry","death","hurt"})
            if(old.getPath().equals("entity.zombie_pigman."+suffix))return new ResourceLocation("minecraft:entity.zombified_piglin."+suffix);
        return null;
    }
    /** This old Long memory had no serializer or saved values in vanilla1.15. */
    public static void retiredMemories(RegistryEvent.Register<net.minecraft.entity.ai.brain.memory.MemoryModuleType<?>> event) {
        event.getRegistry().register(new net.minecraft.entity.ai.brain.memory.MemoryModuleType<Long>(java.util.Optional.empty())
                .setRegistryName("minecraft:golem_last_seen_time"));
    }

    @SubscribeEvent public static void items(RegistryEvent.MissingMappings<net.minecraft.item.Item> event) {
        if(!event.getRegistry().getRegistrySuperType().equals(net.minecraft.item.Item.class))return;
        for (RegistryEvent.MissingMappings.Mapping<net.minecraft.item.Item> mapping : event.getAllMappings()) {
            ResourceLocation replacement = itemReplacement(mapping.key);
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(replacement)));
        }
    }
    @SubscribeEvent public static void blocks(RegistryEvent.MissingMappings<net.minecraft.block.Block> event) {
        if(!event.getRegistry().getRegistrySuperType().equals(net.minecraft.block.Block.class))return;
        for (RegistryEvent.MissingMappings.Mapping<net.minecraft.block.Block> mapping : event.getAllMappings()) {
            ResourceLocation replacement = blockReplacement(mapping.key);
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(replacement)));
        }
    }
    @SubscribeEvent public static void entities(RegistryEvent.MissingMappings<net.minecraft.entity.EntityType<?>> event) {
        if(!event.getRegistry().getRegistrySuperType().equals(net.minecraft.entity.EntityType.class))return;
        for(RegistryEvent.MissingMappings.Mapping<net.minecraft.entity.EntityType<?>> mapping:event.getAllMappings()) {
            ResourceLocation replacement=entityReplacement(mapping.key);
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ENTITIES.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ENTITIES.getValue(replacement)));
        }
    }
    @SubscribeEvent public static void memories(RegistryEvent.MissingMappings<net.minecraft.entity.ai.brain.memory.MemoryModuleType<?>> event) {
        if(!event.getRegistry().getRegistrySuperType().equals(net.minecraft.entity.ai.brain.memory.MemoryModuleType.class))return;
        for(RegistryEvent.MissingMappings.Mapping<net.minecraft.entity.ai.brain.memory.MemoryModuleType<?>> mapping:event.getAllMappings())
            if(mapping.key.equals(new ResourceLocation("minecraft:opened_doors")))
                mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.containsKey(new ResourceLocation("minecraft:doors_to_close")),()->net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.getValue(new ResourceLocation("minecraft:doors_to_close"))));
    }

    @SubscribeEvent public static void sounds(RegistryEvent.MissingMappings<SoundEvent> event) {
        if(!event.getRegistry().getRegistrySuperType().equals(SoundEvent.class))return;
        for(RegistryEvent.MissingMappings.Mapping<SoundEvent> mapping:event.getAllMappings()) {
            ResourceLocation replacement=soundReplacement(mapping.key);
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(replacement)));
        }
    }
}
