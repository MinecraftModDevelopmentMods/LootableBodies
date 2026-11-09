package cyano.lootable.buildtest;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
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
        var types = new net.minecraft.core.MappedRegistry<net.minecraft.world.level.dimension.DimensionType>(
                net.minecraft.core.registries.Registries.DIMENSION_TYPE, com.mojang.serialization.Lifecycle.stable());
        lookup().lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE).listElements()
                .forEach(holder -> types.register(holder.key(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        return new RegistryAccess.ImmutableRegistryAccess(List.of(stems.freeze(), types.freeze()));
    }
}
