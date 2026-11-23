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

/** Controlled boundary fixtures; genuine released-writer proof runs separately. */
class Native120263BoundaryTest {
    @TempDir Path world;
    @AfterEach void close() { UpgradePreservation.close(); System.out.println("NATIVE263_BOUNDARY_FIXTURE="+world); }

    private static CompoundTag header(String release) {
        CompoundTag result=new CompoundTag(), fml=new CompoundTag(), mod=new CompoundTag();
        result.putInt("DataVersion",512);
        mod.putString("ModId","minecraft"); mod.putString("ModVersion",release);
        ListTag mods=new ListTag(); mods.add(mod); fml.put("LoadingModList",mods);
        result.put("WorldFML",fml); return result;
    }

    @Test void targetMatchesActualNative263AndNewOwnedMarker() {
        assertEquals(5023,net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        assertEquals(5023,UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(16,UpgradePreservation.DATA_VERSION);
    }

    @Test void released262And263HeadersKeepDifferentSourceFamilies() {
        assertEquals(4903,UpgradePreservation.chunkSourceVersion(header("26.2")));
        assertEquals(5023,UpgradePreservation.chunkSourceVersion(header("26.3")));
        assertEquals(4790,UpgradePreservation.chunkSourceVersion(header("26.1.2")));
    }

    @Test void marker15SurvivesDamagedOuterStampAsReleased4903() throws Exception {
        UUID id=UUID.randomUUID(); CompoundTag body=Native117RegionFixtures.body(id,15);
        CompoundTag before=body.copy();
        Path path=Native117RegionFixtures.write(world,Native117RegionFixtures.chunk(512,body));
        byte[] bytes=Files.readAllBytes(path);
        UpgradeJournal journal=UpgradeJournal.scan(world);
        assertEquals(4903,journal.originalFor(id,0,0).getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion",0));
        assertArrayEquals(bytes,Files.readAllBytes(path)); assertEquals(before,body);
    }

    @Test void nativeConversionShieldsReleasedMarker15BeforeAdvancingTo16() {
        CompoundTag body=Native117RegionFixtures.body(UUID.randomUUID(),15), before=body.copy();
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),
                References.ENTITY_TREE,body,512,5023);
        assertEquals(16,fixed.getIntOr(UpgradePreservation.VERSION,0));
        assertEquals(4903,fixed.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion",0));
        assertEquals(before,body);
    }

    @Test void allReleasedIndexedSlotsAuxAndRawRecoveryStayOriginal() {
        CompoundTag body=Native117RegionFixtures.body(UUID.randomUUID(),15), before=body.copy();
        CompoundTag context=new CompoundTag(); context.putInt("DataVersion",4903);
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,context);
        ListTag records=typedList(staged,"LootableBodiesUnresolved",10);
        assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {
            CompoundTag record=records.getCompoundOrEmpty(slot);
            assertEquals("Equipment",record.getStringOr("Source",""));
            assertEquals(typedList(before,"Equipment",10).getCompoundOrEmpty(slot),record.getCompoundOrEmpty("Data"));
            assertEquals(4903,record.getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion",0));
        }
        assertEquals("Aux",records.getCompoundOrEmpty(48).getStringOr("Source",""));
        assertEquals(typedList(before,"LootableBodiesUnresolved",10).getCompoundOrEmpty(0),records.getCompoundOrEmpty(49));
        assertEquals(before,body);
    }

    @Test void futureOwnedMarkerRefusesBeforeTouchingEntityRegion() throws Exception {
        CompoundTag body=Native117RegionFixtures.body(UUID.randomUUID(),17);
        Path path=Native117RegionFixtures.write(world,Native117RegionFixtures.chunk(5023,body));
        byte[] bytes=Files.readAllBytes(path);
        assertThrows(Exception.class,()->UpgradeJournal.scan(world));
        assertArrayEquals(bytes,Files.readAllBytes(path));
    }

    @Test void released262ComponentsReachTheActualNativeItemConverter() {
        CompoundTag context=new CompoundTag();context.putInt("DataVersion",4903);
        CompoundTag raw=new CompoundTag(),components=new CompoundTag(),custom=new CompoundTag();
        raw.putString("id","minecraft:diamond");raw.putInt("count",3);raw.putByte("Slot",(byte)47);
        custom.putString("Original","released 26.2");components.put("minecraft:custom_data",custom);
        raw.put("components",components);CompoundTag before=raw.copy();
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        CompoundTag nativeResult=NativeNbt.update(NativeVanillaDataFixer.get(),References.ITEM_STACK,raw.copy(),4903,5023);
        assertEquals(before,nativeResult);
        assertEquals(nativeResult,ItemMigration.convert(raw,context));assertEquals(before,raw);
    }

    @Test void released262UnrecognizedStackDataStaysRecoverable() {
        CompoundTag context=new CompoundTag();context.putInt("DataVersion",4903);
        CompoundTag raw=new CompoundTag();raw.putString("id","minecraft:diamond");raw.putInt("count",3);
        raw.putString("UnrecognizedData","retain original");CompoundTag before=raw.copy();
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        assertNull(ItemMigration.convert(raw,context));assertEquals(before,raw);
    }

    @Test void all48GenuineReleased262SlotsKeepTheirExactItems() throws Exception {
        CompoundTag fixture;
        try(var input=getClass().getResourceAsStream("/native262-release-equipment.nbt")) {
            assertNotNull(input);
            fixture=net.minecraft.nbt.NbtIo.readCompressed(input,net.minecraft.nbt.NbtAccounter.create(1024*1024));
        }
        CompoundTag context=new CompoundTag();context.putInt("DataVersion",4903);
        assertEquals(4903,fixture.getIntOr("DataVersion",0));
        ListTag equipment=typedList(fixture,"Equipment",10);assertEquals(48,equipment.size());
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        java.util.Set<Byte> slots=new java.util.HashSet<>();
        for(var item:equipment) {
            CompoundTag raw=(CompoundTag)item,before=raw.copy();
            slots.add(raw.getByteOr("Slot",(byte)-1));
            assertEquals(before,NativeNbt.update(NativeVanillaDataFixer.get(),References.ITEM_STACK,raw.copy(),4903,5023));
            assertEquals(before,ItemMigration.convert(raw,context));assertEquals(before,raw);
        }
        assertEquals(48,slots.size());for(byte i=0;i<48;i++)assertTrue(slots.contains(i));
    }
}
