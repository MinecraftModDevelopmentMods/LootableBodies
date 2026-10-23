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
    private static boolean old,native112;
    private static int checks;
    static void run(MinecraftServer server,String phase) throws Exception {
        folder=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getSaveHandler().getWorldDirectory();
        manifest=read(new File(folder,"upgrade-fixtures.nbt"));old=manifest.getString("Minecraft").equals("1.10.2");native112=manifest.getString("Minecraft").equals("1.12.2");
        File stored=new File(folder,"upgrade-recovered.nbt");ledger=stored.isFile()?read(stored):new NBTTagCompound();checks=0;
        if(!ledger.contains("Recoveries",10))ledger.setTag("Recoveries",new NBTTagCompound());
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        int wave=phase.startsWith("upgrade-near")?0:phase.startsWith("upgrade-far")?1:phase.equals("upgrade-late")?2:-1;
        boolean recover=phase.equals("upgrade-near-recover") || phase.equals("upgrade-far-reload") || phase.equals("upgrade-late") || phase.equals("upgrade-return");
        NBTTagList bodies=manifest.getList("Bodies",10);
        for(int i=0;i<bodies.size();i++) {
            NBTTagCompound record=bodies.getCompound(i);if(wave>=0 && record.getInt("Wave")!=wave)continue;
            WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));NBTTagCompound source=record.getCompound("Body");
            int x=(int)source.getList("Pos",6).getDouble(0),z=(int)source.getList("Pos",6).getDouble(2);RuntimeChecks.awaitChunk(world,x>>4,z>>4);
            UUID uuid=source.getUniqueId("UUID");EntityLootableBody body=find(world,uuid);String id=uuid.toString();
            if(ledger.getString("Removed").equals(id)) {require(body==null,"Journal resurrected a deliberately removed corpse");continue;}
            require(body!=null,"Missing historical corpse "+uuid);
            require(body.getUniqueID().equals(uuid) && body.dimension.getId()==record.getInt("Dimension"),"UUID/dimension changed");
            require(body.getCustomNameTag().equals(source.getString("CustomName")),"Owner attribution changed");
            require(body.posX==source.getList("Pos",6).getDouble(0) && body.posY==source.getList("Pos",6).getDouble(1) && body.posZ==source.getList("Pos",6).getDouble(2),"Position changed in dimension "+world.dimension.getType().getId()+": "+body.getPositionVector());
            NBTTagCompound actual=save(body);require(actual.getInt("LootableBodiesDataVersion")==3,"Missing corpse migration marker");
            require(NameLifetimeChecks.same(source.getTag("LootableBodiesInvalidFields"),actual.getTag("LootableBodiesInvalidFields")),"Original invalid-field evidence changed");
            Map<String,Integer> expected=expectedInventory(source);Map<String,Integer> observed=inventory(actual);
            NBTTagCompound recovery=ledger.getCompound("Recoveries").getCompound(id);
            if(!recovery.hasKey("Storage"))verifySlots(source,actual);
            Map<String,Integer> recovered=storage(world,recovery);
            merge(observed,recovered);require(expected.equals(observed),"Inventory conservation mismatch "+uuid+" expected="+expected+" actual="+observed);
            verifyRaw(source,actual);
            if(recover) {
                if(!ledger.getCompound("Recoveries").contains(id,10)) {ledger.getCompound("Recoveries").setTag(id,new NBTTagCompound());recovery=ledger.getCompound("Recoveries").getCompound(id);}
                recover(world,body,recovery);
                require(inventory(save(body)).isEmpty(),"Supported inventory remained after GUI recovery");
                require(expected.equals(storage(world,recovery)),"GUI recovery lost or duplicated complete item data");
                verifyRaw(source,save(body));
            }
            verifyChest(world,record.getInt("Wave"));
        }
        verifyPlayer(server);
        if(wave==0 || wave<0) verifyVanilla(server);
        if(phase.equals("upgrade-final") && ledger.getString("Removed").isEmpty()) {
            for(int i=0;i<bodies.size();i++) {
                NBTTagCompound record=bodies.getCompound(i);WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));EntityLootableBody body=find(world,record.getCompound("Body").getUniqueId("UUID"));
                if(body!=null && body.isEmpty()) {ledger.setString("Removed",body.getUniqueID().toString());BlockPos position=body.getPosition();world.removeEntityDangerously(body);world.getChunk(position).markDirty();break;}
            }
            require(!ledger.getString("Removed").isEmpty(),"No safely empty corpse available for non-resurrection check");
        }
        // This harness stops before the game loop. Advance the clock as a real
        // played session would, so vanilla actually writes its loaded chunks.
        for(int dimension:new int[]{0,-1,1}) {WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(dimension));world.getWorldInfo().setWorldTotalTime(world.getGameTime()+1);}
        server.saveAllWorlds(false);net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        try(FileOutputStream output=new FileOutputStream(stored)){CompressedStreamTools.writeCompressed(ledger,output);}
        try(PrintWriter out=new PrintWriter(new File(folder,phase+".pass"),"UTF-8")){out.println("checks="+checks+" historical="+manifest.getString("Minecraft")+"/"+manifest.getString("ModVersion"));}
        System.out.println("LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase="+phase+" checks="+checks);
    }
    private static void require(boolean ok,String message){checks++;RuntimeChecks.require(ok,message);}
    private static NBTTagCompound read(File file) throws IOException {try(FileInputStream input=new FileInputStream(file)){return CompressedStreamTools.readCompressed(input);}}
    private static NBTTagCompound save(EntityLootableBody body){NBTTagCompound nbt=new NBTTagCompound();body.writeAdditional(nbt);return nbt;}
    private static EntityLootableBody find(WorldServer world,UUID id){for(Entity entity:world.loadedEntityList)if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(id))return (EntityLootableBody)entity;return null;}
    private static boolean known(NBTTagCompound item){return item.contains("id",8) && net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(new ResourceLocation(item.getString("id"))) && !item.getString("id").equals("minecraft:air");}
    private static NBTTagCompound expectedItem(NBTTagCompound source) {return expectedItem(source,old?512:native112?1343:922);}
    static NBTTagCompound expectedItem(NBTTagCompound source,int version) {
        // Independent vanilla oracle. Production additionally checks conservation,
        // resolves registries and shields owned entity payloads; this path does not.
        if(!source.contains("id",8) || !source.hasKey("Count"))return source.copy();
        com.mojang.datafixers.DataFixer vanilla=net.minecraft.util.datafix.DataFixesManager.getDataFixer();
        NBTTagCompound input=source.copy();
        if(version<704 && (source.getString("id").equals("minecraft:spawn_egg") || source.getString("id").equals("minecraft:chest") || source.getString("id").equals("minecraft:banner"))) {
            // These three sealed writer fixtures exercise the broken vanilla
            // namespace boundary. Their expected IDs are explicit, independent
            // of the production bridge and registry lookup.
            input=NBTUtil.update(vanilla,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,input,512,703);
            NBTTagCompound tag=input.getCompound("tag");
            if(source.getString("id").equals("minecraft:spawn_egg")) {
                require(tag.getCompound("EntityTag").getString("id").equals("Pig"),"Unexpected historical egg fixture");
                tag.getCompound("EntityTag").setString("id","minecraft:pig");
            } else tag.getCompound("BlockEntityTag").setString("id",source.getString("id"));
            version=705;
        }
        NBTTagCompound result=NBTUtil.update(vanilla,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,input,version,1631);
        require(Objects.equals(source.getTag("Count"),result.getTag("Count")),"Vanilla oracle changed an item count");
        require(Objects.equals(source.getTag("Slot"),result.getTag("Slot")),"Vanilla oracle changed an item slot");
        require(NameLifetimeChecks.same(source.getTag("ForgeCaps"),result.getTag("ForgeCaps")),"Vanilla oracle changed opaque capabilities");
        return result;
    }
    private static void verifySlots(NBTTagCompound source,NBTTagCompound actual) {
        Map<Integer,NBTTagCompound> slots=new TreeMap<>();
        NBTTagList entries=source.getList("Equipment",10);
        for(int i=0;i<entries.size();i++) {
            NBTTagCompound raw=entries.getCompound(i),item=expectedItem(raw);
            if(!known(item) || !raw.contains("Slot",1))continue;
            int slot=raw.getByte("Slot");if(slot<0 || slot>=48 || slots.containsKey(slot))continue;
            item.removeTag("Slot");slots.put(slot,item);
        }
        Map<Integer,NBTTagCompound> saved=new TreeMap<>();NBTTagList entriesNow=actual.getList("Equipment",10);
        for(int i=0;i<entriesNow.size();i++){NBTTagCompound item=entriesNow.getCompound(i).copy();int slot=item.getByte("Slot");item.removeTag("Slot");saved.put(slot,item);}
        for(Map.Entry<Integer,NBTTagCompound> entry:slots.entrySet())
            require(Objects.equals(TypedNbt.canonical(canonicalItem(entry.getValue())),TypedNbt.canonical(canonicalItem(saved.get(entry.getKey())))),"Historical inventory slot moved: "+entry.getKey()+" expected="+entry.getValue()+" actual="+saved.get(entry.getKey()));
    }
    private static NBTTagCompound canonicalItem(NBTTagCompound source) {
        if(source==null)return null;
        NBTTagCompound item=source.copy();String id=item.getString("id");
        String[] colours={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
        for(int colour=0;colour<colours.length;colour++)if(id.equals("minecraft:"+colours[colour]+"_banner")) {
            NBTTagCompound tag=item.getCompound("tag"),block=tag.getCompound("BlockEntityTag");
            // The 1.11 item fixer removes Base; native DFU instead leaves its
            // inverted copy behind. Its exact matching colour is redundant
            // with the flattened item ID. Everything else remains typed.
            if(block.contains("Base",3) && block.getInt("Base")==colour)block.removeTag("Base");
            if(block.getString("id").equals("minecraft:banner"))block.removeTag("id");
            if(block.isEmpty())tag.removeTag("BlockEntityTag");
        }
        return item;
    }
    private static void add(Map<String,Integer> map,NBTTagCompound source,boolean indexed) {
        if(!source.contains("id",8) || source.getByte("Count")<=0)return;
        NBTTagCompound item=canonicalItem(source);int count=item.getByte("Count")&255;item.removeTag("Count");if(!indexed)item.removeTag("Slot");
        String key=TypedNbt.canonical(item);map.put(key,map.getOrDefault(key,0)+count);
    }
    private static void merge(Map<String,Integer> a,Map<String,Integer> b){for(Map.Entry<String,Integer> item:b.entrySet())a.put(item.getKey(),a.getOrDefault(item.getKey(),0)+item.getValue());}
    private static Map<String,Integer> inventory(NBTTagCompound body){Map<String,Integer> map=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}){NBTTagList items=body.getList(key,10);for(int i=0;i<items.size();i++)add(map,items.getCompound(i),false);}return map;}
    private static Map<String,Integer> expectedInventory(NBTTagCompound source) {
        Map<String,Integer> expected=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}) {NBTTagList list=source.getList(key,10);for(int i=0;i<list.size();i++){NBTTagCompound item=expectedItem(list.getCompound(i));if(known(item))add(expected,item,false);}}
        NBTTagList records=source.getList("LootableBodiesUnresolved",10);for(int i=0;i<records.size();i++){NBTTagCompound item=expectedItem(records.getCompound(i).getCompound("Data"));if(known(item))add(expected,item,false);}return expected;
    }
    private static void verifyRaw(NBTTagCompound source,NBTTagCompound saved) {
        List<NBTTagCompound> expected=new ArrayList<>();
        for(String key:new String[]{"Equipment","Aux"}) {NBTTagList list=source.getList(key,10);for(int i=0;i<list.size();i++)if(!known(expectedItem(list.getCompound(i)))){NBTTagCompound record=new NBTTagCompound();record.setString("Source",key);record.setTag("Data",list.getCompound(i).copy());expected.add(record);}}
        NBTTagList records=source.getList("LootableBodiesUnresolved",10);for(int i=0;i<records.size();i++){NBTTagCompound record=records.getCompound(i);if(!record.contains("Data",10) || !known(expectedItem(record.getCompound("Data"))))expected.add(record);}
        NBTTagList actual=saved.getList("LootableBodiesUnresolved",10);require(expected.size()==actual.size(),"Raw recovery count changed");
        for(int i=0;i<expected.size();i++) {NBTTagCompound e=expected.get(i),a=actual.getCompound(i);require(e.getString("Source").equals(a.getString("Source")) && NameLifetimeChecks.same(e.getTag("Data"),a.getTag("Data")),"Original recovery Source/Data changed");require(a.getCompound("SourceVersions").getInt("DataVersion")== (old?512:native112?1343:922),"Recovery lost its original version context");}
    }
    private static Map<String,Integer> contents(IInventory inventory,boolean indexed) {Map<String,Integer> map=new TreeMap<>();for(int slot=0;slot<inventory.getSizeInventory();slot++){ItemStack stack=inventory.getStackInSlot(slot);if(stack.isEmpty())continue;NBTTagCompound item=stack.write(new NBTTagCompound());item.setByte("Slot",(byte)slot);add(map,item,indexed);}return map;}
    private static Map<String,Integer> indexed(NBTTagList list) {Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++)add(map,expectedItem(list.getCompound(i)),true);return map;}
    private static NBTTagList storageList(NBTTagCompound recovery){if(!recovery.contains("Storage",9))recovery.setTag("Storage",new NBTTagList());return recovery.getList("Storage",10);}
    private static BlockPos position(NBTTagCompound nbt){return new BlockPos(nbt.getInt("x"),nbt.getInt("y"),nbt.getInt("z"));}
    private static Map<String,Integer> storage(WorldServer world,NBTTagCompound recovery) {Map<String,Integer> map=new TreeMap<>();NBTTagList list=storageList(recovery);for(int i=0;i<list.size();i++){BlockPos position=position(list.getCompound(i));world.getChunk(position);TileEntity tile=world.getTileEntity(position);require(tile instanceof IInventory,"Recovery chest disappeared");merge(map,contents((IInventory)tile,false));}return map;}
    private static void recover(WorldServer world,EntityLootableBody body,NBTTagCompound recovery) {
        // Original fixtures deliberately retain their source terrain. Clear only
        // this disposable interaction space before ticking the body: Nether
        // netherrack otherwise invokes the existing wall-escape gameplay.
        BlockPos center=body.getPosition();
        for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)world.removeBlock(center.add(x,y,z));
        EntityPlayerMP visitor=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("00000000-2222-4222-8222-000000000099"),"RecoveryVisitor"));visitor.setHealth(20);visitor.inventory.clear();visitor.setPosition(body.posX,body.posY,body.posZ);
        CorpseContainer container=new CorpseContainer(visitor.inventory,body);
        for(int attempt=0;attempt<200 && !inventory(save(body)).isEmpty();attempt++) {
            for(int slot=0;slot<48;slot++)container.transferStackInSlot(visitor,slot);
            flush(world,body,visitor.inventory,recovery);body.baseTick();
        }
        flush(world,body,visitor.inventory,recovery);
        require(contents(visitor.inventory,false).isEmpty(),"Recovery visitor retained unsaved items");
    }
    private static void flush(WorldServer world,EntityLootableBody body,IInventory player,NBTTagCompound recovery) {
        NBTTagList stored=storageList(recovery);
        for(int slot=0;slot<player.getSizeInventory();slot++) {
            ItemStack stack=player.getStackInSlot(slot);if(stack.isEmpty())continue;IInventory destination=null;int empty=-1;
            if(stored.size()>0) {destination=(IInventory)world.getTileEntity(position(stored.getCompound(stored.size()-1)));for(int i=0;i<destination.getSizeInventory();i++)if(destination.getStackInSlot(i).isEmpty()){empty=i;break;}}
            if(empty<0){BlockPos pos=new BlockPos(body.posX+10,80+stored.size(),body.posZ+10);world.setBlockState(pos,net.minecraft.init.Blocks.CHEST.getDefaultState());destination=(IInventory)world.getTileEntity(pos);NBTTagCompound place=new NBTTagCompound();place.setInt("x",pos.getX());place.setInt("y",pos.getY());place.setInt("z",pos.getZ());stored.add(place);empty=0;}
            destination.setInventorySlotContents(empty,stack.copy());destination.markDirty();player.setInventorySlotContents(slot,ItemStack.EMPTY);
        }
    }
    private static void verifyChest(WorldServer world,int wave) {
        NBTTagList containers=manifest.getList("Containers",10);
        for(int i=0;i<containers.size();i++){NBTTagCompound record=containers.getCompound(i);if(record.getInt("Dimension")!=world.dimension.getType().getId() || record.getInt("Wave")!=wave)continue;NBTTagCompound source=record.getCompound("Tile");TileEntity tile=world.getTileEntity(position(source));require(tile instanceof IInventory,"Vanilla source chest disappeared");Map<String,Integer> expected=indexed(source.getList("Items",10)),actual=contents((IInventory)tile,true);if(!expected.equals(actual)){Map<String,Integer> missing=new TreeMap<>(expected),unexpected=new TreeMap<>(actual);missing.entrySet().removeAll(actual.entrySet());unexpected.entrySet().removeAll(expected.entrySet());System.out.println("VANILLA_CHEST_DIFF missing="+missing+" unexpected="+unexpected);}require(expected.equals(actual),"Vanilla source chest slots/data changed");}
    }
    private static void verifyPlayer(MinecraftServer server) throws Exception {
        WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));UUID id=manifest.getUniqueId("Player");
        NBTTagCompound expected=read(new File(folder,"upgrade-player-source.nbt"));
        EntityPlayerMP player=new EntityPlayerMP(server,world,new GameProfile(id,"UpgradeFixture"),new PlayerInteractionManager(world));world.getSaveHandler().getPlayerNBTManager().readPlayerData(player);
        // InventoryPlayer serializes armour as 100..103 and offhand as 150.
        NBTTagList actual=player.inventory.write(new NBTTagList());require(indexedPlayer(expected,"Inventory").equals(indexedCurrent(actual)),"Player inventory slots, counts or custom data changed");
        require(indexedPlayer(expected,"EnderItems").equals(indexedCurrent(player.getInventoryEnderChest().write())),"Ender chest slots/data changed");
        require(player.getUniqueID().equals(id),"Player UUID changed");world.getSaveHandler().getPlayerNBTManager().writePlayerData(player);
    }
    private static Map<String,Integer> indexedPlayer(NBTTagCompound player,String key) {
        int version=player.contains("DataVersion",3)?player.getInt("DataVersion"):(old?512:native112?1343:922);
        Map<String,Integer> map=new TreeMap<>();
        for(INBTBase entry:player.getList(key,10))add(map,expectedItem((NBTTagCompound)entry,version),true);
        return map;
    }
    private static Map<String,Integer> indexedCurrent(NBTTagList list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++)add(map,list.getCompound(i),true);return map;}
    private static void verifyVanilla(MinecraftServer server) {
        NBTTagList list=manifest.getList("Vanilla",10);
        for(int i=0;i<list.size();i++) {NBTTagCompound record=list.getCompound(i),source=record.getCompound("Entity");WorldServer world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));NBTTagList pos=source.getList("Pos",6);world.getChunk(((int)pos.getDouble(0))>>4,((int)pos.getDouble(2))>>4);Entity found=null;for(Entity e:world.loadedEntityList)if(e.getUniqueID().equals(source.getUniqueId("UUID")))found=e;require(found!=null,"Vanilla entity disappeared");NBTTagCompound actual=new NBTTagCompound();found.writeUnlessPassenger(actual);require(actual.getString("id").equals(record.getString("ExpectedType")),"Vanilla entity-name conversion failed");require(found.dimension.getId()==record.getInt("Dimension"),"Vanilla entity dimension changed");if(found instanceof IInventory)require(indexed(source.getList("Items",10)).equals(contents((IInventory)found,true)),"Vanilla minecart inventory changed");if(record.getString("ExpectedType").equals("minecraft:armor_stand"))require(indexedEquipment(source.getList("ArmorItems",10)).equals(indexedNativeEquipment(actual.getList("ArmorItems",10))),"Vanilla armour stand equipment changed");}
    }
    private static Map<String,Integer> indexedNativeEquipment(NBTTagList list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){NBTTagCompound item=list.getCompound(i).copy();item.setByte("Slot",(byte)i);add(map,item,true);}return map;}
    private static Map<String,Integer> indexedEquipment(NBTTagList list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){NBTTagCompound item=expectedItem(list.getCompound(i));item.setByte("Slot",(byte)i);add(map,item,true);}return map;}
}
