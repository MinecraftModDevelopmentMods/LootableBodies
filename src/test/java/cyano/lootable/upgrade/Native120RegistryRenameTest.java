package cyano.lootable.upgrade;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraftforge.registries.MissingMappingsEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native120RegistryRenameTest {
    @Test void released119PotteryItemsMatchTheVanillaItemStackConversion() {
        for (String design : List.of("archer", "arms_up", "prize", "skull")) {
            ResourceLocation old = new ResourceLocation("minecraft:pottery_shard_" + design);
            ResourceLocation replacement = new ResourceLocation("minecraft:" + design + "_pottery_sherd");
            CompoundTag item = new CompoundTag();
            item.putString("id", old.toString());
            item.putByte("Count", (byte) 3);
            item.put("tag", new CompoundTag());
            item.getCompound("tag").putString("LootableBodiesFixture", design);
            CompoundTag before = item.copy();
            CompoundTag fixed = NativeNbt.update(DataFixers.getDataFixer(), References.ITEM_STACK, item, 3337, 3465);
            assertEquals(replacement.toString(), fixed.getString("id"));
            assertEquals(3, fixed.getByte("Count"));
            assertEquals(before.getCompound("tag"), fixed.getCompound("tag"));
            assertEquals(before, item);
            assertTrue(BuiltInRegistries.ITEM.containsKey(replacement));
            assertEquals(replacement, LegacyRegistryMappings.itemReplacement(old));
        }
        assertNull(LegacyRegistryMappings.itemReplacement(new ResourceLocation("anothermod:pottery_shard_archer")));
        assertNull(LegacyRegistryMappings.itemReplacement(new ResourceLocation("minecraft:pottery_shard_unknown")));
    }

    @Test void released119BrushSoundsUseTheNative120Registrations() {
        ResourceLocation brushing = new ResourceLocation("minecraft:item.brush.brushing.generic");
        ResourceLocation complete = new ResourceLocation("minecraft:item.brush.brushing.sand.complete");
        assertSame(SoundEvents.BRUSH_GENERIC, BuiltInRegistries.SOUND_EVENT.get(brushing));
        assertSame(SoundEvents.BRUSH_SAND_COMPLETED, BuiltInRegistries.SOUND_EVENT.get(complete));
        assertEquals(brushing, LegacyRegistryMappings.soundReplacement(new ResourceLocation("minecraft:item.brush.brushing")));
        assertEquals(complete, LegacyRegistryMappings.soundReplacement(new ResourceLocation("minecraft:item.brush.brush_sand_completed")));
        assertNull(LegacyRegistryMappings.soundReplacement(new ResourceLocation("anothermod:item.brush.brushing")));
    }

    @Test void splitBiomeMusicOnlyResolvesItsRetiredNumericSoundBinding() throws Exception {
        ResourceLocation retired = new ResourceLocation("minecraft:music.overworld.jungle_and_forest");
        assertFalse(BuiltInRegistries.SOUND_EVENT.containsKey(retired));
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(new ResourceLocation("minecraft:music.overworld.jungle")));
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(new ResourceLocation("minecraft:music.overworld.forest")));
        assertEquals(MissingMappingsEvent.Action.IGNORE, soundAction(retired));
        assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(new ResourceLocation("anothermod:music.overworld.jungle_and_forest")));
        assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(new ResourceLocation("minecraft:music.overworld.unknown")));
    }

    private static MissingMappingsEvent.Action soundAction(ResourceLocation key) throws Exception {
        MissingMappingsEvent.Mapping<SoundEvent> mapping = new MissingMappingsEvent.Mapping<>(null, null, key, 17);
        LegacyRegistryMappings.sounds(new MissingMappingsEvent(Registries.SOUND_EVENT, null, List.of(mapping)));
        Field action = mapping.getClass().getDeclaredField("action");
        action.setAccessible(true);
        return (MissingMappingsEvent.Action) action.get(mapping);
    }
}
