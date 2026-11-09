package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;

import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.fixes.References;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native118WorldgenTest {
    private CompoundTag source()throws Exception {
        CompoundTag settings=TagParser.parseCompoundFully(Files.readString(Paths.get("src/test/resources/native116-worldgen.snbt")));
        typedList(settings.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").getCompoundOrEmpty("generator").getCompoundOrEmpty("settings"), "layers", 10).getCompoundOrEmpty(2).putString("block","minecraft:grass_path");
        return settings;
    }
    @Test void actualNative18GeneratorConversionRunsDespiteDamagedCompleteSettingsStamps()throws Exception {
        CompoundTag raw=source(),before=raw.copy();
        CompoundTag expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.WORLD_GEN_SETTINGS,raw.copy(),2586,2975);
        for(int stamp:new int[]{-1,0,512,2230,2586}) {
            CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.WORLD_GEN_SETTINGS,raw,stamp,2975);
            assertEquals(expected,actual,"stamp="+stamp);assertEquals(before,raw);
        }
        ListTag layers=typedList(expected.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").getCompoundOrEmpty("generator").getCompoundOrEmpty("settings"), "layers", 10);
        // Genuine 1.18 height conversion prepends 64 air blocks, retaining the
        // original third layer at index3 and the same world-space height.
        assertEquals("minecraft:air",layers.getCompoundOrEmpty(0).getStringOr("block", ""));assertEquals(64,layers.getCompoundOrEmpty(0).getIntOr("height", 0));
        assertEquals("minecraft:dirt_path",layers.getCompoundOrEmpty(3).getStringOr("block", ""));
    }
    @Test void levelProtectionKeepsUnknownTagsWhileItsGeneratorReachesNative18()throws Exception {
        CompoundTag raw=source(),level=new CompoundTag();level.put("WorldGenSettings",raw);level.putLong("CustomKeepTag",1234567890123L);
        CompoundTag before=level.copy(),expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.WORLD_GEN_SETTINGS,raw.copy(),2586,2975);
        CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.LEVEL,level,-1,2975);
        assertEquals(expected,actual.get("WorldGenSettings"));assertEquals(1234567890123L,actual.getLongOr("CustomKeepTag", 0L));assertEquals(before,level);
    }
}
