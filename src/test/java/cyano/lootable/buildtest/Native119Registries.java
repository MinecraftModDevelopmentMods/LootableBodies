package cyano.lootable.buildtest;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** One native vanilla bootstrap per test JVM; never historical world evidence. */
public final class Native119Registries {
    private Native119Registries(){ }
    private static final class Prepared {
        static final HolderLookup.Provider LOOKUP=net.minecraft.data.registries.VanillaRegistries.createLookup();
    }
    public static HolderLookup.Provider lookup(){return Prepared.LOOKUP;}
    public static RegistryAccess forDimensions(WorldDimensions dimensions) {
        var stems=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.dimension.LevelStem>(
                net.minecraft.core.registries.Registries.LEVEL_STEM,com.mojang.serialization.Lifecycle.stable());
        dimensions.dimensions().forEach((key,value)->stems.register(key,value,net.minecraft.core.RegistrationInfo.BUILT_IN));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(stems.freeze())) {
            @Override public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                Optional<HolderLookup.RegistryLookup<T>> saved=super.lookup(key);
                return saved.isPresent()?saved:Native119Registries.lookup().lookup(key);
            }
        };
    }
}
