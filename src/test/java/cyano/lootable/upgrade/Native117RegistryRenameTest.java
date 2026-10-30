package cyano.lootable.upgrade;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117RegistryRenameTest {
    final ResourceLocation oldPath=new ResourceLocation("minecraft:grass_path");
    final ResourceLocation dirtPath=new ResourceLocation("minecraft:dirt_path");
    @Test void grassPathBlockUsesTheActualNativeDirtPath() {
        assertEquals(dirtPath,LegacyRegistryMappings.blockReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH,Registry.BLOCK.get(dirtPath));
        assertNull(LegacyRegistryMappings.blockReplacement(new ResourceLocation("anothermod:grass_path")));
    }
    @Test void grassPathItemUsesTheActualNativeDirtPathItem() {
        assertEquals(dirtPath,LegacyRegistryMappings.itemReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH.asItem(),Registry.ITEM.get(dirtPath));
        assertNull(LegacyRegistryMappings.itemReplacement(new ResourceLocation("anothermod:grass_path")));
    }
    @Test void berryPickingSoundUsesTheActualNativeBlockSound() {
        ResourceLocation old=new ResourceLocation("minecraft:item.sweet_berries.pick_from_bush");
        ResourceLocation current=new ResourceLocation("minecraft:block.sweet_berry_bush.pick_berries");
        assertEquals(current,LegacyRegistryMappings.soundReplacement(old));
        assertSame(net.minecraft.sounds.SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,Registry.SOUND_EVENT.get(current));
        assertNull(LegacyRegistryMappings.soundReplacement(new ResourceLocation("anothermod:item.sweet_berries.pick_from_bush")));
    }
}
