package cyano.lootable.upgrade;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
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
            Identifier old = Identifier.parse("minecraft:pottery_shard_" + design);
            Identifier replacement = Identifier.parse("minecraft:" + design + "_pottery_sherd");
            CompoundTag item = new CompoundTag();
            item.putString("id", old.toString());
            item.putByte("Count", (byte) 3);
            item.put("tag", new CompoundTag());
            item.getCompoundOrEmpty("tag").putString("LootableBodiesFixture", design);
            CompoundTag before = item.copy();
            CompoundTag fixed = NativeNbt.update(DataFixers.getDataFixer(), References.ITEM_STACK, item, 3337, 3465);
            assertEquals(replacement.toString(), fixed.getStringOr("id", ""));
            assertEquals(3, fixed.getByteOr("Count", (byte) 0));
            assertEquals(before.getCompoundOrEmpty("tag"), fixed.getCompoundOrEmpty("tag"));
            assertEquals(before, item);
            assertTrue(BuiltInRegistries.ITEM.containsKey(replacement));
            assertEquals(replacement, LegacyRegistryMappings.itemReplacement(old));
        }
        assertNull(LegacyRegistryMappings.itemReplacement(Identifier.parse("anothermod:pottery_shard_archer")));
        assertNull(LegacyRegistryMappings.itemReplacement(Identifier.parse("minecraft:pottery_shard_unknown")));
    }

    @Test void released119BrushSoundsUseTheNative120Registrations() {
        Identifier brushing = Identifier.parse("minecraft:item.brush.brushing.generic");
        Identifier complete = Identifier.parse("minecraft:item.brush.brushing.sand.complete");
        assertSame(SoundEvents.BRUSH_GENERIC, BuiltInRegistries.SOUND_EVENT.get(brushing).orElseThrow().value());
        assertSame(SoundEvents.BRUSH_SAND_COMPLETED, BuiltInRegistries.SOUND_EVENT.get(complete).orElseThrow().value());
        assertEquals(brushing, LegacyRegistryMappings.soundReplacement(Identifier.parse("minecraft:item.brush.brushing")));
        assertEquals(complete, LegacyRegistryMappings.soundReplacement(Identifier.parse("minecraft:item.brush.brush_sand_completed")));
        assertNull(LegacyRegistryMappings.soundReplacement(Identifier.parse("anothermod:item.brush.brushing")));
    }

    @Test void splitBiomeMusicOnlyResolvesItsRetiredNumericSoundBinding() throws Exception {
        Identifier retired = Identifier.parse("minecraft:music.overworld.jungle_and_forest");
        assertFalse(BuiltInRegistries.SOUND_EVENT.containsKey(retired));
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse("minecraft:music.overworld.jungle")));
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse("minecraft:music.overworld.forest")));
        assertEquals(MissingMappingsEvent.Action.IGNORE, soundAction(retired));
        assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(Identifier.parse("anothermod:music.overworld.jungle_and_forest")));
        assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(Identifier.parse("minecraft:music.overworld.unknown")));
    }

    @Test void retiredParrotImitationsOnlyResolveTheFiveObsoleteVanillaSoundBindings() throws Exception {
        for (String mob : List.of("enderman", "panda", "polar_bear", "wolf", "zombie_pigman")) {
            String path = "entity.parrot.imitate." + mob;
            Identifier retired = Identifier.withDefaultNamespace(path);
            assertFalse(BuiltInRegistries.SOUND_EVENT.containsKey(retired), path);
            assertEquals(MissingMappingsEvent.Action.IGNORE, soundAction(retired), path);
            assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(Identifier.fromNamespaceAndPath("othermod", path)), path);
        }
        Identifier piglin = Identifier.withDefaultNamespace("entity.parrot.imitate.piglin");
        assertTrue(BuiltInRegistries.SOUND_EVENT.containsKey(piglin));
        assertEquals(MissingMappingsEvent.Action.DEFAULT, soundAction(piglin));
        assertEquals(MissingMappingsEvent.Action.DEFAULT,
                soundAction(Identifier.withDefaultNamespace("entity.parrot.imitate.unknown")));
    }

    private static MissingMappingsEvent.Action soundAction(Identifier key) throws Exception {
        MissingMappingsEvent.Mapping<SoundEvent> mapping = new MissingMappingsEvent.Mapping<>(null, null, key, 17);
        LegacyRegistryMappings.sounds(new MissingMappingsEvent(Registries.SOUND_EVENT, null, List.of(mapping)));
        Field action = mapping.getClass().getDeclaredField("action");
        action.setAccessible(true);
        return (MissingMappingsEvent.Action) action.get(mapping);
    }
}
