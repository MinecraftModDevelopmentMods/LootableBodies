package cyano.lootable.upgrade;

import java.nio.file.*;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.fixes.References;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native118WorldgenTest {
    private CompoundTag source()throws Exception {
        CompoundTag settings=TagParser.parseTag(Files.readString(Paths.get("src/test/resources/native116-worldgen.snbt")));
        settings.getCompound("dimensions").getCompound("minecraft:overworld").getCompound("generator").getCompound("settings").getList("layers",10).getCompound(2).putString("block","minecraft:grass_path");
        return settings;
    }
    @Test void actualNative18GeneratorConversionRunsDespiteDamagedCompleteSettingsStamps()throws Exception {
        CompoundTag raw=source(),before=raw.copy();
        CompoundTag expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.WORLD_GEN_SETTINGS,raw.copy(),2586,2975);
        for(int stamp:new int[]{-1,0,512,2230,2586}) {
            CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.WORLD_GEN_SETTINGS,raw,stamp,2975);
            assertEquals(expected,actual,"stamp="+stamp);assertEquals(before,raw);
        }
        ListTag layers=expected.getCompound("dimensions").getCompound("minecraft:overworld").getCompound("generator").getCompound("settings").getList("layers",10);
        // Genuine 1.18 height conversion prepends 64 air blocks, retaining the
        // original third layer at index3 and the same world-space height.
        assertEquals("minecraft:air",layers.getCompound(0).getString("block"));assertEquals(64,layers.getCompound(0).getInt("height"));
        assertEquals("minecraft:dirt_path",layers.getCompound(3).getString("block"));
    }
    @Test void levelProtectionKeepsUnknownTagsWhileItsGeneratorReachesNative18()throws Exception {
        CompoundTag raw=source(),level=new CompoundTag();level.put("WorldGenSettings",raw);level.putLong("CustomKeepTag",1234567890123L);
        CompoundTag before=level.copy(),expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.WORLD_GEN_SETTINGS,raw.copy(),2586,2975);
        CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.LEVEL,level,-1,2975);
        assertEquals(expected,actual.get("WorldGenSettings"));assertEquals(1234567890123L,actual.getLong("CustomKeepTag"));assertEquals(before,level);
    }
}
