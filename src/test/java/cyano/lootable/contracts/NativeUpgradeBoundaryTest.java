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
    private NBTTagCompound item(String id,int count,int damage,int slot) {
        NBTTagCompound item=new NBTTagCompound();item.setString("id",id);item.setByte("Count",(byte)count);item.setShort("Damage",(short)damage);if(slot>=0)item.setByte("Slot",(byte)slot);
        NBTTagCompound tag=new NBTTagCompound();tag.setString("keepsake","slot-"+slot);item.setTag("tag",tag);return item;
    }
    private NBTTagCompound corpse() {
        NBTTagCompound body=new NBTTagCompound();body.setString("id","lootablebodies.Corpse");body.setUniqueId("UUID",ID);body.setString("Name","Saved owner");body.setString("CustomName","Saved owner");body.setLong("DeathTime",700);
        NBTTagList equipment=new NBTTagList();for(int i=0;i<48;i++)equipment.add(item("minecraft:stone",i+1,1,i));body.setTag("Equipment",equipment);
        NBTTagList aux=new NBTTagList();aux.add(item("minecraft:gold_ingot",17,0,-1));body.setTag("Aux",aux);body.setDouble("FallDistance",Double.NaN);return body;
    }
    private NBTTagCompound chunk(NBTTagCompound entity,boolean populated) {
        NBTTagCompound root=new NBTTagCompound(),level=new NBTTagCompound();root.setInt("DataVersion",512);root.setTag("Level",level);level.setInt("xPos",0);level.setInt("zPos",0);level.setBoolean("TerrainPopulated",populated);level.setBoolean("LightPopulated",populated);level.setByteArray("Biomes",new byte[256]);level.setIntArray("HeightMap",new int[256]);
        NBTTagList entities=new NBTTagList();if(entity!=null)entities.add(entity.copy());level.setTag("Entities",entities);
        NBTTagCompound section=new NBTTagCompound();section.setByte("Y",(byte)0);byte[] blocks=new byte[4096];blocks[0]=54;section.setByteArray("Blocks",blocks);section.setByteArray("Data",new byte[2048]);section.setByteArray("BlockLight",new byte[2048]);section.setByteArray("SkyLight",new byte[2048]);NBTTagList sections=new NBTTagList();sections.add(section);level.setTag("Sections",sections);
        NBTTagCompound chest=new NBTTagCompound();chest.setString("id","Chest");chest.setInt("x",0);chest.setInt("y",0);chest.setInt("z",0);NBTTagList contents=new NBTTagList();contents.add(item("minecraft:stone",23,1,0));chest.setTag("Items",contents);NBTTagList tiles=new NBTTagList();tiles.add(chest);level.setTag("TileEntities",tiles);return root;
    }
    private byte[] encoded(NBTTagCompound nbt)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();CompressedStreamTools.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    private void preserve(NBTTagCompound nbt)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(new DeflaterOutputStream(bytes))){CompressedStreamTools.write(nbt,output);}
        int sectors=(bytes.size()+5+4095)/4096;ByteBuffer region=ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Path file=world.resolve("region/r.0.0.mca");Files.createDirectories(file.getParent());Files.write(file,region.array());UpgradePreservation.register(VANILLA);UpgradePreservation.prepare(world);
    }
    private NBTTagCompound update(NBTTagCompound chunk)throws Exception {
        preserve(chunk);DataFixer safe=UpgradePreservation.wrapper(VANILLA);
        NBTTagCompound first=NBTUtil.update(safe,TypeReferences.CHUNK,chunk.copy(),chunk.getInt("DataVersion"),1493);
        return NBTUtil.update(safe,TypeReferences.CHUNK,first,1493,1631);
    }
    private NBTTagCompound findCorpse(NBTTagCompound root) {
        NBTTagCompound found=find(root);assertNotNull(found,"The original corpse must remain present");return found;
    }
    private NBTTagCompound find(INBTBase value) {
        if(value instanceof NBTTagCompound){NBTTagCompound nbt=(NBTTagCompound)value;if(nbt.getString("id").equals("lootablebodies:corpse") || nbt.getString("id").equals("lootablebodies.Corpse"))return nbt;for(String key:nbt.keySet()){NBTTagCompound found=find(nbt.getTag(key));if(found!=null)return found;}}
        if(value instanceof NBTTagList){for(INBTBase entry:(NBTTagList)value){NBTTagCompound found=find(entry);if(found!=null)return found;}}return null;
    }
    @Test void legacyIdentityAndEveryRawInventoryEntrySurviveBothAnvilPasses()throws Exception {
        NBTTagCompound body=corpse(),source=chunk(body,true);byte[] before=encoded(source);NBTTagCompound result=update(source),saved=findCorpse(result);
        assertEquals("lootablebodies:corpse",saved.getString("id"));assertEquals(3,saved.getInt("LootableBodiesDataVersion"));assertEquals(ID,saved.getUniqueId("UUID"));
        NBTTagList records=saved.getList("LootableBodiesUnresolved",10);assertEquals(49,records.size());
        for(int i=0;i<48;i++){NBTTagCompound record=records.getCompound(i);assertEquals("Equipment",record.getString("Source"));assertArrayEquals(encoded(body.getList("Equipment",10).getCompound(i)),encoded(record.getCompound("Data")));assertEquals(512,record.getCompound("SourceVersions").getInt("DataVersion"));}
        assertEquals("Aux",records.getCompound(48).getString("Source"));assertArrayEquals(before,encoded(source));assertFalse(saved.hasKey("LootableBodiesUpgradePayload"));assertFalse(saved.hasKey("LootableBodiesInventorySource"));
    }
    @Test void occupiedPartlyGeneratedChunksKeepTerrainAndVanillaContainers()throws Exception {
        NBTTagCompound result=update(chunk(corpse(),false)),level=result.getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
        NBTTagList tiles=level.getList("TileEntities",10);assertEquals(1,tiles.size());NBTTagCompound contents=tiles.getCompound(0).getList("Items",10).getCompound(0);assertEquals("minecraft:granite",contents.getString("id"));assertEquals(23,contents.getByte("Count"));assertNotEquals("empty",level.getString("Status"));
    }
    @Test void vanillaContainersAreProtectedEvenWithoutACorpseInTheirChunk()throws Exception {
        NBTTagCompound result=update(chunk(null,false));assertEquals(1,result.getCompound("Level").getList("Sections",10).size());assertEquals(1,result.getCompound("Level").getList("TileEntities",10).size());
    }
    @Test void storedTerrainSurvivesAnIncompleteChunkWithoutEntitiesOrContainers()throws Exception {
        NBTTagCompound source=chunk(null,false);source.getCompound("Level").setTag("TileEntities",new NBTTagList());
        NBTTagCompound level=update(source).getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());
        assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
    }
    @Test void repeatedConversionCannotDuplicateOrResurrectRemovedCorpses()throws Exception {
        NBTTagCompound result=update(chunk(corpse(),true));DataFixer safe=UpgradePreservation.wrapper(VANILLA);NBTTagCompound again=NBTUtil.update(safe,TypeReferences.CHUNK,result.copy(),1631,1631);
        assertEquals(49,findCorpse(again).getList("LootableBodiesUnresolved",10).size());
        again.getCompound("Level").setTag("Entities",new NBTTagList());assertNull(find(NBTUtil.update(safe,TypeReferences.CHUNK,again,1631,1631)));
    }
    @Test void vanillaMinecartParentsAndNestedCorpsesBothUpgrade()throws Exception {
        NBTTagCompound cart=new NBTTagCompound();cart.setString("id","MinecartRideable");cart.setUniqueId("UUID",UUID.fromString("00000000-3333-4333-8333-000000000099"));NBTTagList riders=new NBTTagList();riders.add(corpse());cart.setTag("Passengers",riders);
        NBTTagCompound result=update(chunk(cart,true));assertEquals("minecraft:minecart",result.getCompound("Level").getList("Entities",10).getCompound(0).getString("id"));assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
    }
    @Test void corpseEggsKeepTheirTypeCountAndEntityPayload()throws Exception {
        for(String id:new String[]{"lootablebodies.Corpse","lootablebodies:corpse"}){
            NBTTagCompound egg=item("minecraft:spawn_egg",7,0,4),entity=new NBTTagCompound();entity.setString("id",id);entity.setString("Name","Egg owner");egg.getCompound("tag").setTag("EntityTag",entity);byte[] before=encoded(egg);
            NBTTagCompound fixed=NBTUtil.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.ITEM_STACK,egg.copy(),512,1631);
            assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(7,fixed.getByte("Count"));assertEquals("lootablebodies:corpse",fixed.getCompound("tag").getCompound("EntityTag").getString("id"));assertEquals("Egg owner",fixed.getCompound("tag").getCompound("EntityTag").getString("Name"));assertEquals(4,fixed.getByte("Slot"));assertArrayEquals(before,encoded(egg));
        }
    }
    @Test void corpseEggsInsideVanillaPlayerInventoriesAlsoUpgrade()throws Exception {
        NBTTagCompound egg=item("minecraft:spawn_egg",11,0,0),entity=new NBTTagCompound();entity.setString("id","lootablebodies.Corpse");egg.getCompound("tag").setTag("EntityTag",entity);NBTTagList items=new NBTTagList();items.add(egg);NBTTagCompound player=new NBTTagCompound();player.setTag("Inventory",items);
        NBTTagCompound result=NBTUtil.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.PLAYER,player.copy(),512,1631);NBTTagCompound fixed=result.getList("Inventory",10).getCompound(0);assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(11,fixed.getByte("Count"));
    }
    @Test void aFalseCurrentMarkerCannotBypassLegacyIdentityConversion()throws Exception {
        NBTTagCompound body=corpse();body.setInt("LootableBodiesDataVersion",3);
        NBTTagCompound result=update(chunk(body,true));
        assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
        assertEquals(512,findCorpse(result).getCompound("LootableBodiesSourceVersions").getInt("DataVersion"));
    }
    @Test void futureCorpseFormatsAreNeverOverwritten() {
        NBTTagCompound body=corpse();body.setString("id","lootablebodies:corpse");body.setInt("LootableBodiesDataVersion",99);
        assertThrows(IllegalStateException.class,()->NBTUtil.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.ENTITY_TYPE,body,1343,1631));
    }
    @Test void chunkConversionFailuresCannotBeMistakenForAbsentChunks() {
        UpgradePreservation.close();
        assertThrows(Error.class,()->NBTUtil.update(UpgradePreservation.wrapper(VANILLA),TypeReferences.CHUNK,chunk(corpse(),true),512,1631));
    }
    @Test void aPlausibleFalseStampCannotSkipVanillaBannerConversion()throws Exception {
        NBTTagCompound source=chunk(corpse(),true);source.setInt("DataVersion",922);
        NBTTagCompound banner=item("minecraft:banner",2,0,0),block=new NBTTagCompound();block.setInt("Base",5);banner.getCompound("tag").setTag("BlockEntityTag",block);
        NBTTagList items=new NBTTagList();items.add(banner);source.getCompound("Level").getList("TileEntities",10).getCompound(0).setTag("Items",items);
        NBTTagCompound converted=update(source).getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        assertEquals("minecraft:purple_banner",converted.getString("id"));assertEquals(2,converted.getByte("Count"));
    }
}
