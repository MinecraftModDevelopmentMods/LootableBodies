package cyano.lootable.upgrade;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Native RegionFile codec and entity-chunk shapes; not historical-world qualification. */
class Native117EntityRegionTest {
    @TempDir Path world;
    private static final UUID ID=UUID.fromString("00000000-1171-4171-8171-000000000001");
    private CompoundTag body(UUID id) {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");body.putUUID("UUID",id);
        body.putInt(UpgradePreservation.VERSION,7);body.putString("Name","Native 17 owner");
        ListTag inventory=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);item.putString("id","minecraft:diamond");
            item.putByte("Count",(byte)(slot+1));CompoundTag custom=new CompoundTag();custom.putString("keep","slot-"+slot);item.put("tag",custom);inventory.add(item);
        }
        body.put("Equipment",inventory);return body;
    }
    private CompoundTag chunk(int x,int z,CompoundTag... entities) {
        CompoundTag root=new CompoundTag();root.putInt("DataVersion",2730);root.putIntArray("Position",new int[]{x,z});
        ListTag list=new ListTag();for(CompoundTag entity:entities)list.add(entity.copy());root.put("Entities",list);return root;
    }
    private Path write(String dimension,String storage,int x,int z,CompoundTag root) throws Exception {
        Path directory=world.resolve(dimension).resolve(storage);Files.createDirectories(directory);
        Path path=directory.resolve("r."+(x>>5)+"."+(z>>5)+".mca");
        try(RegionFile region=new RegionFile(path.toFile(),directory.toFile(),false)) {
            try(DataOutputStream out=region.getChunkDataOutputStream(new ChunkPos(x,z))) {NbtIo.write(root,out);}
        }
        return path;
    }
    private CompoundTag original(UpgradeJournal journal,UUID id,int x,int z) throws Exception {
        CompoundTag saved=journal.originalFor(id,x,z);assertNotNull(saved,"Saved native entity chunk was skipped");return saved;
    }
    @Test void nativeEntityRegionsPreserveEverySlotAcrossAllDimensions() throws Exception {
        String[] dimensions={"","DIM-1","DIM1","dimensions/fixture/realm"};Map<Path,byte[]> before=new LinkedHashMap<>();
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<dimensions.length;i++) {
            UUID id=new UUID(ID.getMostSignificantBits(),ID.getLeastSignificantBits()+i);ids.add(id);
            Path file=write(dimensions[i],"entities",-1,33,chunk(-1,33,body(id)));before.put(file,Files.readAllBytes(file));
        }
        UpgradeJournal journal=UpgradeJournal.scan(world);assertEquals(4,journal.size());
        for(int i=0;i<dimensions.length;i++) {
            CompoundTag saved=original(journal,ids.get(i),-1,33);
            assertEquals(body(ids.get(i)),saved.getCompound("Entity"));assertEquals(dimensions[i],saved.getString("Dimension"));
            assertTrue(saved.getString("Region").endsWith("entities/r.-1.1.mca"));
            assertEquals(2730,saved.getCompound("SourceVersions").getInt("DataVersion"));
        }
        for(Map.Entry<Path,byte[]> file:before.entrySet())assertArrayEquals(file.getValue(),Files.readAllBytes(file.getKey()));
    }
    @Test void retainsExactCompressedEntityChunkAndRootContext() throws Exception {
        CompoundTag root=chunk(0,0,body(ID));Path file=write("","entities",0,0,root);byte[] before=Files.readAllBytes(file);
        CompoundTag saved=original(UpgradeJournal.scan(world),ID,0,0);
        int offset=ByteBuffer.wrap(before).getInt()>>>8,base=offset*4096,size=ByteBuffer.wrap(before,base,4).getInt();
        byte[] original=Arrays.copyOfRange(before,base+5,base+4+size);
        assertArrayEquals(original,Files.readAllBytes(world.resolve("data/lootablebodies-upgrade/chunks/"+saved.getString("OriginalChunk")+".bin")));
        try(DataInputStream input=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(original)))) {
            assertEquals(root,NbtIo.read(input,new NbtAccounter(64L*1024*1024)));
        }
        assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void rejectsWrongTypedOrMismatchedPositionBeforeOpeningTheWorld() throws Exception {
        for(Tag position:new Tag[]{StringTag.valueOf("0,0"),new IntArrayTag(new int[]{0}),new IntArrayTag(new int[]{32,0})}) {
            CompoundTag root=chunk(0,0,body(ID));root.put("Position",position);
            Path file=write("","entities",0,0,root);byte[] before=Files.readAllBytes(file);
            assertThrows(IOException.class,()->UpgradeJournal.scan(world));assertArrayEquals(before,Files.readAllBytes(file));
        }
    }
    @Test void rejectsMissingPositionAndWrongEntityListType() throws Exception {
        CompoundTag root=chunk(0,0,body(ID));root.remove("Position");write("","entities",0,0,root);
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));
        root=chunk(0,0);ListTag invalid=new ListTag();invalid.add(StringTag.valueOf("not an entity"));root.put("Entities",invalid);write("","entities",0,0,root);
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));
    }
    @Test void duplicateUuidAcrossActiveTerrainAndEntityRegionsCannotBeGuessed() throws Exception {
        CompoundTag terrain=new CompoundTag(),level=new CompoundTag();terrain.putInt("DataVersion",2586);terrain.put("Level",level);
        level.putInt("xPos",0);level.putInt("zPos",0);ListTag entities=new ListTag();entities.add(body(ID));level.put("Entities",entities);
        write("","region",0,0,terrain);write("","entities",0,0,chunk(0,0,body(ID)));
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));
    }
    @Test void removedNativeBodyNeverReappearsFromAnArchivedSnapshot() throws Exception {
        write("","entities",0,0,chunk(0,0,body(ID)));assertNotNull(UpgradeJournal.scan(world).originalFor(ID,0,0));
        write("","entities",0,0,chunk(0,0));assertEquals(0,UpgradeJournal.scan(world).size());
        assertNull(UpgradeJournal.scan(world).originalFor(ID,0,0));
        try(java.util.stream.Stream<Path> saved=Files.list(world.resolve("data/lootablebodies-upgrade/snapshots"))) {assertTrue(saved.findAny().isPresent());}
    }
    @Test void nestedPassengersArePreservedBeforeConstruction() throws Exception {
        CompoundTag cart=new CompoundTag();cart.putString("id","minecraft:chest_minecart");cart.putUUID("UUID",new UUID(7,8));
        ListTag passengers=new ListTag();passengers.add(body(ID));cart.put("Passengers",passengers);
        write("","entities",0,0,chunk(0,0,cart));CompoundTag saved=original(UpgradeJournal.scan(world),ID,0,0);
        assertEquals(body(ID),saved.getCompound("Entity"));assertArrayEquals(new int[]{0},saved.getIntArray("PassengerPath"));
    }
    @Test void unsupportedExternalStreamsRefuseSafelyAndRetainTheSidecar() throws Exception {
        Path file=write("","entities",0,0,chunk(0,0,body(ID)));byte[] raw=Files.readAllBytes(file);
        int base=(ByteBuffer.wrap(raw).getInt()>>>8)*4096,size=ByteBuffer.wrap(raw,base,4).getInt();
        Path sidecar=file.getParent().resolve("c.0.0.mcc");byte[] payload=Arrays.copyOfRange(raw,base+5,base+4+size);Files.write(sidecar,payload);
        ByteBuffer.wrap(raw,base,4).putInt(1);raw[base+4]=(byte)0x82;Files.write(file,raw);
        assertThrows(IOException.class,()->UpgradeJournal.scan(world));
        assertArrayEquals(raw,Files.readAllBytes(file));assertArrayEquals(payload,Files.readAllBytes(sidecar));
    }
    @Test void nativeEmptyEntityRegionPlaceholderStaysEmpty() throws Exception {
        Path directory=world.resolve("entities");Files.createDirectories(directory);Path file=directory.resolve("r.0.0.mca");
        try(RegionFile region=new RegionFile(file.toFile(),directory.toFile(),false)) {assertNull(region.getChunkDataInputStream(new ChunkPos(0,0)));}
        byte[] before=Files.readAllBytes(file);assertEquals(0,UpgradeJournal.scan(world).size());assertArrayEquals(before,Files.readAllBytes(file));
    }
}
