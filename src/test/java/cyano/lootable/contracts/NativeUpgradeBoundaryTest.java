package cyano.lootable.contracts;

import cyano.lootable.upgrade.UpgradePreservation;
import com.mojang.datafixers.DataFixer;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic boundary contracts; historical world qualification uses real old writers. */
class NativeUpgradeBoundaryTest {
    @TempDir Path world;
    private static final UUID ID=UUID.fromString("00000000-3333-4333-8333-000000000001");
    private static final DataFixer VANILLA=DataFixers.getDataFixer();
    @AfterEach void close(){UpgradePreservation.close();}
    private CompoundTag item(String id,int count,int damage,int slot) {
        CompoundTag item=new CompoundTag();item.putString("id",id);item.putByte("Count",(byte)count);item.putShort("Damage",(short)damage);if(slot>=0)item.putByte("Slot",(byte)slot);
        CompoundTag tag=new CompoundTag();tag.putString("keepsake","slot-"+slot);item.put("tag",tag);return item;
    }
    private CompoundTag corpse() {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies.Corpse");body.putUUID("UUID",ID);body.putString("Name","Saved owner");body.putString("CustomName","Saved owner");body.putLong("DeathTime",700);
        ListTag equipment=new ListTag();for(int i=0;i<48;i++)equipment.add(item("minecraft:stone",i+1,1,i));body.put("Equipment",equipment);
        ListTag aux=new ListTag();aux.add(item("minecraft:gold_ingot",17,0,-1));body.put("Aux",aux);body.putDouble("FallDistance",Double.NaN);return body;
    }
    private CompoundTag chunk(CompoundTag entity,boolean populated) {
        CompoundTag root=new CompoundTag(),level=new CompoundTag();root.putInt("DataVersion",512);root.put("Level",level);level.putInt("xPos",0);level.putInt("zPos",0);level.putBoolean("TerrainPopulated",populated);level.putBoolean("LightPopulated",populated);level.putByteArray("Biomes",new byte[256]);level.putIntArray("HeightMap",new int[256]);
        ListTag entities=new ListTag();if(entity!=null)entities.add(entity.copy());level.put("Entities",entities);
        CompoundTag section=new CompoundTag();section.putByte("Y",(byte)0);byte[] blocks=new byte[4096];blocks[0]=54;section.putByteArray("Blocks",blocks);section.putByteArray("Data",new byte[2048]);section.putByteArray("BlockLight",new byte[2048]);section.putByteArray("SkyLight",new byte[2048]);ListTag sections=new ListTag();sections.add(section);level.put("Sections",sections);
        CompoundTag chest=new CompoundTag();chest.putString("id","Chest");chest.putInt("x",0);chest.putInt("y",0);chest.putInt("z",0);ListTag contents=new ListTag();contents.add(item("minecraft:stone",23,1,0));chest.put("Items",contents);ListTag tiles=new ListTag();tiles.add(chest);level.put("TileEntities",tiles);return root;
    }
    private byte[] encoded(CompoundTag nbt)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.write(nbt,new DataOutputStream(bytes));return bytes.toByteArray();}
    private void preserve(CompoundTag nbt)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream output=new DataOutputStream(new DeflaterOutputStream(bytes))){NbtIo.write(nbt,output);}
        int sectors=(bytes.size()+5+4095)/4096;ByteBuffer region=ByteBuffer.allocate(8192+sectors*4096);region.putInt((2<<8)|sectors);region.position(8192);region.putInt(bytes.size()+1);region.put((byte)2);region.put(bytes.toByteArray());Path file=world.resolve("region/r.0.0.mca");Files.createDirectories(file.getParent());Files.write(file,region.array());UpgradePreservation.register(VANILLA);UpgradePreservation.prepare(world);
    }
    private CompoundTag update(CompoundTag chunk)throws Exception {
        preserve(chunk);DataFixer safe=UpgradePreservation.wrapper(VANILLA);
        CompoundTag first=cyano.lootable.upgrade.NativeNbt.update(safe,References.CHUNK,chunk.copy(),chunk.getInt("DataVersion"),1493);
        return cyano.lootable.upgrade.NativeNbt.update(safe,References.CHUNK,first,1493,1976);
    }
    private CompoundTag findCorpse(CompoundTag root) {
        CompoundTag found=find(root);assertNotNull(found,"The original corpse must remain present");return found;
    }
    private CompoundTag find(Tag value) {
        if(value instanceof CompoundTag){CompoundTag nbt=(CompoundTag)value;if(nbt.getString("id").equals("lootablebodies:corpse") || nbt.getString("id").equals("lootablebodies.Corpse"))return nbt;for(String key:nbt.getAllKeys()){CompoundTag found=find(nbt.get(key));if(found!=null)return found;}}
        if(value instanceof ListTag){for(Tag entry:(ListTag)value){CompoundTag found=find(entry);if(found!=null)return found;}}return null;
    }
    @Test void legacyIdentityAndEveryRawInventoryEntrySurviveBothAnvilPasses()throws Exception {
        CompoundTag body=corpse(),source=chunk(body,true);byte[] before=encoded(source);CompoundTag result=update(source),saved=findCorpse(result);
        assertEquals("lootablebodies:corpse",saved.getString("id"));assertEquals(7,saved.getInt("LootableBodiesDataVersion"));assertEquals(ID,saved.getUUID("UUID"));
        ListTag records=saved.getList("LootableBodiesUnresolved",10);assertEquals(49,records.size());
        for(int i=0;i<48;i++){CompoundTag record=records.getCompound(i);assertEquals("Equipment",record.getString("Source"));assertArrayEquals(encoded(body.getList("Equipment",10).getCompound(i)),encoded(record.getCompound("Data")));assertEquals(512,record.getCompound("SourceVersions").getInt("DataVersion"));}
        assertEquals("Aux",records.getCompound(48).getString("Source"));assertArrayEquals(before,encoded(source));assertFalse(saved.contains("LootableBodiesUpgradePayload"));assertFalse(saved.contains("LootableBodiesInventorySource"));
    }
    @Test void occupiedPartlyGeneratedChunksKeepTerrainAndVanillaContainers()throws Exception {
        CompoundTag result=update(chunk(corpse(),false)),level=result.getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
        ListTag tiles=level.getList("TileEntities",10);assertEquals(1,tiles.size());CompoundTag contents=tiles.getCompound(0).getList("Items",10).getCompound(0);assertEquals("minecraft:granite",contents.getString("id"));assertEquals(23,contents.getByte("Count"));assertNotEquals("empty",level.getString("Status"));
    }
    @Test void vanillaContainersAreProtectedEvenWithoutACorpseInTheirChunk()throws Exception {
        CompoundTag result=update(chunk(null,false));assertEquals(1,result.getCompound("Level").getList("Sections",10).size());assertEquals(1,result.getCompound("Level").getList("TileEntities",10).size());
    }
    @Test void storedTerrainSurvivesAnIncompleteChunkWithoutEntitiesOrContainers()throws Exception {
        CompoundTag source=chunk(null,false);source.getCompound("Level").put("TileEntities",new ListTag());
        CompoundTag level=update(source).getCompound("Level");
        assertEquals(1,level.getList("Sections",10).size());
        assertTrue(level.getList("Sections",10).getCompound(0).contains("Palette",9));
    }
    @Test void repeatedConversionCannotDuplicateOrResurrectRemovedCorpses()throws Exception {
        CompoundTag result=update(chunk(corpse(),true));DataFixer safe=UpgradePreservation.wrapper(VANILLA);CompoundTag again=cyano.lootable.upgrade.NativeNbt.update(safe,References.CHUNK,result.copy(),1976,1976);
        assertEquals(49,findCorpse(again).getList("LootableBodiesUnresolved",10).size());
        again.getCompound("Level").put("Entities",new ListTag());assertNull(find(cyano.lootable.upgrade.NativeNbt.update(safe,References.CHUNK,again,1976,1976)));
    }
    @Test void vanillaMinecartParentsAndNestedCorpsesBothUpgrade()throws Exception {
        CompoundTag cart=new CompoundTag();cart.putString("id","MinecartRideable");cart.putUUID("UUID",UUID.fromString("00000000-3333-4333-8333-000000000099"));ListTag riders=new ListTag();riders.add(corpse());cart.put("Passengers",riders);
        CompoundTag result=update(chunk(cart,true));assertEquals("minecraft:minecart",result.getCompound("Level").getList("Entities",10).getCompound(0).getString("id"));assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
    }
    @Test void corpseEggsKeepTheirTypeCountAndEntityPayload()throws Exception {
        for(String id:new String[]{"lootablebodies.Corpse","lootablebodies:corpse"}){
            CompoundTag egg=item("minecraft:spawn_egg",7,0,4),entity=new CompoundTag();entity.putString("id",id);entity.putString("Name","Egg owner");egg.getCompound("tag").put("EntityTag",entity);byte[] before=encoded(egg);
            CompoundTag fixed=cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),References.ITEM_STACK,egg.copy(),512,1976);
            assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(7,fixed.getByte("Count"));assertEquals("lootablebodies:corpse",fixed.getCompound("tag").getCompound("EntityTag").getString("id"));assertEquals("Egg owner",fixed.getCompound("tag").getCompound("EntityTag").getString("Name"));assertEquals(4,fixed.getByte("Slot"));assertArrayEquals(before,encoded(egg));
        }
    }
    @Test void corpseEggsInsideVanillaPlayerInventoriesAlsoUpgrade()throws Exception {
        CompoundTag egg=item("minecraft:spawn_egg",11,0,0),entity=new CompoundTag();entity.putString("id","lootablebodies.Corpse");egg.getCompound("tag").put("EntityTag",entity);ListTag items=new ListTag();items.add(egg);CompoundTag player=new CompoundTag();player.put("Inventory",items);
        CompoundTag result=cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),References.PLAYER,player.copy(),512,1976);CompoundTag fixed=result.getList("Inventory",10).getCompound(0);assertEquals("lootablebodies:corpse_spawn_egg",fixed.getString("id"));assertEquals(11,fixed.getByte("Count"));
    }
    @Test void aFalseCurrentMarkerCannotBypassLegacyIdentityConversion()throws Exception {
        CompoundTag body=corpse();body.putInt("LootableBodiesDataVersion",3);
        CompoundTag result=update(chunk(body,true));
        assertEquals(49,findCorpse(result).getList("LootableBodiesUnresolved",10).size());
        assertEquals(512,findCorpse(result).getCompound("LootableBodiesSourceVersions").getInt("DataVersion"));
    }
    @Test void futureCorpseFormatsAreNeverOverwritten() {
        CompoundTag body=corpse();body.putString("id","lootablebodies:corpse");body.putInt("LootableBodiesDataVersion",99);
        assertThrows(IllegalStateException.class,()->cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),References.ENTITY_TREE,body,1343,1976));
    }
    @Test void chunkConversionFailuresCannotBeMistakenForAbsentChunks() {
        UpgradePreservation.close();
        assertThrows(Error.class,()->cyano.lootable.upgrade.NativeNbt.update(UpgradePreservation.wrapper(VANILLA),References.CHUNK,chunk(corpse(),true),512,1976));
    }
    @Test void aPlausibleFalseStampCannotSkipVanillaBannerConversion()throws Exception {
        CompoundTag source=chunk(corpse(),true);source.putInt("DataVersion",922);
        CompoundTag banner=item("minecraft:banner",2,0,0),block=new CompoundTag();block.putInt("Base",5);banner.getCompound("tag").put("BlockEntityTag",block);
        ListTag items=new ListTag();items.add(banner);source.getCompound("Level").getList("TileEntities",10).getCompound(0).put("Items",items);
        CompoundTag converted=update(source).getCompound("Level").getList("TileEntities",10).getCompound(0).getList("Items",10).getCompound(0);
        assertEquals("minecraft:purple_banner",converted.getString("id"));assertEquals(2,converted.getByte("Count"));
    }
}
