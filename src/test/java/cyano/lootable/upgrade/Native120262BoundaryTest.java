package cyano.lootable.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import static cyano.lootable.buildtest.TestNativeNbt.typedList;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic boundary regressions using the actual 26.2 native converter. */
class Native120262BoundaryTest {
    @TempDir Path world;
    @AfterEach void close() { UpgradePreservation.close(); }
    private static CompoundTag source() {
        CompoundTag result=new CompoundTag(); result.putInt("DataVersion",4790); return result;
    }
    private static CompoundTag stack(String id) {
        CompoundTag result=new CompoundTag(); result.putString("id",id);
        result.putInt("count",3); result.putByte("Slot",(byte)47); return result;
    }
    private static CompoundTag bed() {
        CompoundTag result=stack("minecraft:red_bed"), components=new CompoundTag(), data=new CompoundTag();
        data.putString("id","minecraft:bed"); data.putString("OriginalCustomData","keep in recovery");
        components.put("minecraft:block_entity_data",data);
        components.putString("minecraft:custom_name","Kept bed"); result.put("components",components); return result;
    }
    private static CompoundTag nativeItem(CompoundTag input) {
        return NativeNbt.update(NativeVanillaDataFixer.get(),References.ITEM_STACK,input.copy(),4790,4903);
    }
    private static CompoundTag worldContext(String release) {
        CompoundTag result=new CompoundTag(), fml=new CompoundTag(), mod=new CompoundTag();
        mod.putString("ModId","minecraft"); mod.putString("ModVersion",release);
        ListTag mods=new ListTag(); mods.add(mod); fml.put("LoadingModList",mods); result.put("WorldFML",fml); return result;
    }
    @Test void targetAndWriterMarkerMatchTheActualNewBoundary() {
        assertEquals(4903,net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals(4903,UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(15,UpgradePreservation.DATA_VERSION);
    }
    @Test void releasedMinecraftHeaderRemains4790AndNewHeaderIs4903() {
        assertEquals(4790,UpgradePreservation.chunkSourceVersion(worldContext("26.1.2")));
        assertEquals(4903,UpgradePreservation.chunkSourceVersion(worldContext("26.2")));
    }
    @Test void marker14OverridesADamagedDetachedEntityStamp() {
        CompoundTag body=Native117RegionFixtures.body(UUID.randomUUID(),14), original=body.copy();
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.ENTITY_TREE,body,512,4903);
        assertEquals(4790,fixed.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion",0));
        assertEquals(15,fixed.getIntOr(UpgradePreservation.VERSION,0));
        assertEquals(original,body);
    }
    @Test void marker14JournalRetainsEveryOriginalAndExactRegionBytes() throws Exception {
        UUID id=UUID.randomUUID(); CompoundTag body=Native117RegionFixtures.body(id,14), original=body.copy();
        CompoundTag chunk=Native117RegionFixtures.chunk(512,body);
        Path path=Native117RegionFixtures.write(world,chunk); byte[] bytes=Files.readAllBytes(path);
        UpgradeJournal journal=UpgradeJournal.scan(world);
        assertEquals(4790,journal.originalFor(id,0,0).getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion",0));
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,source());
        ListTag records=typedList(staged,"LootableBodiesUnresolved",10); assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {
            assertEquals("Equipment",records.getCompoundOrEmpty(slot).getStringOr("Source",""));
            assertEquals(typedList(body,"Equipment",10).getCompoundOrEmpty(slot),records.getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));
            assertEquals(4790,records.getCompoundOrEmpty(slot).getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion",0));
        }
        assertEquals("Aux",records.getCompoundOrEmpty(48).getStringOr("Source",""));
        assertEquals(typedList(original,"LootableBodiesUnresolved",10).getCompoundOrEmpty(0),records.getCompoundOrEmpty(49));
        assertArrayEquals(bytes,Files.readAllBytes(path)); assertEquals(original,body);
    }
    @Test void actualNativeBedRemovalIsConvertedWithInputUntouched() {
        CompoundTag raw=bed();raw.getCompoundOrEmpty("components").getCompoundOrEmpty("minecraft:block_entity_data").remove("OriginalCustomData");
        CompoundTag original=raw.copy(), expected=raw.copy();
        expected.getCompoundOrEmpty("components").remove("minecraft:block_entity_data");
        assertEquals(expected,nativeItem(raw));
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertEquals(expected,ItemMigration.convert(raw,source())); assertEquals(original,raw);
    }
    @Test void bedPayloadThatNativeWouldDiscardRemainsUnresolved() {
        CompoundTag raw=bed(),original=raw.copy();
        assertFalse(nativeItem(raw).getCompoundOrEmpty("components").contains("minecraft:block_entity_data"));
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertNull(ItemMigration.convert(raw,source()));assertEquals(original,raw);
    }
    @Test void plainReleasedStackKeepsItsCountSlotAndCustomData() {
        CompoundTag raw=stack("minecraft:diamond"), components=new CompoundTag(), custom=new CompoundTag();
        custom.putString("Original","preserved");components.put("minecraft:custom_data",custom);raw.put("components",components);
        CompoundTag before=raw.copy(); UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertEquals(before,nativeItem(raw));assertEquals(before,ItemMigration.convert(raw,source()));assertEquals(before,raw);
    }
    @Test void nativeNameTagAttributeRenameIsPreserved() {
        CompoundTag raw=stack("minecraft:stick"),components=new CompoundTag(), modifier=new CompoundTag();
        modifier.putString("type","minecraft:nameplate_distance");modifier.putString("id","test:kept");
        modifier.putDouble("amount",2);modifier.putString("operation","add_value");
        ListTag modifiers=new ListTag();modifiers.add(modifier);components.put("minecraft:attribute_modifiers",modifiers);raw.put("components",components);
        CompoundTag before=raw.copy(),expected=raw.copy();
        typedList(expected.getCompoundOrEmpty("components"),"minecraft:attribute_modifiers",10).getCompoundOrEmpty(0).putString("type","minecraft:name_tag_distance");
        assertEquals(expected,nativeItem(raw));UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertEquals(expected,ItemMigration.convert(raw,source()));assertEquals(before,raw);
    }
    @Test void unrecognizedReleasedStackFieldsRemainUnresolved() {
        CompoundTag raw=stack("minecraft:diamond"); raw.putString("UnrecognizedData","retain");
        CompoundTag before=raw.copy();UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertNull(ItemMigration.convert(raw,source()));assertEquals(before,raw);
    }
}
