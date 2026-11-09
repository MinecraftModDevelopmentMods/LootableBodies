package cyano.lootable.upgrade;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117RegistryRenameTest {
    final Identifier oldPath=Identifier.parse("minecraft:grass_path");
    final Identifier dirtPath=Identifier.parse("minecraft:dirt_path");
    @Test void grassPathBlockUsesTheActualNativeDirtPath() {
        assertEquals(dirtPath,LegacyRegistryMappings.blockReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH,net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(dirtPath).orElseThrow().value());
        assertNull(LegacyRegistryMappings.blockReplacement(Identifier.parse("anothermod:grass_path")));
    }
    @Test void grassPathItemUsesTheActualNativeDirtPathItem() {
        assertEquals(dirtPath,LegacyRegistryMappings.itemReplacement(oldPath));
        assertSame(net.minecraft.world.level.block.Blocks.DIRT_PATH.asItem(),net.minecraft.core.registries.BuiltInRegistries.ITEM.get(dirtPath).orElseThrow().value());
        assertNull(LegacyRegistryMappings.itemReplacement(Identifier.parse("anothermod:grass_path")));
    }
    @Test void berryPickingSoundUsesTheActualNativeBlockSound() {
        Identifier old=Identifier.parse("minecraft:item.sweet_berries.pick_from_bush");
        Identifier current=Identifier.parse("minecraft:block.sweet_berry_bush.pick_berries");
        assertEquals(current,LegacyRegistryMappings.soundReplacement(old));
        assertSame(net.minecraft.sounds.SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(current).orElseThrow().value());
        assertNull(LegacyRegistryMappings.soundReplacement(Identifier.parse("anothermod:item.sweet_berries.pick_from_bush")));
    }
}
