package cyano.lootable.contracts;

import cyano.lootable.upgrade.UpgradePreservation;
import com.mojang.datafixers.DataFixer;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary contracts; historical world qualification uses real old writers. */
class NativeUpgradeBoundaryTest {
    @TempDir Path world;
    private static final UUID ID=UUID.fromString("00000000-3333-4333-8333-000000000001");
    private static final DataFixer VANILLA=DataFixesManager.getDataFixer();
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundNBT item(String id,int count,int damage,int slot) {
        CompoundNBT item=new CompoundNBT();item.putString("id",id);item.putByte("Count",(byte)count);item.putShort("Damage",(short)damage);if(slot>=0)item.putByte("Slot",(byte)slot);
        CompoundNBT tag=new CompoundNBT();tag.putString("keepsake","slot-"+slot);item.put("tag",tag);return item;
    }
    private CompoundNBT corpse() {
        CompoundNBT body=new CompoundNBT();body.putString("id","lootablebodies.Corpse");body.putUniqueId("UUID",ID);body.putString("Name","Saved owner");body.putString("CustomName","Saved owner");body.putLong("DeathTime",700);
        ListNBT equipment=new ListNBT();for(int i=0;i<48;i++)equipment.add(item("minecraft:stone",i+1,1,i));body.put("Equipment",equipment);
        ListNBT aux=new ListNBT();aux.add(item("minecraft:gold_ingot",17,0,-1));body.put("Aux",aux);body.putDouble("FallDistance",Double.NaN);return body;
    }
    private CompoundNBT chunk(CompoundNBT entity,boolean populated) {
        CompoundNBT root=new CompoundNBT(),level=new CompoundNBT();root.putInt("DataVersion",512);root.put("Level",level);level.putInt("xPos",0);level.putInt("zPos",0);level.putBoolean("TerrainPopulated",populated);level.putBoolean("LightPopulated",populated);level.putByteArray("Biomes",new byte[256]);level.putIntArray("HeightMap",new int[256]);
        ListNBT entities=new ListNBT();if(entity!=null)entities.add(entity.copy());level.put("Entities",entities);
        CompoundNBT section=new CompoundNBT();section.putByte("Y",(byte)0);byte[] blocks=new byte[4096];blocks[0]=54;section.putByteArray("Blocks",blocks);section.putByteArray("Data",new byte[2048]);section.putByteArray("BlockLight",new byte[2048]);section.putByteArray("SkyLight",new byte[2048]);ListNBT sections=new ListNBT();sections.add(section);level.put("Sections",sections);
        CompoundNBT chest=new CompoundNBT();chest.putString("id","Chest");chest.putInt("x",0);chest.putInt("y",0);chest.putInt("z",0);ListNBT contents=new ListNBT();contents.add(item("minecraft:stone",23,1,0));chest.put("Items",contents);ListNBT tiles=new ListNBT();tiles.add(chest);level.put("TileEntities",tiles);return root;
    }
    private byte[] encoded(CompoundNBT nbt)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    private void preserve(CompoundNBT nbt)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(nbt,output);}
        int sectors=(bytes.size()+5+4095)/4096;ByteBuffer region=ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Path file=world.resolve("region/r.0.0.mca");Files.createDirectories(file.getParent());Files.write(file,region.array());UpgradePreservation.register(VANILLA);UpgradePreservation.prepare(world);
    }
    private CompoundNBT update(CompoundNBT chunk)throws Exception {
        preserve(chunk);DataFixer safe=UpgradePreservation.wrapper(VANILLA);
        CompoundNBT first=cyano.lootable.upgrade.NativeNbt.update(safe,TypeReferences.CHUNK,chunk.copy(),chunk.getInt("DataVersion"),1493);
        return cyano.lootable.upgrade.NativeNbt.update(safe,TypeReferences.CHUNK,first,1493,1976);
    }
    private CompoundNBT findCorpse(CompoundNBT root) {
        CompoundNBT found=find(root);assertNotNull(found,"The original corpse must remain present");return found;
    }
    private CompoundNBT find(INBT value) {
        if(value instanceof CompoundNBT){CompoundNBT nbt=(CompoundNBT)value;if(nbt.getString("id").equals("lootablebodies:corpse") || nbt.getString("id").equals("lootablebodies.Corpse"))return nbt;for(String key:nbt.keySet()){CompoundNBT found=find(nbt.get(key));if(found!=null)return found;}}
        if(value instanceof ListNBT){for(INBT entry:(ListNBT)value){CompoundNBT found=find(entry);if(found!=null)return found;}}return null;
    }
    @Test void legacyIdentityAndEveryRawInventoryEntrySurviveBothAnvilPasses()throws Exception {
        CompoundNBT body=corpse(),source=chunk(body,true);byte[] before=encoded(source);CompoundNBT result=update(source),saved=findCorpse(result);
        assertEquals("lootablebodies:corpse",saved.getString("id"));assertEquals(5,saved.getInt("LootableBodiesDataVersion"));assertEquals(ID,saved.getUniqueId("UUID"));
        ListNBT records=saved.getList("LootableBodiesUnresolved",10);assertEquals(49,records.size());
        for(int i=0;i<48;i++){CompoundNBT record=records.getCompound(i);assertEquals("Equipment",record.getString("Source"));assertArrayEquals(encoded(body.getList("Equipment",10).getCompound(i)),encoded(record.getCompound("Data")));assertEquals(512,record.getCompound("SourceVersions").getInt("DataVersion"));}
        assertEquals("Aux",records.getCompound(48).getString("Source"));assertArrayEquals(before,encoded(source));assertFalse(saved.contains("LootableBodiesUpgradePayload"));assertFalse(saved.contains("LootableBodiesInventorySource"));
    }
    @Test void occupiedPartlyGeneratedChunksKeepTerrainAndVanillaContainers()throws Exception {
        CompoundNBT result=update(chunk(corpse(),false)),level=result.getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
        ListNBT tiles=level.getList("TileEntities",10);assertEquals(1,tiles.size());CompoundNBT contents=tiles.getCompound(0).getList("Items",10).getCompound(0);assertEquals("minecraft:granite",contents.getString("id"));assertEquals(23,contents.getByte("Count"));assertNotEquals("empty",level.getString("Status"));
    }
    @Test void vanillaContainersAreProtectedEvenWithoutACorpseInTheirChunk()throws Exception {
        CompoundNBT result=update(chunk(null,false));assertEquals(1,result.getCompound("Level").getList("Sections",10).size());assertEquals(1,result.getCompound("Level").getList("TileEntities",10).size());
    }
    @Test void storedTerrainSurvivesAnIncompleteChunkWithoutEntitiesOrContainers()throws Exception {
        CompoundNBT source=chunk(null,false);source.getCompound("Level").put("TileEntities",new ListNBT());
        CompoundNBT level=update(source).getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());
        assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
    }
    @Test void repeatedConversionCannotDuplicateOrResurrectRemovedCorpses()throws Exception {
        CompoundNBT result=update(chunk(corpse(),true));DataFixer safe=UpgradePreservation.wrapper(VANILLA);CompoundNBT again=cyano.lootable.upgrade.NativeNbt.update(safe,TypeReferences.CHUNK,result.copy(),1976,1976);
        assertEquals(49,findCorpse(again).getList("LootableBodiesUnresolved",10).size());
        again.getCompound("Level").put("Entities",new ListNBT());assertNull(find(cyano.lootable.upgrade.NativeNbt.update(safe,TypeReferences.CHUNK,again,1976,1976)));
    }
    @Test void vanillaMinecartParentsAndNestedCorpsesBothUpgrade()throws Exception {
        CompoundNBT cart=new CompoundNBT();cart.putString("id","MinecartRideable");cart.putUniqueId("UUID",UUID.fromString("00000000-3333-4333-8333-000000000099"));ListNBT riders=new ListNBT();riders.add(corpse());cart.put("Passengers",riders);
        CompoundNBT result=update(chunk(cart,true));assertEquals("minecraft:minecart",result.getCompound("Level").getList("Entities",10).getCompound(0).getString("id"));assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
    }
    @Test void corpseEggsKeepTheirTypeCountAndEntityPayload()throws Exception {
        for(String id:new String[]{"lootablebodies.Corpse","lootablebodies:corpse"}){
            CompoundNBT egg=item("minecraft:spawn_egg",7,0,4),entity=new CompoundNBT();entity.putString("id",id);entity.putString("Name","Egg owner");egg.getCompound("tag").put("EntityTag",entity);byte[] before=encoded(egg);
            CompoundNBT fixed=cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.ITEM_STACK,egg.copy(),512,1976);
            assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(7,fixed.getByte("Count"));assertEquals("lootablebodies:corpse",fixed.getCompound("tag").getCompound("EntityTag").getString("id"));assertEquals("Egg owner",fixed.getCompound("tag").getCompound("EntityTag").getString("Name"));assertEquals(4,fixed.getByte("Slot"));assertArrayEquals(before,encoded(egg));
        }
    }
    @Test void corpseEggsInsideVanillaPlayerInventoriesAlsoUpgrade()throws Exception {
        CompoundNBT egg=item("minecraft:spawn_egg",11,0,0),entity=new CompoundNBT();entity.putString("id","lootablebodies.Corpse");egg.getCompound("tag").put("EntityTag",entity);ListNBT items=new ListNBT();items.add(egg);CompoundNBT player=new CompoundNBT();player.put("Inventory",items);
        CompoundNBT result=cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.PLAYER,player.copy(),512,1976);CompoundNBT fixed=result.getList("Inventory",10).getCompound(0);assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(11,fixed.getByte("Count"));
    }
    @Test void aFalseCurrentMarkerCannotBypassLegacyIdentityConversion()throws Exception {
        CompoundNBT body=corpse();body.putInt("LootableBodiesDataVersion",3);
        CompoundNBT result=update(chunk(body,true));
        assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
        assertEquals(512,findCorpse(result).getCompound("LootableBodiesSourceVersions").getInt("DataVersion"));
    }
    @Test void futureCorpseFormatsAreNeverOverwritten() {
        CompoundNBT body=corpse();body.putString("id","lootablebodies:corpse");body.putInt("LootableBodiesDataVersion",99);
        assertThrows(IllegalStateException.class,()->cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.ENTITY_TYPE,body,1343,1976));
    }
    @Test void chunkConversionFailuresCannotBeMistakenForAbsentChunks() {
        UpgradePreservation.close();
        assertThrows(Error.class,()->cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.CHUNK,chunk(corpse(),true),512,1976));
    }
    @Test void aPlausibleFalseStampCannotSkipVanillaBannerConversion()throws Exception {
        CompoundNBT source=chunk(corpse(),true);source.putInt("DataVersion",922);
        CompoundNBT banner=item("minecraft:banner",2,0,0),block=new CompoundNBT();block.putInt("Base",5);banner.getCompound("tag").put("BlockEntityTag",block);
        ListNBT items=new ListNBT();items.add(banner);source.getCompound("Level").getList("TileEntities",10).getCompound(0).put("Items",items);
        CompoundNBT converted=update(source).getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        assertEquals("minecraft:purple_banner",converted.getString("id"));assertEquals(2,converted.getByte("Count"));
    }
}
