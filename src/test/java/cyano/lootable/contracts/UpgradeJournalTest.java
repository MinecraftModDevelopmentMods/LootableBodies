package cyano.lootable.contracts;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic region files test the preservation boundary, not old-world qualification. */
class UpgradeJournalTest {
    @TempDir Path world;
    private static final UUID ID=UUID.fromString("00000000-1111-4111-8111-000000000001");
    private Object scan() throws Exception {
        try { return Class.forName("cyano.lootable.upgrade.UpgradeJournal").getMethod("scan",Path.class).invoke(null,world); }
        catch(InvocationTargetException error) { throw (Exception)error.getCause(); }
    }
    private NBTTagCompound original(Object journal,UUID id,int x,int z) throws Exception {
        return (NBTTagCompound)journal.getClass().getMethod("originalFor",UUID.class,int.class,int.class).invoke(journal,id,x,z);
    }
    private NBTTagCompound body(UUID id) {
        NBTTagCompound body=new NBTTagCompound();body.setString("id","lootablebodies.Corpse");body.setUniqueId("UUID",id);
        body.setDouble("FallDistance",Double.NaN);body.setString("Name","Saved owner");body.setLong("DeathTime",700);
        NBTTagList equipment=new NBTTagList();
        for(int i=0;i<48;i++) {NBTTagCompound item=new NBTTagCompound();item.setString("id","minecraft:diamond");item.setByte("Count",(byte)(i+1));item.setShort("Damage",(short)i);item.setByte("Slot",(byte)i);NBTTagCompound custom=new NBTTagCompound();custom.setString("keepsake","slot-"+i);item.setTag("tag",custom);equipment.appendTag(item);}
        body.setTag("Equipment",equipment);
        NBTTagCompound record=new NBTTagCompound();record.setString("Source","Equipment");record.setTag("Data",equipment.getCompoundTagAt(47).copy());
        NBTTagList recovery=new NBTTagList();recovery.appendTag(record);body.setTag("LootableBodiesUnresolved",recovery);
        return body;
    }
    private Path region(String dimension,NBTTagCompound... bodies) throws Exception {
        NBTTagCompound chunk=new NBTTagCompound(),level=new NBTTagCompound();chunk.setInteger("DataVersion",512);chunk.setTag("Level",level);level.setInteger("xPos",0);level.setInteger("zPos",0);
        NBTTagList entities=new NBTTagList();for(NBTTagCompound body:bodies)entities.appendTag(body.copy());level.setTag("Entities",entities);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream compressed=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(chunk,compressed);}
        int sectors=(bytes.size()+5+4095)/4096;byte[] contents=new byte[8192+sectors*4096];
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(contents);buffer.putInt((2<<8)|sectors);buffer.position(8192);buffer.putInt(bytes.size()+1);buffer.put((byte)2);buffer.put(bytes.toByteArray());
        Path region=world.resolve(dimension).resolve("region/r.0.0.mca");Files.createDirectories(region.getParent());Files.write(region,contents);return region;
    }
    private byte[] encoded(NBTTagCompound nbt) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    private List<Path> snapshots() throws Exception {try(java.util.stream.Stream<Path> files=Files.walk(world.resolve("data/lootablebodies-upgrade/snapshots"))){List<Path> result=new ArrayList<>();files.filter(p->p.toString().endsWith(".nbt")).forEach(result::add);return result;}}
    @Test void preservesCompleteTypedPayloadWithoutWritingSourceRegions() throws Exception {
        NBTTagCompound body=body(ID);Path region=region("",body);byte[] before=Files.readAllBytes(region);java.nio.file.attribute.FileTime stamp=Files.getLastModifiedTime(region);
        Object journal=scan();NBTTagCompound saved=original(journal,ID,0,0);
        assertArrayEquals(encoded(body),encoded(saved.getCompoundTag("Entity")));assertEquals(48,saved.getCompoundTag("Entity").getTagList("Equipment",10).tagCount());
        assertEquals(512,saved.getCompoundTag("SourceVersions").getInteger("DataVersion"));
        assertArrayEquals(before,Files.readAllBytes(region));assertEquals(stamp,Files.getLastModifiedTime(region));
    }
    @Test void repeatedScanReusesOriginalBackups() throws Exception {region("",body(ID));scan();List<Path> first=snapshots();scan();assertEquals(first,snapshots());}
    @Test void changedRegionsRetainOriginalsWithoutResurrectingRemovedBodies() throws Exception {
        region("",body(ID));scan();List<Path> first=snapshots();UUID replacement=UUID.fromString("00000000-1111-4111-8111-000000000002");region("",body(replacement));Object journal=scan();
        assertNull(original(journal,ID,0,0));assertNotNull(original(journal,replacement,0,0));assertTrue(snapshots().containsAll(first));assertEquals(2,snapshots().size());
    }
    @Test void staleIndexCannotOverwriteNewInventory() throws Exception {
        NBTTagCompound body=body(ID);region("",body);scan();Path index=world.resolve("data/lootablebodies-upgrade/index.nbt");byte[] stale=Files.readAllBytes(index);
        body.getTagList("Equipment",10).getCompoundTagAt(40).setByte("Count",(byte)99);region("",body);scan();Files.write(index,stale);
        assertArrayEquals(encoded(body),encoded(original(scan(),ID,0,0).getCompoundTag("Entity")));
    }
    @Test void interruptedTemporaryWriteIsNeverTrusted() throws Exception {
        region("",body(ID));Path partial=world.resolve("data/lootablebodies-upgrade/.pending-interrupted");Files.createDirectories(partial.getParent());Files.write(partial,new byte[]{31,-117,8});
        assertNotNull(original(scan(),ID,0,0));assertArrayEquals(new byte[]{31,-117,8},Files.readAllBytes(partial));
    }
    @Test void corruptBackupAbortsPreservation() throws Exception {region("",body(ID));scan();Path snapshot=snapshots().get(0);Files.write(snapshot,new byte[]{0,1,2});assertThrows(IOException.class,()->scan());}
    @Test void corruptIndexAbortsPreservation() throws Exception {region("",body(ID));scan();Files.write(world.resolve("data/lootablebodies-upgrade/index.nbt"),new byte[]{0,1,2});assertThrows(IOException.class,()->scan());}
    @Test void readableIndexDamageCannotSilentlyOmitPreservedCorpses() throws Exception {
        region("",body(ID));scan();Path path=world.resolve("data/lootablebodies-upgrade/index.nbt");
        NBTTagCompound index=CompressedStreamTools.readCompressed(Files.newInputStream(path));
        if(index.hasKey("Payload",7)) {byte[] payload=index.getByteArray("Payload");payload[payload.length/2]^=1;index.setByteArray("Payload",payload);}
        else index.getTagList("Regions",10).getCompoundTagAt(0).setTag("Snapshots",new NBTTagList());
        try(OutputStream output=Files.newOutputStream(path)){CompressedStreamTools.writeCompressed(index,output);}
        assertThrows(IOException.class,()->scan(),"A readable damaged index must not be accepted as a complete scan");
    }
    @Test void truncatedRegionAbortsBeforeLoading() throws Exception {Path region=region("",body(ID));Files.write(region,new byte[8193]);assertThrows(IOException.class,()->scan());}
    @Test void duplicateUuidAcrossDimensionsAbortsInsteadOfGuessing() throws Exception {region("",body(ID));region("DIM-1",body(ID));assertThrows(IOException.class,()->scan());}
    @Test void newDimensionsAreScannedOnLaterStarts() throws Exception {region("",body(ID));scan();UUID id=UUID.fromString("00000000-1111-4111-8111-000000000003");region("DIM1",body(id));assertEquals("DIM1",original(scan(),id,0,0).getString("Dimension"));}
    @Test void chunkCoordinateMismatchCannotApplyAnOldPayload() throws Exception {region("",body(ID));assertNull(original(scan(),ID,32,0));}
    @Test void retainsExactCompressedChunkAsWellAsDecodedCorpse() throws Exception {
        Path region=region("",body(ID));byte[] source=Files.readAllBytes(region);int size=java.nio.ByteBuffer.wrap(source,8192,4).getInt();
        byte[] originalChunk=Arrays.copyOfRange(source,8197,8192+4+size);NBTTagCompound snapshot=original(scan(),ID,0,0);
        assertTrue(snapshot.hasKey("OriginalChunk",8),"A decoded NBT copy cannot retain every original byte encoding");
        assertEquals(2,snapshot.getByte("OriginalCompression"));assertEquals(0,snapshot.getInteger("EntityIndex"));
        assertArrayEquals(originalChunk,Files.readAllBytes(world.resolve("data/lootablebodies-upgrade/chunks/"+snapshot.getString("OriginalChunk")+".bin")));
    }
    @Test void diskCannotSupplyAProcessedTemporaryPayload() throws Exception {
        NBTTagCompound body=body(ID),fake=body(ID);fake.setInteger("LootableBodiesDataVersion",1);fake.setTag("Equipment",new NBTTagList());body.setTag("LootableBodiesUpgradePayload",fake);
        Path region=region("",body);byte[] before=Files.readAllBytes(region);
        IOException failure=assertThrows(IOException.class,()->scan());assertTrue(failure.getMessage().contains("intermediate"));assertArrayEquals(before,Files.readAllBytes(region));
        assertFalse(snapshots().isEmpty(),"Unsafe input must still have a forensic original");
    }
}
