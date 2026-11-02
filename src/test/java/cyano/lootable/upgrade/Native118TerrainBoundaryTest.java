package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native-codec cases, distinct from genuine historical writer worlds. */
class Native118TerrainBoundaryTest {
    @TempDir(cleanup=CleanupMode.ON_SUCCESS) Path world;
    @AfterEach void close(){UpgradePreservation.close();System.out.println("NATIVE118_TERRAIN_FIXTURE="+world);}
    private CompoundTag root(CompoundTag... bodies) {
        CompoundTag root=new CompoundTag();root.putInt("DataVersion",2975);root.putInt("xPos",0);root.putInt("zPos",0);root.putInt("yPos",-4);
        root.putString("Status","full");root.put("sections",new ListTag());
        ListTag list=new ListTag();for(CompoundTag body:bodies)list.add(body.copy());root.put("entities",list);return root;
    }
    private Path write(CompoundTag root)throws Exception {
        Path folder=world.resolve("region");Files.createDirectories(folder);Path file=folder.resolve("r.0.0.mca");
        try(RegionFile region=new RegionFile(file,folder,false);DataOutputStream output=region.getChunkDataOutputStream(new ChunkPos(0,0))){NbtIo.write(root,output);}
        return file;
    }
    private CompoundTag legacy() {
        CompoundTag old=new CompoundTag(),level=new CompoundTag();old.putInt("DataVersion",2730);old.put("Level",level);
        level.putInt("xPos",0);level.putInt("zPos",0);level.putString("Status","full");level.putIntArray("Biomes",new int[1024]);
        CompoundTag section=new CompoundTag(),state=new CompoundTag();section.putByte("Y",(byte)0);state.putString("Name","minecraft:stone");
        ListTag palette=new ListTag();palette.add(state);section.put("Palette",palette);section.putLongArray("BlockStates",new long[256]);
        ListTag sections=new ListTag();sections.add(section);level.put("Sections",sections);level.put("Entities",new ListTag());return old;
    }
    @Test void invalidSectionYTypesAndOverflowAreRefusedWithoutChangingOriginals()throws Exception {
        for(net.minecraft.nbt.Tag bad:new net.minecraft.nbt.Tag[]{DoubleTag.valueOf(-4.5),IntTag.valueOf(128),StringTag.valueOf("-4")}) {
            CompoundTag chunk=root(),section=new CompoundTag();section.put("Y",bad);ListTag sections=new ListTag();sections.add(section);chunk.put("sections",sections);
            Path file=write(chunk);byte[] original=Files.readAllBytes(file);
            assertThrows(IOException.class,()->UpgradeJournal.scan(world));assertArrayEquals(original,Files.readAllBytes(file));
        }
    }
    @Test void missingStampOnModernThreeDimensionalBiomesStillRunsNativeHeightFix()throws Exception {
        CompoundTag old=legacy();int[] biomes=new int[1024];for(int i=0;i<biomes.length;i++)biomes[i]=1+i%2;
        old.getCompound("Level").putIntArray("Biomes",biomes);ChunkStorage.injectDatafixingContext(old,Level.OVERWORLD,Optional.empty());
        CompoundTag expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,old.copy(),2730,2975);
        CompoundTag damaged=old.copy();damaged.remove("DataVersion");CompoundTag before=damaged.copy();
        CompoundTag result=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.CHUNK,damaged,512,2975);
        assertEquals(expected.get("sections"),result.get("sections"));assertFalse(result.contains("Level"));assertEquals(before,damaged);
    }
    @Test void currentFlattenedTerrainWithoutCorpsesCanBeScannedWithoutRewritingIt()throws Exception {
        Path file=write(root());byte[] original=Files.readAllBytes(file);
        assertEquals(0,UpgradeJournal.scan(world).size());assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void flattenedTerrainRetainsAllOriginalOwnedPayloadAndSource()throws Exception {
        UUID id=UUID.randomUUID();CompoundTag body=Native117RegionFixtures.body(id,7),input=root(body);
        Path file=write(input);byte[] original=Files.readAllBytes(file);UpgradeJournal journal=UpgradeJournal.scan(world);
        assertEquals(1,journal.size());assertEquals(body,journal.originalFor(id,0,0).getCompound("Entity"));
        assertEquals(2730,journal.originalFor(id,0,0).getCompound("SourceVersions").getInt("CorpseWriterDataVersion"));
        assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void ambiguousLegacyAndFlattenedTerrainIsRefusedWithAllBytesRetained()throws Exception {
        CompoundTag mixed=root();CompoundTag level=new CompoundTag();level.putInt("xPos",0);level.putInt("zPos",0);level.put("Entities",new ListTag());mixed.put("Level",level);
        Path file=write(mixed);byte[] original=Files.readAllBytes(file);
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void actualNativeHeightAndRootConversionRetainsTheProtectedCorpse()throws Exception {
        CompoundTag old=legacy(),level=old.getCompound("Level");
        CompoundTag corpse=Native117RegionFixtures.body(UUID.randomUUID(),7);ListTag bodies=new ListTag();bodies.add(corpse);level.put("Entities",bodies);
        CompoundTag before=old.copy();Path file=write(old);byte[] original=Files.readAllBytes(file);UpgradePreservation.prepare(world);
        ChunkStorage.injectDatafixingContext(old,Level.OVERWORLD,Optional.empty());
        CompoundTag vanilla=old.copy();vanilla.getCompound("Level").put("Entities",new ListTag());
        CompoundTag expected=NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,vanilla,2730,2975);
        NbtIo.writeCompressed(expected,world.resolve("native-reference.nbt").toFile());
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.CHUNK,old,2730,2975);
        assertFalse(fixed.contains("Level"));assertEquals(expected.get("sections"),fixed.get("sections"));assertTrue(fixed.getList("sections",10).size()>=16);
        CompoundTag staged=fixed.getList("entities",10).getCompound(0);assertEquals(8,staged.getInt(UpgradePreservation.VERSION));
        assertEquals(50,staged.getList("LootableBodiesUnresolved",10).size());assertEquals(2730,staged.getCompound(UpgradePreservation.SOURCES).getInt("DataVersion"));
        old.remove("__context");assertEquals(before,old);assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void nativeHeightConversionNormalizesARecoveredLegacyStampBeforeValidatingTheFlattenedResult()throws Exception {
        CompoundTag old=legacy(),body=Native117RegionFixtures.body(UUID.randomUUID(),7);
        ListTag bodies=new ListTag();bodies.add(body);old.getCompound("Level").put("Entities",bodies);
        old.putInt("DataVersion",Integer.MAX_VALUE);
        Path file=write(old);byte[] original=Files.readAllBytes(file);UpgradePreservation.prepare(world);
        ChunkStorage.injectDatafixingContext(old,Level.OVERWORLD,Optional.empty());CompoundTag before=old.copy();
        // The native schema conversion leaves DataVersion to ChunkStorage's
        // caller. This stale stamp does not describe the newly converted root.
        CompoundTag nativeCopy=old.copy();nativeCopy.getCompound("Level").put("Entities",new ListTag());
        CompoundTag nativeResult=NativeNbt.update(NativeVanillaDataFixer.get(),References.CHUNK,nativeCopy,2730,2975);
        assertFalse(nativeResult.contains("Level"));assertEquals(Integer.MAX_VALUE,nativeResult.getInt("DataVersion"));
        CompoundTag fixed=NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.CHUNK,old,2730,2975);
        assertFalse(fixed.contains("Level"));assertEquals(2975,fixed.getInt("DataVersion"));
        assertEquals(nativeResult.get("sections"),fixed.get("sections"));
        assertEquals(body.getUUID("UUID"),fixed.getList("entities",10).getCompound(0).getUUID("UUID"));
        assertEquals(before,old);assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void genuinelyFutureFlattenedTerrainStillRefusesBeforeConversion()throws Exception {
        CompoundTag future=root();future.putInt("DataVersion",2976);CompoundTag before=future.copy();
        Path file=write(future);byte[] original=Files.readAllBytes(file);
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));
        assertThrows(Error.class,()->NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.CHUNK,future,2976,2975));
        assertEquals(before,future);assertArrayEquals(original,Files.readAllBytes(file));
    }
}
