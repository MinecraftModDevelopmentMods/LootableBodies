package cyano.lootable.upgrade;

import java.nio.file.Files;
import java.nio.file.Paths;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.fixes.References;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117VanillaTest {
    private CompoundTag flatSettings() throws Exception {
        CompoundTag settings=TagParser.parseTag(Files.readString(Paths.get("src/test/resources/native116-worldgen.snbt")));
        settings.getCompound("dimensions").getCompound("minecraft:overworld").getCompound("generator")
                .getCompound("settings").getList("layers",10).getCompound(2).putString("block","minecraft:grass_path");
        return settings;
    }
    private CompoundTag nativeSettings(CompoundTag input) {
        CompoundTag fixed=NativeNbt.update(NativeVanillaDataFixer.get(),References.WORLD_GEN_SETTINGS,input,2586,2730);
        assertEquals("minecraft:dirt_path",fixed.getCompound("dimensions").getCompound("minecraft:overworld")
                .getCompound("generator").getCompound("settings").getList("layers",10).getCompound(2).getString("block"));
        return fixed;
    }
    @Test void completeSettingsMigrateFlatLayersEvenWithDamagedStamps() throws Exception {
        CompoundTag raw=flatSettings(),before=raw.copy(),expected=nativeSettings(raw);
        for(int source:new int[]{-1,0,512,2230,2586}) {
            CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),
                    References.WORLD_GEN_SETTINGS,raw,source,2730);
            assertEquals(expected,actual,"source="+source);assertEquals(before,raw);
        }
    }
    @Test void protectedLevelFieldStillReceivesNative17WorldgenChanges() throws Exception {
        CompoundTag raw=flatSettings(),expected=nativeSettings(raw),level=new CompoundTag();
        level.put("WorldGenSettings",raw);level.putString("CustomKeepTag","retain me");
        CompoundTag before=level.copy();
        CompoundTag actual=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.LEVEL,level,-1,2730);
        assertEquals(expected,actual.getCompound("WorldGenSettings"));
        assertEquals("retain me",actual.getString("CustomKeepTag"));assertEquals(before,level);
    }
    @Test void vanillaBlockAndItemRenamesUseTheActualNativeOracle() {
        CompoundTag block=new CompoundTag();block.putString("Name","minecraft:grass_path");
        CompoundTag item=new CompoundTag();item.putString("id","minecraft:grass_path");item.putByte("Count",(byte)3);
        CompoundTag blockBefore=block.copy(),itemBefore=item.copy();
        com.mojang.datafixers.DataFixer safe=UpgradePreservation.wrapper(NativeVanillaDataFixer.get());
        assertEquals("minecraft:dirt_path",NativeNbt.update(safe,References.BLOCK_STATE,block,2586,2730).getString("Name"));
        assertEquals("minecraft:dirt_path",NativeNbt.update(safe,References.ITEM_STACK,item,2586,2730).getString("id"));
        assertEquals(blockBefore,block);assertEquals(itemBefore,item);
    }
}
