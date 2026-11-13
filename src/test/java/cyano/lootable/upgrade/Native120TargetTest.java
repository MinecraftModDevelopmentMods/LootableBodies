package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native120TargetTest {
    @TempDir Path world;
    @AfterEach void closeJournal() { UpgradePreservation.close(); }
    private static int targetDataVersion() {
        net.minecraft.SharedConstants.tryDetectVersion();
        return net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    }
    @Test void targetMatchesTheActualNativeWorldVersion() {
        net.minecraft.SharedConstants.tryDetectVersion();
        assertEquals("26.2",net.minecraft.SharedConstants.getCurrentVersion().name());
        assertEquals(net.minecraft.server.packs.metadata.pack.PackFormat.of(88,0),
                net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES));
        assertEquals(net.minecraft.server.packs.metadata.pack.PackFormat.of(107,1),
                net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA));
        assertEquals(4903,targetDataVersion());
        assertEquals(targetDataVersion(),UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(15,UpgradePreservation.DATA_VERSION);
    }
    @Test void unstampedEmptyLegacyCapabilityDataGetsTheCurrentEnvelope() {
        CompoundTag legacy = new CompoundTag();
        legacy.put("data", new CompoundTag());
        CompoundTag before = legacy.copy();
        CompoundTag current = UpgradePreservation.upgradeLevelCapabilities(legacy);
        assertEquals(before, legacy);
        assertEquals(before, current.getCompoundOrEmpty("data"));
        assertSame(current, UpgradePreservation.upgradeLevelCapabilities(current));
    }

    @Test void released16MarkerIsMigratedWithAllSlotsAndOriginalsRetained() {
        assertEquals(2586,cyano.lootable.buildtest.HistoricalVersions.dataVersion("1.16.5"));
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,6);
        ListTag items=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);
            item.putString("id","minecraft:stone");item.putByte("Count",(byte)(slot+1));items.add(item);
        }
        body.put("Equipment",items);CompoundTag before=body.copy();
        CompoundTag source=new CompoundTag();source.putInt("DataVersion",2586);
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,source);
        assertEquals(15,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(48,typedList(staged, "LootableBodiesUnresolved", 10).size());
        for(int slot=0;slot<48;slot++) {
            CompoundTag record=typedList(staged, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(slot);
            assertEquals(items.getCompoundOrEmpty(slot),record.getCompoundOrEmpty("Data"));
            assertEquals("Equipment",record.getStringOr("Source", ""));
            assertEquals(2586,record.getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion", 0));
        }
        assertEquals(before,body);
    }
    @Test void productionClassesUseJava25() throws Exception {
        try(DataInputStream bytes=new DataInputStream(getClass().getResourceAsStream("/cyano/lootable/entities/EntityLootableBody.class"))) {
            assertEquals(0xCAFEBABE,bytes.readInt());bytes.readUnsignedShort();assertEquals(69,bytes.readUnsignedShort());
        }
    }
    @Test void marker6OverridesADamagedOuterEntityStamp() {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,6);
        com.mojang.datafixers.DataFixer identity=new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int version){throw new AssertionError("No schema needed for this pass-through control");}
            public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.serialization.Dynamic<T> value,int from,int to){return value;}
        };
        CompoundTag staged=NativeNbt.update(UpgradePreservation.wrapper(identity),
                net.minecraft.util.datafix.fixes.References.ENTITY_TREE,body,512,targetDataVersion());
        assertEquals(2586,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        assertEquals(6,body.getIntOr(UpgradePreservation.VERSION, 0));
    }
    @Test void released17MarkerRetainsItsSourceAndEveryIndexedOriginal() {
        CompoundTag body=Native117RegionFixtures.body(java.util.UUID.randomUUID(),7),before=body.copy();
        CompoundTag source=new CompoundTag();source.putInt("DataVersion",2730);
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,source);
        assertEquals(15,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(2730,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        ListTag records=typedList(staged, "LootableBodiesUnresolved", 10);assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {
            assertEquals("Equipment",records.getCompoundOrEmpty(slot).getStringOr("Source", ""));
            assertEquals(typedList(before, "Equipment", 10).getCompoundOrEmpty(slot),records.getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));
        }
        assertEquals(before,body);
    }
    @Test void marker7OverridesDamagedOuterStampWhenTheNative20TargetRuns() {
        CompoundTag body=Native117RegionFixtures.body(java.util.UUID.randomUUID(),7),before=body.copy();
        com.mojang.datafixers.DataFixer identity=new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int version){throw new AssertionError("No schema needed");}
            public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.serialization.Dynamic<T> value,int from,int to){return value;}
        };
        CompoundTag staged=NativeNbt.update(UpgradePreservation.wrapper(identity),net.minecraft.util.datafix.fixes.References.ENTITY_TREE,body,512,targetDataVersion());
        assertEquals(2730,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        assertEquals(before,body);
    }
    @Test void released18MarkerRetainsItsSourceAndEveryIndexedOriginal() {
        assertEquals(2975,cyano.lootable.buildtest.HistoricalVersions.dataVersion("1.18.2"));
        CompoundTag body=Native117RegionFixtures.body(java.util.UUID.randomUUID(),8),before=body.copy();
        CompoundTag source=new CompoundTag();source.putInt("DataVersion",2975);
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,source);
        assertEquals(15,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(2975,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        ListTag records=typedList(staged, "LootableBodiesUnresolved", 10);assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {
            assertEquals("Equipment",records.getCompoundOrEmpty(slot).getStringOr("Source", ""));
            assertEquals(typedList(before, "Equipment", 10).getCompoundOrEmpty(slot),records.getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));
        }
        assertEquals(before,body);
    }
    @Test void marker8OverridesDamagedOuterStampWhenTheNative20TargetRuns() {
        CompoundTag body=Native117RegionFixtures.body(java.util.UUID.randomUUID(),8),before=body.copy();
        com.mojang.datafixers.DataFixer identity=new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int version){throw new AssertionError("No schema needed");}
            public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.serialization.Dynamic<T> value,int from,int to){return value;}
        };
        CompoundTag staged=NativeNbt.update(UpgradePreservation.wrapper(identity),net.minecraft.util.datafix.fixes.References.ENTITY_TREE,body,512,targetDataVersion());
        assertEquals(2975,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        assertEquals(before,body);
    }

    @Test void released19MarkerOverridesTheDamagedChunkStampAndKeepsOriginals() throws Exception {
        assertEquals(3337,cyano.lootable.buildtest.HistoricalVersions.dataVersion("1.19.4"));
        CompoundTag body=Native117RegionFixtures.body(java.util.UUID.randomUUID(),9),before=body.copy();
        CompoundTag root=Native117RegionFixtures.chunk(512,body);
        Path region=Native117RegionFixtures.write(world,root);
        byte[] original=Files.readAllBytes(region);
        UpgradePreservation.prepare(world);
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),
                net.minecraft.util.datafix.fixes.References.ENTITY_CHUNK,root,512,targetDataVersion());
        CompoundTag staged=typedList(fixed, "Entities", 10).getCompoundOrEmpty(0);
        assertEquals(15,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(3337,staged.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        CompoundTag detached=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),
                net.minecraft.util.datafix.fixes.References.ENTITY_TREE,body,512,targetDataVersion());
        assertEquals(3337,detached.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        assertArrayEquals(original,Files.readAllBytes(region));
        ListTag records=typedList(staged, "LootableBodiesUnresolved", 10);
        assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {
            CompoundTag record=records.getCompoundOrEmpty(slot);
            assertEquals("Equipment",record.getStringOr("Source", ""));
            assertEquals(typedList(before, "Equipment", 10).getCompoundOrEmpty(slot),record.getCompoundOrEmpty("Data"));
        }
        assertEquals(before,body);
    }
}
