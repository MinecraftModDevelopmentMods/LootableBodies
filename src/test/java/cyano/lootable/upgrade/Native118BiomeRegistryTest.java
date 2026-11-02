package cyano.lootable.upgrade;

import com.mojang.serialization.Dynamic;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.BiomeFix;
import net.minecraft.util.datafix.fixes.CavesAndCliffsRenames;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Registry aliases must agree with actual vanilla biome conversion. */
class Native118BiomeRegistryTest {
    private String nativeName(String name,int source){
        return net.minecraft.util.datafix.DataFixers.getDataFixer().update(References.BIOME,
                new Dynamic<>(NbtOps.INSTANCE,StringTag.valueOf(name)),source,2975).asString().getOrThrow(false,error->{throw new AssertionError(error);});
    }
    @Test void everyCavesAndCliffsAliasMatchesTheActualNativeFixAndRegisteredTarget(){
        CavesAndCliffsRenames.RENAMES.forEach((old,current)->{
            assertEquals(current,nativeName(old,2730),old);
            ResourceLocation target=new ResourceLocation(current);
            assertTrue(BuiltinRegistries.BIOME.containsKey(target),current);
            assertEquals(target,LegacyRegistryMappings.biomeReplacement(new ResourceLocation(old)),old);
        });
    }
    @Test void earlierNativeBiomeRenamesChainIntoTheCurrentNativeBiomeNames(){
        BiomeFix.BIOMES.forEach((old,renamed)->{
            String current=CavesAndCliffsRenames.RENAMES.getOrDefault(renamed,renamed);
            if(current.equals("minecraft:deep_warm_ocean"))current="minecraft:warm_ocean";
            ResourceLocation target=new ResourceLocation(current);
            assertTrue(BuiltinRegistries.BIOME.containsKey(target),current);
            assertEquals(target,LegacyRegistryMappings.biomeReplacement(new ResourceLocation(old)),old);
        });
    }
    @Test void replacedNetherAndDeepWarmOceanFollowTheirSeparateActualNativeRules(){
        for(String old:new String[]{"minecraft:nether","minecraft:deep_warm_ocean"})
            assertEquals(new ResourceLocation(nativeName(old,2230)),LegacyRegistryMappings.biomeReplacement(new ResourceLocation(old)));
    }
    @Test void healthyUnknownAndOtherModNamesDoNotReceiveGuessedReplacements(){
        for(String name:new String[]{"minecraft:plains","minecraft:forest","minecraft:unknown_saved_biome","anothermod:wooded_hills"})
            assertNull(LegacyRegistryMappings.biomeReplacement(new ResourceLocation(name)),name);
    }
}
