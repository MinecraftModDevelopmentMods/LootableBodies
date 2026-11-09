package cyano.lootable.upgrade;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.List;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraft.core.registries.Registries;

/** Known vanilla registry renames, using the native conversion rules. */
public final class LegacyRegistryMappings {
    public static Identifier biomeReplacement(Identifier old) {
        if(!old.getNamespace().equals("minecraft"))return null;
        String name=old.toString();
        name=net.minecraft.util.datafix.fixes.BiomeFix.BIOMES.getOrDefault(name,name);
        if(name.equals("minecraft:nether"))name="minecraft:nether_wastes";
        name=net.minecraft.util.datafix.fixes.CavesAndCliffsRenames.RENAMES.getOrDefault(name,name);
        if(name.equals("minecraft:deep_warm_ocean"))name="minecraft:warm_ocean";
        return name.equals(old.toString())?null:Identifier.parse(name);
    }
    public static Identifier itemReplacement(Identifier old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return Identifier.parse("minecraft:oak_sign");
            case "rose_red": return Identifier.parse("minecraft:red_dye");
            case "cactus_green": return Identifier.parse("minecraft:green_dye");
            case "dandelion_yellow": return Identifier.parse("minecraft:yellow_dye");
            case "grass_path": return Identifier.parse("minecraft:dirt_path");
            case "chain": return Identifier.withDefaultNamespace("iron_chain");
            case "grass": return Identifier.withDefaultNamespace("short_grass");
            case "zombie_pigman_spawn_egg":return Identifier.parse("minecraft:zombified_piglin_spawn_egg");
            case "scute": return Identifier.withDefaultNamespace("turtle_scute");
            case "pottery_shard_archer": return Identifier.parse("minecraft:archer_pottery_sherd");
            case "pottery_shard_arms_up": return Identifier.parse("minecraft:arms_up_pottery_sherd");
            case "pottery_shard_prize": return Identifier.parse("minecraft:prize_pottery_sherd");
            case "pottery_shard_skull": return Identifier.parse("minecraft:skull_pottery_sherd");
            default: return null;
        }
    }
    public static Identifier blockReplacement(Identifier old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return Identifier.parse("minecraft:oak_sign");
            case "wall_sign": return Identifier.parse("minecraft:oak_wall_sign");
            case "grass_path": return Identifier.parse("minecraft:dirt_path");
            case "chain": return Identifier.withDefaultNamespace("iron_chain");
            case "grass": return Identifier.withDefaultNamespace("short_grass");
            default: return null;
        }
    }
    private static final Identifier OLD_XP_SOUND=Identifier.parse("minecraft:entity.experience_orb.touch");
    private static final Identifier XP_SOUND=Identifier.parse("minecraft:entity.experience_orb.pickup");
    private static final Identifier OLD_JUNGLE_AND_FOREST_MUSIC =
            Identifier.parse("minecraft:music.overworld.jungle_and_forest");
    private static final Set<Identifier> RETIRED_PARROT_SOUNDS = Set.of(
            Identifier.withDefaultNamespace("entity.parrot.imitate.enderman"),
            Identifier.withDefaultNamespace("entity.parrot.imitate.panda"),
            Identifier.withDefaultNamespace("entity.parrot.imitate.polar_bear"),
            Identifier.withDefaultNamespace("entity.parrot.imitate.wolf"),
            Identifier.withDefaultNamespace("entity.parrot.imitate.zombie_pigman"));
    // These old IDs split into multiple variants in vanilla's entity data
    // fixes. There is no single correct registry target to remap them to.
    private static final Set<Identifier> SPLIT_ENTITIES = Set.of(
            Identifier.withDefaultNamespace("boat"), Identifier.withDefaultNamespace("chest_boat"),
            Identifier.withDefaultNamespace("potion"));
    private static final Set<Identifier> RETIRED_121_SOUNDS = Set.of(
            Identifier.withDefaultNamespace("entity.goat.screaming.horn_break"),
            Identifier.withDefaultNamespace("entity.puffer_fish.ambient"),
            Identifier.withDefaultNamespace("entity.wolf.howl"),
            Identifier.withDefaultNamespace("item.goat_horn.play"));
    private LegacyRegistryMappings() { }

    public static Identifier canonicalAttributeId(Identifier id) {
        if (id == null || !id.getNamespace().equals("minecraft")) return id;
        String path = id.getPath();
        for (String prefix : List.of("generic.", "horse.", "player.", "zombie.")) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                return Identifier.withDefaultNamespace(path.substring(prefix.length()));
            }
        }
        return id;
    }

    public static void register() {
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::items);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::blocks);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::enchantments);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::paintings);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::potions);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::biomes);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::retiredPointsOfInterest);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::entities);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::memories);
        MissingMappingsEvent.BUS.addListener(LegacyRegistryMappings::sounds);
    }
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

    public static Identifier entityReplacement(Identifier old) {
        return old.equals(Identifier.parse("minecraft:zombie_pigman"))?Identifier.parse("minecraft:zombified_piglin"):null;
    }
    public static Identifier soundReplacement(Identifier old) {
        if(old.equals(OLD_XP_SOUND))return XP_SOUND;
        if(old.equals(Identifier.withDefaultNamespace("entity.leash_knot.break")))
            return Identifier.withDefaultNamespace("item.lead.untied");
        if(old.equals(Identifier.withDefaultNamespace("entity.leash_knot.place")))
            return Identifier.withDefaultNamespace("item.lead.tied");
        if(!old.getNamespace().equals("minecraft"))return null;
        if(old.getPath().equals("item.sweet_berries.pick_from_bush"))return Identifier.parse("minecraft:block.sweet_berry_bush.pick_berries");
        if(old.getPath().equals("music.nether"))return Identifier.parse("minecraft:music.nether.nether_wastes");
        if(old.getPath().equals("block.trial_spawner.ambient_charged"))return Identifier.withDefaultNamespace("block.trial_spawner.ambient_ominous");
        if(old.getPath().equals("block.trial_spawner.charge_activate"))return Identifier.withDefaultNamespace("block.trial_spawner.ominous_activate");
        if (old.getPath().equals("item.brush.brushing"))
            return Identifier.parse("minecraft:item.brush.brushing.generic");
        if (old.getPath().equals("item.brush.brush_sand_completed"))
            return Identifier.parse("minecraft:item.brush.brushing.sand.complete");
        for(String suffix:new String[]{"ambient","angry","death","hurt"})
            if(old.getPath().equals("entity.zombie_pigman."+suffix))return Identifier.parse("minecraft:entity.zombified_piglin."+suffix);
        return null;
    }
    /** This old Long memory had no serializer or saved values in vanilla1.15. */
    public static void retiredMemories(RegisterEvent event) {
        event.register(Registries.MEMORY_MODULE_TYPE,Identifier.parse("minecraft:golem_last_seen_time"),
                ()->new net.minecraft.world.entity.ai.memory.MemoryModuleType<Long>(java.util.Optional.empty()));
    }

    public static void items(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<net.minecraft.world.item.Item> mapping : event.<net.minecraft.world.item.Item>getAllMappings(Registries.ITEM)) {
            Identifier replacement = itemReplacement(mapping.getKey());
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(replacement)));
        }
    }
    public static void blocks(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<net.minecraft.world.level.block.Block> mapping : event.<net.minecraft.world.level.block.Block>getAllMappings(Registries.BLOCK)) {
            Identifier replacement = blockReplacement(mapping.getKey());
            if (replacement != null) mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(replacement)));
        }
    }
    static Identifier enchantmentReplacement(Identifier old) {
        return old.equals(Identifier.withDefaultNamespace("sweeping"))
                ?Identifier.withDefaultNamespace("sweeping_edge"):null;
    }

    public static void enchantments(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.item.enchantment.Enchantment>getAllMappings(Registries.ENCHANTMENT)) {
            Identifier replacement=enchantmentReplacement(mapping.getKey());
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

    public static void paintings(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.entity.decoration.painting.PaintingVariant>getAllMappings(Registries.PAINTING_VARIANT)) {
            if(vanillaPaintingVariant(mapping.getKey()))mapping.ignore();
        }
    }

    static boolean vanillaPaintingVariant(Identifier key) {
        // A named vanilla painting now lives in the data-pack registry.
        // Retire only a numeric binding whose exact name the native data owns.
        return key.getNamespace().equals("minecraft") && VanillaData.LOOKUP.lookupOrThrow(Registries.PAINTING_VARIANT)
                .get(net.minecraft.resources.ResourceKey.create(Registries.PAINTING_VARIANT,key)).isPresent();
    }

    static boolean retiredPotion(Identifier key) {
        return key.equals(Identifier.withDefaultNamespace("empty"));
    }

    public static void potions(MissingMappingsEvent event) {
        for(var mapping:event.<net.minecraft.world.item.alchemy.Potion>getAllMappings(Registries.POTION)) {
            // The native item fixer represents the old empty sentinel as an
            // absent potion holder. A real potion here would change its contents.
            if(retiredPotion(mapping.getKey())
                    && net.minecraft.world.item.alchemy.PotionContents.EMPTY.potion().isEmpty())mapping.ignore();
        }
    }

    public static void biomes(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.level.biome.Biome> mapping:event.<net.minecraft.world.level.biome.Biome>getAllMappings(Registries.BIOME)) {
            if(!mapping.getKey().getNamespace().equals("minecraft"))continue;
            Identifier replacement=biomeReplacement(mapping.getKey());
            Identifier current=replacement==null?mapping.getKey():replacement;
            // Native19 chunk palettes use names in the data-pack registry, not
            // the old Forge numeric biome snapshot. Resolve only keys proved
            // by the actual vanilla bootstrap; vanilla still fixes chunk data.
            if(VanillaData.LOOKUP.lookupOrThrow(Registries.BIOME)
                    .get(net.minecraft.resources.ResourceKey.create(Registries.BIOME,current)).isPresent())mapping.ignore();
            else if(replacement!=null)throw new UnsafeRegistryUpgrade(null);
        }
    }
    public static void retiredPointsOfInterest(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.ai.village.poi.PoiType> mapping:
                event.<net.minecraft.world.entity.ai.village.poi.PoiType>getAllMappings(Registries.POINT_OF_INTEREST_TYPE)) {
            // Native PoiTypeRemoveFix3107 removes exactly these unpopulated
            // vanilla records. Resolve their retired numeric bindings only.
            if(mapping.getKey().equals(Identifier.parse("minecraft:unemployed"))
                    || mapping.getKey().equals(Identifier.parse("minecraft:nitwit")))mapping.ignore();
        }
    }
    public static void entities(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.EntityType<?>> mapping:event.<net.minecraft.world.entity.EntityType<?>>getAllMappings(Registries.ENTITY_TYPE)) {
            if (SPLIT_ENTITIES.contains(mapping.getKey())
                    && !BuiltInRegistries.ENTITY_TYPE.containsKey(mapping.getKey())) {
                mapping.ignore();
                continue;
            }
            Identifier replacement=entityReplacement(mapping.getKey());
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(replacement)));
        }
    }
    public static void memories(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<net.minecraft.world.entity.ai.memory.MemoryModuleType<?>> mapping:event.<net.minecraft.world.entity.ai.memory.MemoryModuleType<?>>getAllMappings(Registries.MEMORY_MODULE_TYPE))
            if(mapping.getKey().equals(Identifier.parse("minecraft:opened_doors")))
                mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.containsKey(Identifier.parse("minecraft:doors_to_close")),()->net.minecraftforge.registries.ForgeRegistries.MEMORY_MODULE_TYPES.getValue(Identifier.parse("minecraft:doors_to_close"))));
    }

    public static void sounds(MissingMappingsEvent event) {
        for(MissingMappingsEvent.Mapping<SoundEvent> mapping:event.<SoundEvent>getAllMappings(Registries.SOUND_EVENT)) {
            if ((RETIRED_PARROT_SOUNDS.contains(mapping.getKey()) || RETIRED_121_SOUNDS.contains(mapping.getKey()))
                    && !BuiltInRegistries.SOUND_EVENT.containsKey(mapping.getKey())) {
                // Retire the exact numeric bindings for vanilla events no longer present.
                mapping.ignore();
                continue;
            }
            if (mapping.getKey().equals(OLD_JUNGLE_AND_FOREST_MUSIC)) {
                // Vanilla 1.20 defines separate music for the two biomes.
                // Retire only the obsolete Forge numeric binding.
                if (!BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse("minecraft:music.overworld.jungle"))
                        || !BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse("minecraft:music.overworld.forest")))
                    throw new UnsafeRegistryUpgrade(null);
                mapping.ignore();
                continue;
            }
            Identifier replacement=soundReplacement(mapping.getKey());
            if(replacement!=null)mapping.remap(requireMappingTarget(net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.containsKey(replacement),()->net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(replacement)));
        }
    }
}
