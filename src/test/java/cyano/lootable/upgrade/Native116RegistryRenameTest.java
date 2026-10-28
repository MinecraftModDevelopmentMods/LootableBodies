package cyano.lootable.upgrade;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.datafix.TypeReferences;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Native116RegistryRenameTest {
    @Test void zombiePigmanMappingsAgreeWithNativeEntityAndItemFixes(){
        ResourceLocation old=new ResourceLocation("minecraft:zombie_pigman"),current=new ResourceLocation("minecraft:zombified_piglin");
        assertEquals(current,LegacyRegistryMappings.entityReplacement(old));
        assertEquals(new ResourceLocation("minecraft:zombified_piglin_spawn_egg"),LegacyRegistryMappings.itemReplacement(new ResourceLocation("minecraft:zombie_pigman_spawn_egg")));
        CompoundNBT entity=new CompoundNBT();entity.putString("id",old.toString());
        assertEquals(current.toString(),NativeNbt.update(DataFixesManager.getDataFixer(),TypeReferences.ENTITY_TYPE,entity,2230,2586).getString("id"));
        CompoundNBT item=new CompoundNBT();item.putString("id","minecraft:zombie_pigman_spawn_egg");item.putByte("Count",(byte)7);
        assertEquals("minecraft:zombified_piglin_spawn_egg",NativeNbt.update(DataFixesManager.getDataFixer(),TypeReferences.ITEM_STACK,item,2230,2586).getString("id"));
    }
    @Test void onlyExactRetiredVanillaSoundsAndBiomesAreRenamed(){
        for(String sound:new String[]{"ambient","angry","death","hurt"})assertEquals(new ResourceLocation("minecraft:entity.zombified_piglin."+sound),LegacyRegistryMappings.soundReplacement(new ResourceLocation("minecraft:entity.zombie_pigman."+sound)));
        assertEquals(new ResourceLocation("minecraft:music.nether.nether_wastes"),LegacyRegistryMappings.soundReplacement(new ResourceLocation("minecraft:music.nether")));
        for(String key:new String[]{"othermod:zombie_pigman","minecraft:unrecognised","minecraft:zombified_piglin"})assertNull(LegacyRegistryMappings.entityReplacement(new ResourceLocation(key)));
        assertNull(LegacyRegistryMappings.soundReplacement(new ResourceLocation("othermod:music.nether")));
    }
    @Test void nativeBiomesUseTheirOwnDynamicRegistry() {
        assertEquals(new ResourceLocation("minecraft:worldgen/biome"),net.minecraftforge.registries.ForgeRegistries.Keys.BIOMES.getLocation());
    }
    @Test void failedMappingsEscapeTheNativeLevelReaderFallback() {
        Error absent=assertThrows(Error.class,()->LegacyRegistryMappings.requireMappingTarget(()->null));
        assertTrue(absent.getMessage().contains("unsafe vanilla registry conversion"));
        RuntimeException cause=new IllegalStateException("fixture lookup");
        Error failed=assertThrows(Error.class,()->LegacyRegistryMappings.requireMappingTarget(()->{throw cause;}));
        assertSame(cause,failed.getCause());Object valid=new Object();assertSame(valid,LegacyRegistryMappings.requireMappingTarget(()->valid));
    }
    @Test void missingTargetsCannotUseARegistrysDefaultPigOrAir() {
        java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(Error.class,()->LegacyRegistryMappings.requireMappingTarget(false,()->{called.set(true);return "default air";}));
        assertFalse(called.get());assertEquals("real target",LegacyRegistryMappings.requireMappingTarget(true,()->"real target"));
    }
}
