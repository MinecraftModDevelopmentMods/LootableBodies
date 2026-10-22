package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Acceptance against worlds written by the actual historical production jars. */
final class WorldUpgradeChecks {
    private static NBTTagCompound manifest,ledger;
    private static File folder;
    private static boolean old;
    private static int checks;
    static void run(MinecraftServer server,String phase) throws Exception {
        folder=server.getWorld(0).getSaveHandler().getWorldDirectory();
        manifest=read(new File(folder,"upgrade-fixtures.nbt"));old=manifest.getString("Minecraft").equals("1.10.2");
        File stored=new File(folder,"upgrade-recovered.nbt");ledger=stored.isFile()?read(stored):new NBTTagCompound();checks=0;
        if(!ledger.hasKey("Recoveries",10))ledger.setTag("Recoveries",new NBTTagCompound());
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        int wave=phase.startsWith("upgrade-near")?0:phase.startsWith("upgrade-far")?1:phase.equals("upgrade-late")?2:-1;
        boolean recover=phase.equals("upgrade-near-recover") || phase.equals("upgrade-far-reload") || phase.equals("upgrade-late") || phase.equals("upgrade-return");
        NBTTagList bodies=manifest.getTagList("Bodies",10);
        for(int i=0;i<bodies.tagCount();i++) {
            NBTTagCompound record=bodies.getCompoundTagAt(i);if(wave>=0 && record.getInteger("Wave")!=wave)continue;
            WorldServer world=server.getWorld(record.getInteger("Dimension"));NBTTagCompound source=record.getCompoundTag("Body");
            int x=(int)source.getTagList("Pos",6).getDoubleAt(0),z=(int)source.getTagList("Pos",6).getDoubleAt(2);world.getChunkFromChunkCoords(x>>4,z>>4);
            UUID uuid=source.getUniqueId("UUID");EntityLootableBody body=find(world,uuid);String id=uuid.toString();
            if(ledger.getString("Removed").equals(id)) {require(body==null,"Journal resurrected a deliberately removed corpse");continue;}
            require(body!=null,"Missing historical corpse "+uuid);
            require(body.getUniqueID().equals(uuid) && body.dimension==record.getInteger("Dimension"),"UUID/dimension changed");
            require(body.getCustomNameTag().equals(source.getString("CustomName")),"Owner attribution changed");
            require(body.posX==source.getTagList("Pos",6).getDoubleAt(0) && body.posY==source.getTagList("Pos",6).getDoubleAt(1) && body.posZ==source.getTagList("Pos",6).getDoubleAt(2),"Position changed in dimension "+world.provider.getDimension()+": "+body.getPositionVector());
            NBTTagCompound actual=save(body);require(actual.getInteger("LootableBodiesDataVersion")==1,"Missing corpse migration marker");
            require(NameLifetimeChecks.same(source.getTag("LootableBodiesInvalidFields"),actual.getTag("LootableBodiesInvalidFields")),"Original invalid-field evidence changed");
            Map<String,Integer> expected=expectedInventory(source);Map<String,Integer> observed=inventory(actual);
            NBTTagCompound recovery=ledger.getCompoundTag("Recoveries").getCompoundTag(id);
            Map<String,Integer> recovered=storage(world,recovery);
            merge(observed,recovered);require(expected.equals(observed),"Inventory conservation mismatch "+uuid+" expected="+expected+" actual="+observed);
            verifyRaw(source,actual);
            if(recover) {
                if(!ledger.getCompoundTag("Recoveries").hasKey(id,10)) {ledger.getCompoundTag("Recoveries").setTag(id,new NBTTagCompound());recovery=ledger.getCompoundTag("Recoveries").getCompoundTag(id);}
                recover(world,body,recovery);
                require(inventory(save(body)).isEmpty(),"Supported inventory remained after GUI recovery");
                require(expected.equals(storage(world,recovery)),"GUI recovery lost or duplicated complete item data");
                verifyRaw(source,save(body));
            }
            verifyChest(world,record.getInteger("Wave"));
        }
        verifyPlayer(server);
        if(wave==0 || wave<0) verifyVanilla(server);
        if(phase.equals("upgrade-final") && ledger.getString("Removed").isEmpty()) {
            for(int i=0;i<bodies.tagCount();i++) {
                NBTTagCompound record=bodies.getCompoundTagAt(i);WorldServer world=server.getWorld(record.getInteger("Dimension"));EntityLootableBody body=find(world,record.getCompoundTag("Body").getUniqueId("UUID"));
                if(body!=null && body.isEmpty()) {ledger.setString("Removed",body.getUniqueID().toString());BlockPos position=body.getPosition();world.removeEntityDangerously(body);world.getChunkFromBlockCoords(position).markDirty();break;}
            }
            require(!ledger.getString("Removed").isEmpty(),"No safely empty corpse available for non-resurrection check");
        }
        // This harness stops before the game loop. Advance the clock as a real
        // played session would, so vanilla actually writes its loaded chunks.
        for(int dimension:new int[]{0,-1,1}) {WorldServer world=server.getWorld(dimension);world.getWorldInfo().setWorldTotalTime(world.getTotalWorldTime()+1);}
        server.saveAllWorlds(false);net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        try(FileOutputStream output=new FileOutputStream(stored)){CompressedStreamTools.writeCompressed(ledger,output);}
        try(PrintWriter out=new PrintWriter(new File(folder,phase+".pass"),"UTF-8")){out.println("checks="+checks+" historical="+manifest.getString("Minecraft")+"/"+manifest.getString("ModVersion"));}
        System.out.println("LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase="+phase+" checks="+checks);
    }
    private static void require(boolean ok,String message){checks++;RuntimeChecks.require(ok,message);}
    private static NBTTagCompound read(File file) throws IOException {try(FileInputStream input=new FileInputStream(file)){return CompressedStreamTools.readCompressed(input);}}
    private static NBTTagCompound save(EntityLootableBody body){NBTTagCompound nbt=new NBTTagCompound();body.writeEntityToNBT(nbt);return nbt;}
    private static EntityLootableBody find(WorldServer world,UUID id){for(Entity entity:world.loadedEntityList)if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(id))return (EntityLootableBody)entity;return null;}
    private static boolean known(NBTTagCompound item){return item.hasKey("id",8) && Item.REGISTRY.containsKey(new ResourceLocation(item.getString("id"))) && !item.getString("id").equals("minecraft:air");}
    private static NBTTagCompound expectedItem(NBTTagCompound source) {
        NBTTagCompound item=source.copy();
        if(old) normalize(item);
        return item;
    }
    // Independent expectations for the exact vanilla changes in these fixtures.
    private static void normalize(NBTBase value) {
        if(value instanceof NBTTagList) {NBTTagList list=(NBTTagList)value;for(int i=0;i<list.tagCount();i++)normalize(list.get(i));return;}
        if(!(value instanceof NBTTagCompound))return;NBTTagCompound item=(NBTTagCompound)value;
        if(item.getString("id").equals("minecraft:spawn_egg")) {NBTTagCompound entity=item.getCompoundTag("tag").getCompoundTag("EntityTag");if(entity.getString("id").equals("Pig"))entity.setString("id","minecraft:pig");}
        if(item.getString("id").equals("minecraft:banner")) {NBTTagCompound tag=item.getCompoundTag("tag"),block=tag.getCompoundTag("BlockEntityTag");if(block.hasKey("Base",99)){item.setShort("Damage",(short)(block.getShort("Base")&15));block.removeTag("Base");if(block.hasNoTags())tag.removeTag("BlockEntityTag");}}
        for(String key:new ArrayList<>(item.getKeySet()))normalize(item.getTag(key));
    }
    private static void add(Map<String,Integer> map,NBTTagCompound source,boolean indexed) {
        if(!source.hasKey("id",8) || source.getByte("Count")<=0)return;
        NBTTagCompound item=source.copy();int count=item.getByte("Count")&255;item.removeTag("Count");if(!indexed)item.removeTag("Slot");
        String key=TypedNbt.canonical(item);map.put(key,map.getOrDefault(key,0)+count);
    }
    private static void merge(Map<String,Integer> a,Map<String,Integer> b){for(Map.Entry<String,Integer> item:b.entrySet())a.put(item.getKey(),a.getOrDefault(item.getKey(),0)+item.getValue());}
    private static Map<String,Integer> inventory(NBTTagCompound body){Map<String,Integer> map=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}){NBTTagList items=body.getTagList(key,10);for(int i=0;i<items.tagCount();i++)add(map,items.getCompoundTagAt(i),false);}return map;}
    private static Map<String,Integer> expectedInventory(NBTTagCompound source) {
        Map<String,Integer> expected=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}) {NBTTagList list=source.getTagList(key,10);for(int i=0;i<list.tagCount();i++){NBTTagCompound item=expectedItem(list.getCompoundTagAt(i));if(known(item))add(expected,item,false);}}
        NBTTagList records=source.getTagList("LootableBodiesUnresolved",10);for(int i=0;i<records.tagCount();i++){NBTTagCompound item=expectedItem(records.getCompoundTagAt(i).getCompoundTag("Data"));if(known(item))add(expected,item,false);}return expected;
    }
    private static void verifyRaw(NBTTagCompound source,NBTTagCompound saved) {
        List<NBTTagCompound> expected=new ArrayList<>();
        for(String key:new String[]{"Equipment","Aux"}) {NBTTagList list=source.getTagList(key,10);for(int i=0;i<list.tagCount();i++)if(!known(expectedItem(list.getCompoundTagAt(i)))){NBTTagCompound record=new NBTTagCompound();record.setString("Source",key);record.setTag("Data",list.getCompoundTagAt(i).copy());expected.add(record);}}
        NBTTagList records=source.getTagList("LootableBodiesUnresolved",10);for(int i=0;i<records.tagCount();i++){NBTTagCompound record=records.getCompoundTagAt(i);if(!record.hasKey("Data",10) || !known(expectedItem(record.getCompoundTag("Data"))))expected.add(record);}
        NBTTagList actual=saved.getTagList("LootableBodiesUnresolved",10);require(expected.size()==actual.tagCount(),"Raw recovery count changed");
        for(int i=0;i<expected.size();i++) {NBTTagCompound e=expected.get(i),a=actual.getCompoundTagAt(i);require(e.getString("Source").equals(a.getString("Source")) && NameLifetimeChecks.same(e.getTag("Data"),a.getTag("Data")),"Original recovery Source/Data changed");require(a.getCompoundTag("SourceVersions").getInteger("DataVersion")== (old?512:922),"Recovery lost its original version context");}
    }
    private static Map<String,Integer> contents(IInventory inventory,boolean indexed) {Map<String,Integer> map=new TreeMap<>();for(int slot=0;slot<inventory.getSizeInventory();slot++){ItemStack stack=inventory.getStackInSlot(slot);if(stack.isEmpty())continue;NBTTagCompound item=stack.writeToNBT(new NBTTagCompound());item.setByte("Slot",(byte)slot);add(map,item,indexed);}return map;}
    private static Map<String,Integer> indexed(NBTTagList list) {Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.tagCount();i++)add(map,expectedItem(list.getCompoundTagAt(i)),true);return map;}
    private static NBTTagList storageList(NBTTagCompound recovery){if(!recovery.hasKey("Storage",9))recovery.setTag("Storage",new NBTTagList());return recovery.getTagList("Storage",10);}
    private static BlockPos position(NBTTagCompound nbt){return new BlockPos(nbt.getInteger("x"),nbt.getInteger("y"),nbt.getInteger("z"));}
    private static Map<String,Integer> storage(WorldServer world,NBTTagCompound recovery) {Map<String,Integer> map=new TreeMap<>();NBTTagList list=storageList(recovery);for(int i=0;i<list.tagCount();i++){BlockPos position=position(list.getCompoundTagAt(i));world.getChunkFromBlockCoords(position);TileEntity tile=world.getTileEntity(position);require(tile instanceof IInventory,"Recovery chest disappeared");merge(map,contents((IInventory)tile,false));}return map;}
    private static void recover(WorldServer world,EntityLootableBody body,NBTTagCompound recovery) {
        // Original fixtures deliberately retain their source terrain. Clear only
        // this disposable interaction space before ticking the body: Nether
        // netherrack otherwise invokes the existing wall-escape gameplay.
        BlockPos center=body.getPosition();
        for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)world.setBlockToAir(center.add(x,y,z));
        EntityPlayerMP visitor=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("00000000-2222-4222-8222-000000000099"),"RecoveryVisitor"));visitor.setHealth(20);visitor.inventory.clear();visitor.setPosition(body.posX,body.posY,body.posZ);
        CorpseContainer container=new CorpseContainer(visitor.inventory,body);
        for(int attempt=0;attempt<200 && !inventory(save(body)).isEmpty();attempt++) {
            for(int slot=0;slot<48;slot++)container.transferStackInSlot(visitor,slot);
            flush(world,body,visitor.inventory,recovery);body.onEntityUpdate();
        }
        flush(world,body,visitor.inventory,recovery);
        require(contents(visitor.inventory,false).isEmpty(),"Recovery visitor retained unsaved items");
    }
    private static void flush(WorldServer world,EntityLootableBody body,IInventory player,NBTTagCompound recovery) {
        NBTTagList stored=storageList(recovery);
        for(int slot=0;slot<player.getSizeInventory();slot++) {
            ItemStack stack=player.getStackInSlot(slot);if(stack.isEmpty())continue;IInventory destination=null;int empty=-1;
            if(stored.tagCount()>0) {destination=(IInventory)world.getTileEntity(position(stored.getCompoundTagAt(stored.tagCount()-1)));for(int i=0;i<destination.getSizeInventory();i++)if(destination.getStackInSlot(i).isEmpty()){empty=i;break;}}
            if(empty<0){BlockPos pos=new BlockPos(body.posX+10,80+stored.tagCount(),body.posZ+10);world.setBlockState(pos,net.minecraft.init.Blocks.CHEST.getDefaultState());destination=(IInventory)world.getTileEntity(pos);NBTTagCompound place=new NBTTagCompound();place.setInteger("x",pos.getX());place.setInteger("y",pos.getY());place.setInteger("z",pos.getZ());stored.appendTag(place);empty=0;}
            destination.setInventorySlotContents(empty,stack.copy());destination.markDirty();player.setInventorySlotContents(slot,ItemStack.EMPTY);
        }
    }
    private static void verifyChest(WorldServer world,int wave) {
        NBTTagList containers=manifest.getTagList("Containers",10);
        for(int i=0;i<containers.tagCount();i++){NBTTagCompound record=containers.getCompoundTagAt(i);if(record.getInteger("Dimension")!=world.provider.getDimension() || record.getInteger("Wave")!=wave)continue;NBTTagCompound source=record.getCompoundTag("Tile");TileEntity tile=world.getTileEntity(position(source));require(tile instanceof IInventory,"Vanilla source chest disappeared");Map<String,Integer> expected=indexed(source.getTagList("Items",10)),actual=contents((IInventory)tile,true);if(!expected.equals(actual)){Map<String,Integer> missing=new TreeMap<>(expected),unexpected=new TreeMap<>(actual);missing.entrySet().removeAll(actual.entrySet());unexpected.entrySet().removeAll(expected.entrySet());System.out.println("VANILLA_CHEST_DIFF missing="+missing+" unexpected="+unexpected);}require(expected.equals(actual),"Vanilla source chest slots/data changed");}
    }
    private static void verifyPlayer(MinecraftServer server) throws Exception {
        WorldServer world=server.getWorld(0);UUID id=manifest.getUniqueId("Player");
        NBTTagCompound expected=read(new File(folder,"upgrade-player-source.nbt"));
        EntityPlayerMP player=new EntityPlayerMP(server,world,new GameProfile(id,"UpgradeFixture"),new PlayerInteractionManager(world));world.getSaveHandler().getPlayerNBTManager().readPlayerData(player);
        // InventoryPlayer serializes armour as 100..103 and offhand as 150.
        NBTTagList actual=player.inventory.writeToNBT(new NBTTagList());require(indexed(expected.getTagList("Inventory",10)).equals(indexedCurrent(actual)),"Player inventory slots, counts or custom data changed");
        require(indexed(expected.getTagList("EnderItems",10)).equals(indexedCurrent(player.getInventoryEnderChest().saveInventoryToNBT())),"Ender chest slots/data changed");
        require(player.getUniqueID().equals(id),"Player UUID changed");world.getSaveHandler().getPlayerNBTManager().writePlayerData(player);
    }
    private static Map<String,Integer> indexedCurrent(NBTTagList list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.tagCount();i++)add(map,list.getCompoundTagAt(i),true);return map;}
    private static void verifyVanilla(MinecraftServer server) {
        NBTTagList list=manifest.getTagList("Vanilla",10);
        for(int i=0;i<list.tagCount();i++) {NBTTagCompound record=list.getCompoundTagAt(i),source=record.getCompoundTag("Entity");WorldServer world=server.getWorld(record.getInteger("Dimension"));NBTTagList pos=source.getTagList("Pos",6);world.getChunkFromChunkCoords(((int)pos.getDoubleAt(0))>>4,((int)pos.getDoubleAt(2))>>4);Entity found=null;for(Entity e:world.loadedEntityList)if(e.getUniqueID().equals(source.getUniqueId("UUID")))found=e;require(found!=null,"Vanilla entity disappeared");NBTTagCompound actual=new NBTTagCompound();found.writeToNBTOptional(actual);require(actual.getString("id").equals(record.getString("ExpectedType")),"Vanilla entity-name conversion failed");require(found.dimension==record.getInteger("Dimension"),"Vanilla entity dimension changed");if(found instanceof IInventory)require(indexed(source.getTagList("Items",10)).equals(contents((IInventory)found,true)),"Vanilla minecart inventory changed");if(record.getString("ExpectedType").equals("minecraft:armor_stand"))require(indexedEquipment(source.getTagList("ArmorItems",10)).equals(indexedEquipment(actual.getTagList("ArmorItems",10))),"Vanilla armour stand equipment changed");}
    }
    private static Map<String,Integer> indexedEquipment(NBTTagList list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.tagCount();i++){NBTTagCompound item=expectedItem(list.getCompoundTagAt(i));item.setByte("Slot",(byte)i);add(map,item,true);}return map;}
}
