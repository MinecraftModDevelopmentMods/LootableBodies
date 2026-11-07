package cyano.lootable.upgrade;

import cyano.lootable.LootableBodies;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Known vanilla registry renames, using the native conversion rules. */
@Mod.EventBusSubscriber(modid=LootableBodies.MODID,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class LegacyRegistryMappings {
    public static ResourceLocation biomeReplacement(ResourceLocation old) {
        if(!old.getNamespace().equals("minecraft"))return null;
        String name=old.toString();
        name=net.minecraft.util.datafix.fixes.BiomeFix.BIOMES.getOrDefault(name,name);
        if(name.equals("minecraft:nether"))name="minecraft:nether_wastes";
        name=net.minecraft.util.datafix.fixes.CavesAndCliffsRenames.RENAMES.getOrDefault(name,name);
        if(name.equals("minecraft:deep_warm_ocean"))name="minecraft:warm_ocean";
        return name.equals(old.toString())?null:ResourceLocation.parse(name);
    }
    public static ResourceLocation itemReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return ResourceLocation.parse("minecraft:oak_sign");
            case "rose_red": return ResourceLocation.parse("minecraft:red_dye");
            case "cactus_green": return ResourceLocation.parse("minecraft:green_dye");
            case "dandelion_yellow": return ResourceLocation.parse("minecraft:yellow_dye");
            case "grass_path": return ResourceLocation.parse("minecraft:dirt_path");
            case "grass": return ResourceLocation.withDefaultNamespace("short_grass");
            case "zombie_pigman_spawn_egg":return ResourceLocation.parse("minecraft:zombified_piglin_spawn_egg");
            case "scute": return ResourceLocation.withDefaultNamespace("turtle_scute");
            case "pottery_shard_archer": return ResourceLocation.parse("minecraft:archer_pottery_sherd");
            case "pottery_shard_arms_up": return ResourceLocation.parse("minecraft:arms_up_pottery_sherd");
            case "pottery_shard_prize": return ResourceLocation.parse("minecraft:prize_pottery_sherd");
            case "pottery_shard_skull": return ResourceLocation.parse("minecraft:skull_pottery_sherd");
            default: return null;
        }
    }
    public static ResourceLocation blockReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return ResourceLocation.parse("minecraft:oak_sign");
            case "wall_sign": return ResourceLocation.parse("minecraft:oak_wall_sign");
            case "grass_path": return ResourceLocation.parse("minecraft:dirt_path");
            case "grass": return ResourceLocation.withDefaultNamespace("short_grass");
            default: return null;
        }
    }
    private static final ResourceLocation OLD_XP_SOUND=ResourceLocation.parse("minecraft:entity.experience_orb.touch");
    private static final ResourceLocation XP_SOUND=ResourceLocation.parse("minecraft:entity.experience_orb.pickup");
    private static final ResourceLocation OLD_JUNGLE_AND_FOREST_MUSIC =
            ResourceLocation.parse("minecraft:music.overworld.jungle_and_forest");
    private static final Set<ResourceLocation> RETIRED_PARROT_SOUNDS = Set.of(
            ResourceLocation.withDefaultNamespace("entity.parrot.imitate.enderman"),
            ResourceLocation.withDefaultNamespace("entity.parrot.imitate.panda"),
            ResourceLocation.withDefaultNamespace("entity.parrot.imitate.polar_bear"),
            ResourceLocation.withDefaultNamespace("entity.parrot.imitate.wolf"),
            ResourceLocation.withDefaultNamespace("entity.parrot.imitate.zombie_pigman"));
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
        return old.equals(ResourceLocation.parse("minecraft:zombie_pigman"))?ResourceLocation.parse("minecraft:zombified_piglin"):null;
    }
    public static ResourceLocation soundReplacement(ResourceLocation old) {
        if(old.equals(OLD_XP_SOUND))return XP_SOUND;
        if(!old.getNamespace().equals("minecraft"))return null;
        if(old.getPath().equals("item.sweet_berries.pick_from_bush"))return ResourceLocation.parse("minecraft:block.sweet_berry_bush.pick_berries");
        if(old.getPath().equals("music.nether"))return ResourceLocation.parse("minecraft:music.nether.nether_wastes");
        if(old.getPath().equals("block.trial_spawner.ambient_charged"))return ResourceLocation.withDefaultNamespace("block.trial_spawner.ambient_ominous");
        if(old.getPath().equals("block.trial_spawner.charge_activate"))return ResourceLocation.withDefaultNamespace("block.trial_spawner.ominous_activate");
        if (old.getPath().equals("item.brush.brushing"))
            return ResourceLocation.parse("minecraft:item.brush.brushing.generic");
        if (old.getPath().equals("item.brush.brush_sand_completed"))
            return ResourceLocation.parse("minecraft:item.brush.brushing.sand.complete");
        for(String suffix:new String[]{"ambient","angry","death","hurt"})
            if(old.getPath().equals("entity.zombie_pigman."+suffix))return ResourceLocation.parse("minecraft:entity.zombified_piglin."+suffix);
        return null;
    }
    /** This old Long memory had no serializer or saved values in vanilla1.15. */
    public static void retiredMemories(RegisterEvent event) {
        event.register(Registries.MEMORY_MODULE_TYPE,ResourceLocation.parse("minecraft:golem_last_seen_time"),
                ()->new net.minecraft.world.entity.ai.memory.MemoryModuleType<Long>(java.util.Optional.empty()));
    }

    @SubscribeEvent public static void items(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<net.minecraft.world.item.Item> mapping : event.<net.minecraft.world.item.Item>getAllMappings(Registries.ITEM)) {
            ResourceLocation replacement = itemReplacement(mapping.getKey());
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(replacement)));
        }
    }
    @SubscribeEvent public static void blocks(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<net.minecraft.world.level.block.Block> mapping : event.<net.minecraft.world.level.block.Block>getAllMappings(Registries.BLOCK)) {
            ResourceLocation replacement = blockReplacement(mapping.getKey());
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(replacement)));
        }
    }
    static ResourceLocation enchantmentReplacement(ResourceLocation old) {
        return old.equals(ResourceLocation.withDefaultNamespace("sweeping"))
                ?ResourceLocation.withDefaultNamespace("sweeping_edge"):null;
    }

    @SubscribeEvent public static void enchantments(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.item.enchantment.Enchantment>getAllMappings(Registries.ENCHANTMENT)) {
            ResourceLocation replacement=enchantmentReplacement(mapping.getKey());
            if(replacement==null)continue;
            // The old numeric snapshot does not own the 1.21 data-pack
            // registry. Native item conversion renames the actual enchantment.
            if(VanillaData.LOOKUP.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT,replacement)).isEmpty())
                throw new UnsafeRegistryUpgrade(null);
            mapping.ignore();
        }
    }
    private static final class VanillaData {
        static final net.minecraft.core.HolderLookup.Provider LOOKUP=net.minecraft.data.registries.VanillaRegistries.createLookup();
    }

    @SubscribeEvent public static void paintings(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.entity.decoration.PaintingVariant>getAllMappings(Registries.PAINTING_VARIANT)) {
            if(vanillaPaintingVariant(mapping.getKey()))mapping.ignore();
        }
    }

    static boolean vanillaPaintingVariant(ResourceLocation key) {
        // A named vanilla painting now lives in the data-pack registry.
        // Retire only a numeric binding whose exact name the native data owns.
        return key.getNamespace().equals("minecraft") && VanillaData.LOOKUP.lookupOrThrow(Registries.PAINTING_VARIANT)
                .get(net.minecraft.resources.ResourceKey.create(Registries.PAINTING_VARIANT,key)).isPresent();
    }

    static boolean retiredPotion(ResourceLocation key) {
        return key.equals(ResourceLocation.withDefaultNamespace("empty"));
    }

    @SubscribeEvent public static void potions(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.item.alchemy.Potion>getAllMappings(Registries.POTION)) {
            // The native item fixer represents the old empty sentinel as an
            // absent potion holder. A real potion here would change its contents.
            if(retiredPotion(mapping.getKey())
                    && net.minecraft.world.item.alchemy.PotionContents.EMPTY.potion().isEmpty())mapping.ignore();
        }
    }

    @SubscribeEvent public static void biomes(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.level.biome.Biome> mapping:event.<net.minecraft.world.level.biome.Biome>getAllMappings(Registries.BIOME)) {
            if(!mapping.getKey().getNamespace().equals("minecraft"))continue;
            ResourceLocation replacement=biomeReplacement(mapping.getKey());
            ResourceLocation current=replacement==null?mapping.getKey():replacement;
            // Native19 chunk palettes use names in the data-pack registry, not
            // the old Forge numeric biome snapshot. Resolve only keys proved
            // by the actual vanilla bootstrap; vanilla still fixes chunk data.
            if(VanillaData.LOOKUP.lookupOrThrow(Registries.BIOME)
                    .get(net.minecraft.resources.ResourceKey.create(Registries.BIOME,current)).isPresent())mapping.ignore();
            else if(replacement!=null)throw new UnsafeRegistryUpgrade(null);
        }
    }
    @SubscribeEvent public static void retiredPointsOfInterest(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.ai.village.poi.PoiType> mapping:
                event.<net.minecraft.world.entity.ai.village.poi.PoiType>getAllMappings(Registries.POINT_OF_INTEREST_TYPE)) {
            // Native PoiTypeRemoveFix3107 removes exactly these unpopulated
            // vanilla records. Resolve their retired numeric bindings only.
            if(mapping.getKey().equals(ResourceLocation.parse("minecraft:unemployed"))
                    || mapping.getKey().equals(ResourceLocation.parse("minecraft:nitwit")))mapping.ignore();
        }
    }
    @SubscribeEvent public static void entities(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.EntityType<?>> mapping:event.<net.minecraft.world.entity.EntityType<?>>getAllMappings(Registries.ENTITY_TYPE)) {
            ResourceLocation replacement=entityReplacement(mapping.getKey());
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(replacement)));
        }
    }
    @SubscribeEvent public static void memories(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.ai.memory.MemoryModuleType<?>> mapping:event.<net.minecraft.world.entity.ai.memory.MemoryModuleType<?>>getAllMappings(Registries.MEMORY_MODULE_TYPE))
            if(mapping.getKey().equals(ResourceLocation.parse("minecraft:opened_doors")))
                mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.containsKey(ResourceLocation.parse("minecraft:doors_to_close")),()->net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.getValue(ResourceLocation.parse("minecraft:doors_to_close"))));
    }

    @SubscribeEvent public static void sounds(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<SoundEvent> mapping:event.<SoundEvent>getAllMappings(Registries.SOUND_EVENT)) {
            if (RETIRED_PARROT_SOUNDS.contains(mapping.getKey())
                    && !BuiltInRegistries.SOUND_EVENT.containsKey(mapping.getKey())) {
                // Old saves retain these numeric bindings after vanilla removed the imitations.
                mapping.ignore();
                continue;
            }
            if (mapping.getKey().equals(OLD_JUNGLE_AND_FOREST_MUSIC)) {
                // Vanilla 1.20 defines separate music for the two biomes.
                // Retire only the obsolete Forge numeric binding.
                if (!BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse("minecraft:music.overworld.jungle"))
                        || !BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse("minecraft:music.overworld.forest")))
                    throw new UnsafeRegistryUpgrade(null);
                mapping.ignore();
                continue;
            }
            ResourceLocation replacement=soundReplacement(mapping.getKey());
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(replacement)));
        }
    }
}
