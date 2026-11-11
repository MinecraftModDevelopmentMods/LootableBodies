package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import static cyano.lootable.buildtest.TestNativeNbt.writeUuid;

import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.datafixers.*;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static cyano.lootable.upgrade.Native117RegionFixtures.*;

class Native117EntityChunkTest {
    @TempDir(cleanup=CleanupMode.ON_SUCCESS) Path world;
    private static final UUID ID=UUID.fromString("00000000-1171-4171-8171-000000000011");
    @AfterEach void close(){UpgradePreservation.close();System.out.println("ENTITY_CHUNK_FIXTURE="+world);}
    private CompoundTag convert(CompoundTag root,int source) {
        return NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),References.ENTITY_CHUNK,root,source,3955);
    }
    @Test void nativeEntityChunkShieldsAllSlotsAuxRecoveryAndPassengers() throws Exception {
        CompoundTag corpse=body(ID,6),cart=new CompoundTag();cart.putString("id","minecraft:chest_minecart");writeUuid(cart, "UUID", new UUID(12,13));
        ListTag passengers=new ListTag();passengers.add(corpse);cart.put("Passengers",passengers);
        CompoundTag raw=chunk(2586,cart),before=raw.copy();Path region=write(world,raw);byte[] bytes=Files.readAllBytes(region);
        UpgradePreservation.prepare(world);
        CompoundTag fixed=convert(raw,2586).getListOrEmpty("Entities").getCompoundOrEmpty(0).getListOrEmpty("Passengers").getCompoundOrEmpty(0);
        assertEquals(14,fixed.getIntOr(UpgradePreservation.VERSION, 0));assertEquals(2586,fixed.getCompoundOrEmpty(UpgradePreservation.SOURCES).getIntOr("DataVersion", 0));
        ListTag records=typedList(fixed, "LootableBodiesUnresolved", 10);assertEquals(50,records.size());
        for(int slot=0;slot<48;slot++) {assertEquals(typedList(corpse, "Equipment", 10).getCompoundOrEmpty(slot),records.getCompoundOrEmpty(slot).getCompoundOrEmpty("Data"));assertEquals("Equipment",records.getCompoundOrEmpty(slot).getStringOr("Source", ""));}
        assertEquals(typedList(corpse, "Aux", 10).getCompoundOrEmpty(0),records.getCompoundOrEmpty(48).getCompoundOrEmpty("Data"));
        assertEquals(typedList(corpse, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(0),records.getCompoundOrEmpty(49));
        assertEquals(before,raw);assertArrayEquals(bytes,Files.readAllBytes(region));
    }
    @Test void currentMarkerProtectsNativeRootAgainstDamagedStamps() {
        CompoundTag corpse=body(ID,UpgradePreservation.DATA_VERSION);
        for(int version:new int[]{-1,0,512,2230,2586,2730,2975,3337,3465,3839,3955}) {
            CompoundTag raw=chunk(version,corpse),before=raw.copy();
            CompoundTag fixed=convert(raw,version);
            assertEquals(corpse,typedList(fixed, "Entities", 10).getCompoundOrEmpty(0));assertEquals(before,raw);
        }
    }
    @Test void malformedEntityChunkCannotReachNativeConstruction() {
        CompoundTag raw=chunk(2730,body(ID,7));raw.putString("Position","0,0");CompoundTag before=raw.copy();
        assertThrows(IllegalStateException.class,()->convert(raw,2730));assertEquals(before,raw);
    }
    @Test void unpreservedSavedEntityChunkRefusesInsteadOfGuessingItsOriginal() {
        UpgradePreservation.close();CompoundTag raw=chunk(2586,body(ID,6));
        assertThrows(IllegalStateException.class,()->convert(raw,2586));
    }
    private DataFixer observe(DataFixer nativeFixer,AtomicInteger calls,UpgradeJournal expectedGlobal,boolean reject) {
        return new DataFixer() {
            public Schema getSchema(int version){return nativeFixer.getSchema(version);}
            public <T> Dynamic<T> update(DSL.TypeReference type,Dynamic<T> input,int from,int to) {
                if(type.typeName().equals("entity_chunk")) {
                    calls.incrementAndGet();assertSame(expectedGlobal,UpgradePreservation.journal(),"Preflight must not temporarily substitute the runtime journal");
                    if(reject)throw new IllegalStateException("Injected fixture conversion refusal; not a native Minecraft failure");
                }
                return nativeFixer.update(type,input,from,to);
            }
        };
    }
    @Test void startupRefusesBeforeWorldConstructionWithoutChangingGlobalOrOriginals() throws Exception {
        DataFixer nativeFixer=NativeVanillaDataFixer.get();
        UpgradeJournal previous=UpgradePreservation.prepare(world.resolve("previous"));
        Path candidate=world.resolve("candidate"),region=write(candidate,chunk(2586,body(ID,6)));byte[] bytes=Files.readAllBytes(region);
        AtomicInteger calls=new AtomicInteger();
        DataFixer fixer=observe(nativeFixer,calls,previous,true);
        assertThrows(java.io.IOException.class,()->UpgradePreservation.prepareEarly(candidate,candidate.resolve("level.dat"),fixer));
        assertTrue(calls.get()>0);assertSame(previous,UpgradePreservation.journal());assertArrayEquals(bytes,Files.readAllBytes(region));
    }
    @Test void successfulPreflightConvertsOneImmutableCopyPerChunkNotPerBody() throws Exception {
        DataFixer nativeFixer=NativeVanillaDataFixer.get();
        Path region=write(world,chunk(2586,body(ID,6),body(new UUID(14,15),6)));byte[] bytes=Files.readAllBytes(region);
        AtomicInteger calls=new AtomicInteger();UpgradePreservation.close();
        DataFixer fixer=observe(nativeFixer,calls,null,false);
        UpgradeJournal journal=UpgradePreservation.prepareEarly(world,world.resolve("level.dat"),fixer);assertEquals(2,journal.size());assertEquals(1,calls.get());
        assertSame(journal,UpgradePreservation.journal());assertArrayEquals(bytes,Files.readAllBytes(region));
    }
    @Test void unchangedIndexCannotReuseAnOldPreflightAfterTheFixerChanges() throws Exception {
        DataFixer nativeFixer=NativeVanillaDataFixer.get();
        Path region=write(world,chunk(2586,body(ID,6)));byte[] bytes=Files.readAllBytes(region);
        UpgradeJournal previous=UpgradePreservation.prepareEarly(world,world.resolve("level.dat"));AtomicInteger calls=new AtomicInteger();
        DataFixer fixer=observe(nativeFixer,calls,previous,true);
        assertThrows(java.io.IOException.class,()->UpgradePreservation.prepareEarly(world,world.resolve("level.dat"),fixer));assertTrue(calls.get()>0);
        assertSame(previous,UpgradePreservation.journal());assertArrayEquals(bytes,Files.readAllBytes(region));
    }
    @Test void actualServerFixerIsValidatedAfterTheEarlyLevelHook() throws Exception {
        DataFixer nativeFixer=NativeVanillaDataFixer.get();
        Path region=write(world,chunk(2586,body(ID,6)));byte[] bytes=Files.readAllBytes(region);
        UpgradeJournal previous=UpgradePreservation.prepareEarly(world,world.resolve("level.dat"));AtomicInteger calls=new AtomicInteger();
        DataFixer fixer=observe(nativeFixer,calls,previous,true);
        assertThrows(java.io.IOException.class,()->UpgradePreservation.prepareForStart(world,fixer));
        assertTrue(calls.get()>0);assertSame(previous,UpgradePreservation.journal());assertArrayEquals(bytes,Files.readAllBytes(region));
    }
}
