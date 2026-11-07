package cyano.lootable.upgrade;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117RegistryRenameTest {
    final ResourceLocation oldPath=ResourceLocation.parse("minecraft:grass_path");
    final ResourceLocation dirtPath=ResourceLocation.parse("minecraft:dirt_path");
    @Test void grassPathBlockUsesTheActualNativeDirtPath() {
        assertEquals(dirtPath,LegacyRegistryMappings.blockReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH,net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(dirtPath));
        assertNull(LegacyRegistryMappings.blockReplacement(ResourceLocation.parse("anothermod:grass_path")));
    }
    @Test void grassPathItemUsesTheActualNativeDirtPathItem() {
        assertEquals(dirtPath,LegacyRegistryMappings.itemReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH.asItem(),net.minecraft.core.registries.BuiltInRegistries.ITEM.get(dirtPath));
        assertNull(LegacyRegistryMappings.itemReplacement(ResourceLocation.parse("anothermod:grass_path")));
    }
    @Test void berryPickingSoundUsesTheActualNativeBlockSound() {
        ResourceLocation old=ResourceLocation.parse("minecraft:item.sweet_berries.pick_from_bush");
        ResourceLocation current=ResourceLocation.parse("minecraft:block.sweet_berry_bush.pick_berries");
        assertEquals(current,LegacyRegistryMappings.soundReplacement(old));
        assertSame(net.minecraft.sounds.SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(current));
        assertNull(LegacyRegistryMappings.soundReplacement(ResourceLocation.parse("anothermod:item.sweet_berries.pick_from_bush")));
    }
}
