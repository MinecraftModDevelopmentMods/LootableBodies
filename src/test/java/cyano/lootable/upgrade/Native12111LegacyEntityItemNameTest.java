package cyano.lootable.upgrade;

import com.mojang.datafixers.DSL;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.util.datafix.fixes.UnflattenTextComponentFix;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Native12111LegacyEntityItemNameTest {
    @Test
    void keepsAPlainNameFromASealedLegacyChestWithoutNativeTextErrors() throws Exception {
        CompoundTag source = TagParser.parseCompoundFully("""
                {id:"minecraft:chest",x:1,y:64,z:2,Items:[
                  {id:"minecraft:pig_spawn_egg",Slot:3b,Count:7b,
                   tag:{EntityTag:{id:"minecraft:pig",CustomName:"Saved pig egg"},opaque:"Saved pig egg"}}]}
                """);
        CompoundTag fixed = withoutFallbackError(source, References.BLOCK_ENTITY, 2230);
        CompoundTag stack = fixed.getListOrEmpty("Items").getCompoundOrEmpty(0);
        assertEquals(7, stack.getIntOr("count", 0));
        assertEquals(3, stack.getIntOr("Slot", -1));
        assertEquals("minecraft:pig_spawn_egg", stack.getStringOr("id", ""));
        CompoundTag components = stack.getCompoundOrEmpty("components");
        assertEquals("Saved pig egg", components.getCompoundOrEmpty("minecraft:entity_data").getStringOr("CustomName", ""));
        assertEquals("Saved pig egg", components.getCompoundOrEmpty("minecraft:custom_data").getStringOr("opaque", ""));
    }

    @Test
    void keepsThePlainNameThroughTheComponentBoundaryAndLeavesRichTextAlone() throws Exception {
        CompoundTag stack = TagParser.parseCompoundFully("""
                {id:"minecraft:pig_spawn_egg",count:7,Slot:3b,components:{
                  "minecraft:entity_data":{id:"minecraft:pig",CustomName:"Saved pig egg"}}}
                """);
        CompoundTag fixed = withoutFallbackError(stack, References.ITEM_STACK, 3955);
        assertEquals(stack, fixed);

        CompoundTag rich = stack.copy();
        rich.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:entity_data")
                .putString("CustomName", "{\"text\":\"Named pig\",\"color\":\"red\"}");
        var vanilla = NativeVanillaDataFixer.get();
        CompoundTag expected = NativeNbt.update(vanilla, References.ITEM_STACK, rich.copy(), 3955, 4671);
        assertEquals(expected, NativeNbt.update(new NativeSafeFixer(vanilla, null), References.ITEM_STACK, rich, 3955, 4671));
        assertEquals(stack, NativeNbt.update(new NativeSafeFixer(vanilla, null), References.ITEM_STACK, stack, 4671, 4671));
    }

    private static CompoundTag withoutFallbackError(CompoundTag input, DSL.TypeReference type, int version) {
        var vanilla = NativeVanillaDataFixer.get();
        CompoundTag before = input.copy();
        List<String> errors = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger(UnflattenTextComponentFix.class);
        var capture = new AbstractAppender("NativeTextFixture", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.ERROR) errors.add(event.getMessage().getFormattedMessage());
            }
        };
        capture.start();
        logger.addAppender(capture);
        try {
            CompoundTag nativeResult = NativeNbt.update(vanilla, type, input.copy(), version, 4671);
            assertTrue(errors.stream().anyMatch(message -> message.contains("Saved pig egg")), errors.toString());
            errors.clear();
            CompoundTag fixed = NativeNbt.update(new NativeSafeFixer(vanilla, null), type, input, version, 4671);
            assertEquals(before, input);
            assertEquals(nativeResult, fixed);
            assertTrue(errors.isEmpty(), errors.toString());
            return fixed;
        } finally {
            logger.removeAppender(capture);
            capture.stop();
        }
    }
}
