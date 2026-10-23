package cyano.lootable.fixture;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.buildtest.FixtureItems;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;

/** Writes a real 1.11 intermediate save while leaving distant 1.10 chunks unopened. */
@Mod(modid="lootablebodiesbuildtest",name="Lootable Bodies Transition Fixture",version="1",dependencies="required-after:lootablebodies")
public final class TransitionFixture {
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event){FixtureItems.register();}
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        MinecraftServer server=FMLCommonHandler.instance().getMinecraftServerInstance();
        try {run(server);}catch(Throwable error){error.printStackTrace();}finally{server.initiateShutdown();}
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void run(MinecraftServer server) throws Exception {
        require(server.getMinecraftVersion().equals("1.11.2"),"Wrong transition Minecraft");
        require(Loader.instance().getIndexedModList().get("lootablebodies").getVersion().equals("2.4.2.111021"),"Wrong intermediate release");
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        File folder=server.getWorld(0).getSaveHandler().getWorldDirectory();NBTTagCompound manifest;
        try(InputStream input=new FileInputStream(new File(folder,"upgrade-fixtures.nbt"))){manifest=CompressedStreamTools.readCompressed(input);}
        require(manifest.getString("Minecraft").equals("1.10.2"),"Transition source was not written by Minecraft 1.10");
        NBTTagList bodies=manifest.getTagList("Bodies",10);int loaded=0;
        for(int i=0;i<bodies.tagCount();i++) {
            NBTTagCompound record=bodies.getCompoundTagAt(i);if(record.getInteger("Wave")!=0)continue;
            WorldServer world=server.getWorld(record.getInteger("Dimension"));NBTTagCompound source=record.getCompoundTag("Body");
            world.getChunk(8,8);EntityLootableBody found=null;
            for(Entity entity:world.loadedEntityList)if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(source.getUniqueId("UUID")))found=(EntityLootableBody)entity;
            require(found!=null,"Missing intermediate corpse");NBTTagCompound saved=new NBTTagCompound();found.writeToNBTOptional(saved);
            require(saved.getInteger("LootableBodiesDataVersion")==1 && saved.getString("id").equals("lootablebodies:corpse"),"Intermediate corpse not migrated by released 1.11");
            NBTTagCompound expected=source.copy();normalize111(expected);
            require(inventory(expected).equals(inventory(saved)),"Intermediate 1.11 save changed complete inventory");
            require(found.dimension==record.getInteger("Dimension"),"Intermediate dimension changed");loaded++;
        }
        require(loaded==3,"Expected three near corpses");
        for(int dimension:new int[]{0,-1,1}) {
            WorldServer world=server.getWorld(dimension);
            require(!world.getChunkProvider().chunkExists(256,256) && !world.getChunkProvider().chunkExists(512,512),"Transition opened a distant chunk");
            world.getWorldInfo().setWorldTotalTime(world.getTotalWorldTime()+1);
        }
        server.saveAllWorlds(false);net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        try(Writer marker=new FileWriter(new File(folder,"transition.pass"))){marker.write("1.10.2/2.2.0 -> 1.11.2/2.4.2.111021; near=3, unopened=6\n");}
        System.out.println("LOOTABLE_BODIES_TRANSITION_PASS loaded=3 unopened=6");
    }
    private static Map<String,Integer> inventory(NBTTagCompound body) {
        Map<String,Integer> result=new TreeMap<>();
        for(String key:new String[]{"Equipment","Aux"}) {
            NBTTagList list=body.getTagList(key,10);
            for(int i=0;i<list.tagCount();i++) {
                NBTTagCompound item=list.getCompoundTagAt(i).copy();int count=item.getByte("Count")&255;
                item.removeTag("Count");item.removeTag("Slot");String signature=canonical(item);
                result.put(signature,result.getOrDefault(signature,0)+count);
            }
        }
        return result;
    }
    private static String canonical(NBTBase tag) {
        StringBuilder text=new StringBuilder().append(tag.getId()).append(':');
        if(tag instanceof NBTTagCompound) {
            NBTTagCompound compound=(NBTTagCompound)tag;
            for(String key:new TreeSet<>(compound.getKeySet()))text.append(key.length()).append(':').append(key).append('=').append(canonical(compound.getTag(key))).append(';');
        } else if(tag instanceof NBTTagList) {
            NBTTagList list=(NBTTagList)tag;text.append(list.getTagType()).append('/').append(list.tagCount()).append('[');
            for(int i=0;i<list.tagCount();i++)text.append(canonical(list.get(i))).append(';');text.append(']');
        } else if(tag instanceof NBTTagByteArray)text.append(Arrays.toString(((NBTTagByteArray)tag).getByteArray()));
        else if(tag instanceof NBTTagIntArray)text.append(Arrays.toString(((NBTTagIntArray)tag).getIntArray()));
        else text.append(tag.toString());
        return text.toString();
    }
    private static void normalize111(NBTBase value) {
        if(value instanceof NBTTagList){NBTTagList list=(NBTTagList)value;for(int i=0;i<list.tagCount();i++)normalize111(list.get(i));return;}
        if(!(value instanceof NBTTagCompound))return;NBTTagCompound item=(NBTTagCompound)value;
        if(item.getString("id").equals("minecraft:spawn_egg")) {
            NBTTagCompound entity=item.getCompoundTag("tag").getCompoundTag("EntityTag");if(entity.getString("id").equals("Pig"))entity.setString("id","minecraft:pig");
        }
        if(item.getString("id").equals("minecraft:banner")) {
            NBTTagCompound tag=item.getCompoundTag("tag"),block=tag.getCompoundTag("BlockEntityTag");
            if(block.hasKey("Base",99)){item.setShort("Damage",(short)(block.getShort("Base")&15));block.removeTag("Base");if(block.isEmpty())tag.removeTag("BlockEntityTag");}
        }
        for(String key:new ArrayList<>(item.getKeySet()))normalize111(item.getTag(key));
    }
}
