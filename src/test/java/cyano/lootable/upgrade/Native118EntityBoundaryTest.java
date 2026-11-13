package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static cyano.lootable.upgrade.Native117RegionFixtures.*;

/** Native-codec synthetic boundaries; genuine released-writer worlds are separate. */
class Native118EntityBoundaryTest {
    @TempDir(cleanup=CleanupMode.ON_SUCCESS) Path world;
    @AfterEach void close(){UpgradePreservation.close();System.out.println("NATIVE118_ENTITY_FIXTURE="+world);}
    @Test void released17WriterWithDamagedOuterStampStillUsesItsPreservedSource() throws Exception {
        CompoundTag corpse=body(new UUID(118,171),7),raw=chunk(-1,corpse),before=raw.copy();
        Path file=write(world,raw);byte[] original=Files.readAllBytes(file);
        UpgradeJournal journal=UpgradePreservation.prepareEarly(world,world.resolve("level.dat"));
        assertEquals(1,journal.size());
        CompoundTag converted=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.ENTITY_CHUNK,raw,-1,3955);
        CompoundTag actual=typedList(converted, "Entities", 10).getCompoundOrEmpty(0);
        assertEquals(15,actual.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(2730,actual.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        assertEquals(50,typedList(actual, "LootableBodiesUnresolved", 10).size());
        for(int slot=0;slot<48;slot++)assertEquals(typedList(corpse, "Equipment", 10).getCompoundOrEmpty(slot),typedList(actual, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));
        assertEquals(before,raw);assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void futureFlattenedTerrainIsRefusedDuringPreflightEvenWithoutACorpse() throws Exception {
        CompoundTag root=new CompoundTag();root.putInt("DataVersion",4904);root.putInt("xPos",0);root.putInt("zPos",0);
        root.put("sections",new ListTag());root.put("entities",new ListTag());
        Path folder=world.resolve("region");Files.createDirectories(folder);Path file=folder.resolve("r.0.0.mca");
        try(RegionFile region=new RegionFile(new net.minecraft.world.level.chunk.storage.RegionStorageInfo("fixture",net.minecraft.world.level.Level.OVERWORLD,"region"),file,folder,false);DataOutputStream output=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(root,output);}
        byte[] original=Files.readAllBytes(file);
        assertThrows(IOException.class,()->UpgradePreservation.prepareEarly(world,world.resolve("level.dat")));
        assertNull(UpgradePreservation.journal());assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void futureSeparateEntityVersionRefusesEvenWhenItsCorpseHasAnOlderKnownMarker() throws Exception {
        CompoundTag raw=chunk(4904,body(new UUID(118,172),7)),before=raw.copy();
        Path file=write(world,raw);byte[] original=Files.readAllBytes(file);
        assertThrows(IOException.class,()->UpgradePreservation.prepareEarly(world,world.resolve("level.dat")));
        assertNull(UpgradePreservation.journal());assertEquals(before,raw);assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void malformedNonIntSeparateStampUsesVerifiedReleased17SourceWithoutGuessingFutureData() throws Exception {
        net.minecraft.nbt.Tag[] stamps={StringTag.valueOf("damaged"),DoubleTag.valueOf(Double.NaN),LongTag.valueOf(Integer.MAX_VALUE),ShortTag.valueOf((short)512)};
        for(int i=0;i<stamps.length;i++) {
            UpgradePreservation.close();Path folder=world.resolve("typed-"+i);
            CompoundTag raw=chunk(2730,body(new UUID(118,173+i),7));raw.put("DataVersion",stamps[i]);CompoundTag before=raw.copy();
            Path file=write(folder,raw);byte[] original=Files.readAllBytes(file);UpgradePreservation.prepareEarly(folder,folder.resolve("level.dat"));
            CompoundTag converted=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.ENTITY_CHUNK,raw,-1,3955).getListOrEmpty("Entities").getCompoundOrEmpty(0);
            assertEquals(15,converted.getIntOr(UpgradePreservation.VERSION, 0));assertEquals(2730,converted.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
            assertEquals(50,typedList(converted, "LootableBodiesUnresolved", 10).size());assertEquals(before,raw);assertArrayEquals(original,Files.readAllBytes(file));
        }
    }
}
