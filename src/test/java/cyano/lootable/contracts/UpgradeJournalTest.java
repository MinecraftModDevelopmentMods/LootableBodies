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
    private CompoundNBT original(Object journal,UUID id,int x,int z) throws Exception {
        return (CompoundNBT)journal.getClass().getMethod("originalFor",UUID.class,int.class,int.class).invoke(journal,id,x,z);
    }
    private CompoundNBT body(UUID id) {
        CompoundNBT body=new CompoundNBT();body.putString("id","lootablebodies.Corpse");body.putUniqueId("UUID",id);
        body.putDouble("FallDistance",Double.NaN);body.putString("Name","Saved owner");body.putLong("DeathTime",700);
        ListNBT equipment=new ListNBT();
        for(int i=0;i<48;i++) {CompoundNBT item=new CompoundNBT();item.putString("id","minecraft:diamond");item.putByte("Count",(byte)(i+1));item.putShort("Damage",(short)i);item.putByte("Slot",(byte)i);CompoundNBT custom=new CompoundNBT();custom.putString("keepsake","slot-"+i);item.put("tag",custom);equipment.add(item);}
        body.put("Equipment",equipment);
        CompoundNBT record=new CompoundNBT();record.putString("Source","Equipment");record.put("Data",equipment.getCompound(47).copy());
        ListNBT recovery=new ListNBT();recovery.add(record);body.put("LootableBodiesUnresolved",recovery);
        return body;
    }
    private Path region(String dimension,CompoundNBT... bodies) throws Exception {
        return regionVersion(dimension,512,bodies);
    }
    private Path regionVersion(String dimension,int version,CompoundNBT... bodies) throws Exception {
        CompoundNBT chunk=new CompoundNBT(),level=new CompoundNBT();if(version>=0)chunk.putInt("DataVersion",version);chunk.put("Level",level);level.putInt("xPos",0);level.putInt("zPos",0);
        ListNBT entities=new ListNBT();for(CompoundNBT body:bodies)entities.add(body.copy());level.put("Entities",entities);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream compressed=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(chunk,compressed);}
        int sectors=(bytes.size()+5+4095)/4096;byte[] contents=new byte[8192+sectors*4096];
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(contents);buffer.putInt((2<<8)|sectors);buffer.position(8192);buffer.putInt(bytes.size()+1);buffer.put((byte)2);buffer.put(bytes.toByteArray());
        Path region=world.resolve(dimension).resolve("region/r.0.0.mca");Files.createDirectories(region.getParent());Files.write(region,contents);return region;
    }
    private byte[] encoded(CompoundNBT nbt) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    @Test void native112VersionIsNotDowngradedTo111() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");regionVersion("",1343,b);
        assertEquals(1343,original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion"));
    }
    @Test void native113MarkerAndStampSurvivePreservation() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");b.putInt("LootableBodiesDataVersion",3);regionVersion("",1631,b);
        assertEquals(1631,original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion"));
        assertNotNull(original(scan(),ID,0,0));
    }
    @Test void released112MarkerDoesNotBorrowAFalselyNative113Stamp() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");b.putInt("LootableBodiesDataVersion",2);regionVersion("",1631,b);
        assertEquals(1343,original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion"));
    }
    @Test void native113MarkerDisambiguatesMissingMinecraftStamps() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");b.putInt("LootableBodiesDataVersion",3);regionVersion("",-1,b);
        CompoundNBT versions=original(scan(),ID,0,0).getCompound("SourceVersions");
        assertEquals(1631,versions.getInt("DataVersion"));assertFalse(versions.getBoolean("Ambiguous"));
    }
    @Test void actual112WriterMetadataRejectsAFalselyFlattenedStamp() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");regionVersion("",1631,b);
        CompoundNBT root=new CompoundNBT(),fml=new CompoundNBT(),mod=new CompoundNBT();mod.putString("ModId","lootablebodies");mod.putString("ModVersion","2.4.0");ListNBT mods=new ListNBT();mods.add(mod);fml.put("ModList",mods);root.put("FML",fml);root.put("Data",new CompoundNBT());
        try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,output);}
        int source=original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion");assertTrue(source>=1139 && source<=1343);
    }
    @Test void corpsesSavedAsVanillaVehiclePassengersArePreserved() throws Exception {
        CompoundNBT cart=new CompoundNBT();cart.putString("id","minecraft:minecart");cart.putUniqueId("UUID",UUID.fromString("00000000-1111-4111-8111-000000000099"));
        ListNBT riders=new ListNBT();riders.add(body(ID));cart.put("Passengers",riders);region("",cart);
        CompoundNBT saved=original(scan(),ID,0,0);assertNotNull(saved);assertArrayEquals(encoded(body(ID)),encoded(saved.getCompound("Entity")));
        region("",cart.copy());assertNotNull(original(scan(),ID,0,0));
        cart.remove("Passengers");region("",cart);assertNull(original(scan(),ID,0,0));
    }
    @Test void current112MarkerCanBePreservedAndReloaded() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");b.putInt("LootableBodiesDataVersion",2);regionVersion("",1343,b);
        assertNotNull(original(scan(),ID,0,0));assertNotNull(original(scan(),ID,0,0));
    }
    @Test void nativeIdentityWithoutAnyVersionEvidenceIsExplicitlyAmbiguous() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");regionVersion("",-1,b);
        assertTrue(original(scan(),ID,0,0).getCompound("SourceVersions").getBoolean("Ambiguous"));
    }
    @Test void old111MarkerProvesItsOwnSourceDespiteAnIncorrectNewChunkStamp() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");b.putInt("LootableBodiesDataVersion",1);regionVersion("",1343,b);
        assertEquals(922,original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion"));
    }
    @Test void rootFmlMetadataDistinguishesAnUnstampedOriginal112World() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");regionVersion("",-1,b);
        CompoundNBT root=new CompoundNBT(),fml=new CompoundNBT(),mod=new CompoundNBT();mod.putString("ModId","lootablebodies");mod.putString("ModVersion","2.4.0");ListNBT mods=new ListNBT();mods.add(mod);fml.put("ModList",mods);root.put("FML",fml);root.put("Data",new CompoundNBT());
        try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,output);}
        CompoundNBT context=original(scan(),ID,0,0).getCompound("SourceVersions");assertTrue(context.getInt("DataVersion")>=1139);assertFalse(context.getBoolean("Ambiguous"));assertEquals(fml,context.getCompound("WorldFML"));
    }
    @Test void unmarkedOriginal111CorpseWithFalselyNewStampStillUses111ItemRules() throws Exception {
        CompoundNBT b=body(ID);b.putString("id","lootablebodies:corpse");regionVersion("",1343,b);
        CompoundNBT root=new CompoundNBT(),fml=new CompoundNBT(),mod=new CompoundNBT();mod.putString("ModId","lootablebodies");mod.putString("ModVersion","2.3.0");ListNBT mods=new ListNBT();mods.add(mod);fml.put("ModList",mods);root.put("FML",fml);root.put("Data",new CompoundNBT());
        try(OutputStream output=Files.newOutputStream(world.resolve("level.dat"))){CompressedStreamTools.writeCompressed(root,output);}
        assertEquals(922,original(scan(),ID,0,0).getCompound("SourceVersions").getInt("DataVersion"));
    }
    @Test void stampOnlyChangesRetainPreviouslyVerifiedChunkVersion() throws Exception {
        CompoundNBT b=body(ID);regionVersion("",512,b);scan();
        regionVersion("",-1,b);
        CompoundNBT versions=original(scan(),ID,0,0).getCompound("SourceVersions");
        assertEquals(512,versions.getInt("VerifiedChunkDataVersion"));
    }
    @Test void changedChunkPayloadCannotReusePreviousVersionEvidence() throws Exception {
        CompoundNBT b=body(ID);regionVersion("",512,b);scan();
        CompoundNBT added=new CompoundNBT();added.putString("id","minecraft:pig");
        regionVersion("",-1,b,added);
        assertFalse(original(scan(),ID,0,0).getCompound("SourceVersions").contains("VerifiedChunkDataVersion"));
    }
    private List<Path> snapshots() throws Exception {try(java.util.stream.Stream<Path> files=Files.walk(world.resolve("data/lootablebodies-upgrade/snapshots"))){List<Path> result=new ArrayList<>();files.filter(p->p.toString().endsWith(".nbt")).forEach(result::add);return result;}}
    @Test void preservesCompleteTypedPayloadWithoutWritingSourceRegions() throws Exception {
        CompoundNBT body=body(ID);Path region=region("",body);byte[] before=Files.readAllBytes(region);java.nio.file.attribute.FileTime stamp=Files.getLastModifiedTime(region);
        Object journal=scan();CompoundNBT saved=original(journal,ID,0,0);
        assertArrayEquals(encoded(body),encoded(saved.getCompound("Entity")));assertEquals(48,saved.getCompound("Entity").getList("Equipment",10).size());
        assertEquals(512,saved.getCompound("SourceVersions").getInt("DataVersion"));
        assertArrayEquals(before,Files.readAllBytes(region));assertEquals(stamp,Files.getLastModifiedTime(region));
    }
    @Test void repeatedScanReusesOriginalBackups() throws Exception {region("",body(ID));scan();List<Path> first=snapshots();scan();assertEquals(first,snapshots());}
    @Test void changedRegionsRetainOriginalsWithoutResurrectingRemovedBodies() throws Exception {
        region("",body(ID));scan();List<Path> first=snapshots();UUID replacement=UUID.fromString("00000000-1111-4111-8111-000000000002");region("",body(replacement));Object journal=scan();
        assertNull(original(journal,ID,0,0));assertNotNull(original(journal,replacement,0,0));assertTrue(snapshots().containsAll(first));assertEquals(2,snapshots().size());
    }
    @Test void staleIndexCannotOverwriteNewInventory() throws Exception {
        CompoundNBT body=body(ID);region("",body);scan();Path index=world.resolve("data/lootablebodies-upgrade/index.nbt");byte[] stale=Files.readAllBytes(index);
        body.getList("Equipment",10).getCompound(40).putByte("Count",(byte)99);region("",body);scan();Files.write(index,stale);
        assertArrayEquals(encoded(body),encoded(original(scan(),ID,0,0).getCompound("Entity")));
    }
    @Test void interruptedTemporaryWriteIsNeverTrusted() throws Exception {
        region("",body(ID));Path partial=world.resolve("data/lootablebodies-upgrade/.pending-interrupted");Files.createDirectories(partial.getParent());Files.write(partial,new byte[]{31,-117,8});
        assertNotNull(original(scan(),ID,0,0));assertArrayEquals(new byte[]{31,-117,8},Files.readAllBytes(partial));
    }
    @Test void corruptBackupAbortsPreservation() throws Exception {region("",body(ID));scan();Path snapshot=snapshots().get(0);Files.write(snapshot,new byte[]{0,1,2});assertThrows(IOException.class,()->scan());}
    @Test void corruptIndexAbortsPreservation() throws Exception {region("",body(ID));scan();Files.write(world.resolve("data/lootablebodies-upgrade/index.nbt"),new byte[]{0,1,2});assertThrows(IOException.class,()->scan());}
    @Test void readableIndexDamageCannotSilentlyOmitPreservedCorpses() throws Exception {
        region("",body(ID));scan();Path path=world.resolve("data/lootablebodies-upgrade/index.nbt");
        CompoundNBT index=CompressedStreamTools.readCompressed(Files.newInputStream(path));
        if(index.contains("Payload",7)) {byte[] payload=index.getByteArray("Payload");payload[payload.length/2]^=1;index.putByteArray("Payload",payload);}
        else index.getList("Regions",10).getCompound(0).put("Snapshots",new ListNBT());
        try(OutputStream output=Files.newOutputStream(path)){CompressedStreamTools.writeCompressed(index,output);}
        assertThrows(IOException.class,()->scan(),"A readable damaged index must not be accepted as a complete scan");
    }
    @Test void truncatedRegionAbortsBeforeLoading() throws Exception {Path region=region("",body(ID));Files.write(region,new byte[8193]);assertThrows(IOException.class,()->scan());}
    @Test void duplicateUuidAcrossDimensionsAbortsInsteadOfGuessing() throws Exception {region("",body(ID));region("DIM-1",body(ID));assertThrows(IOException.class,()->scan());}
    @Test void newDimensionsAreScannedOnLaterStarts() throws Exception {region("",body(ID));scan();UUID id=UUID.fromString("00000000-1111-4111-8111-000000000003");region("DIM1",body(id));assertEquals("DIM1",original(scan(),id,0,0).getString("Dimension"));}
    @Test void chunkCoordinateMismatchCannotApplyAnOldPayload() throws Exception {region("",body(ID));assertNull(original(scan(),ID,32,0));}
    @Test void retainsExactCompressedChunkAsWellAsDecodedCorpse() throws Exception {
        Path region=region("",body(ID));byte[] source=Files.readAllBytes(region);int size=java.nio.ByteBuffer.wrap(source,8192,4).getInt();
        byte[] originalChunk=Arrays.copyOfRange(source,8197,8192+4+size);CompoundNBT snapshot=original(scan(),ID,0,0);
        assertTrue(snapshot.contains("OriginalChunk",8),"A decoded NBT copy cannot retain every original byte encoding");
        assertEquals(2,snapshot.getByte("OriginalCompression"));assertEquals(0,snapshot.getInt("EntityIndex"));
        assertArrayEquals(originalChunk,Files.readAllBytes(world.resolve("data/lootablebodies-upgrade/chunks/"+snapshot.getString("OriginalChunk")+".bin")));
    }
    @Test void diskCannotSupplyAProcessedTemporaryPayload() throws Exception {
        CompoundNBT body=body(ID),fake=body(ID);fake.putInt("LootableBodiesDataVersion",1);fake.put("Equipment",new ListNBT());body.put("LootableBodiesUpgradePayload",fake);
        Path region=region("",body);byte[] before=Files.readAllBytes(region);
        IOException failure=assertThrows(IOException.class,()->scan());assertTrue(failure.getMessage().contains("intermediate"));assertArrayEquals(before,Files.readAllBytes(region));
        assertFalse(snapshots().isEmpty(),"Unsafe input must still have a forensic original");
    }
    @Test void unsupportedFutureCorpseFormatsStopBeforeWorldLoading()throws Exception {
        CompoundNBT corpse=body(ID);corpse.putString("id","lootablebodies:corpse");corpse.putInt("LootableBodiesDataVersion",99);Path file=region("",corpse);byte[] before=Files.readAllBytes(file);
        assertThrows(IOException.class,()->scan());assertArrayEquals(before,Files.readAllBytes(file));
        assertTrue(Files.list(world.resolve("data/lootablebodies-upgrade/snapshots")).findAny().isPresent(),"Original future data must be preserved before stopping");
    }
}
