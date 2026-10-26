package cyano.lootable.buildtest;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.inventory.container.Slot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.dispenser.Position;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Acceptance against worlds written by the actual historical production jars. */
final class WorldUpgradeChecks {
    private static CompoundNBT manifest,ledger;
    private static File folder;
    private static boolean old,native112,native113;
    private static int sourceVersion;
    private static int checks;
    static void run(MinecraftServer server,String phase) throws Exception {
        folder=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getSaveHandler().getWorldDirectory();
        manifest=read(new File(folder,"upgrade-fixtures.nbt"));old=manifest.getString("Minecraft").equals("1.10.2");native112=manifest.getString("Minecraft").equals("1.12.2");native113=manifest.getString("Minecraft").equals("1.13.2");sourceVersion=old?512:native112?1343:native113?1631:922;
        File stored=new File(folder,"upgrade-recovered.nbt");ledger=stored.isFile()?read(stored):new CompoundNBT();checks=0;
        if(!ledger.contains("Recoveries",10))ledger.put("Recoveries",new CompoundNBT());
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        int wave=phase.startsWith("upgrade-near")?0:phase.startsWith("upgrade-far")?1:phase.equals("upgrade-late")?2:-1;
        boolean recover=phase.equals("upgrade-near-recover") || phase.equals("upgrade-far-reload") || phase.equals("upgrade-late") || phase.equals("upgrade-return");
        ListNBT bodies=manifest.getList("Bodies",10);
        for(int i=0;i<bodies.size();i++) {
            CompoundNBT record=bodies.getCompound(i);if(wave>=0 && record.getInt("Wave")!=wave)continue;
            ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));CompoundNBT source=record.getCompound("Body");
            int x=(int)source.getList("Pos",6).getDouble(0),z=(int)source.getList("Pos",6).getDouble(2);RuntimeChecks.awaitChunk(world,x>>4,z>>4);
            UUID uuid=source.getUniqueId("UUID");EntityLootableBody body=find(world,uuid);String id=uuid.toString();
            if(ledger.getString("Removed").equals(id)) {require(body==null,"Journal resurrected a deliberately removed corpse");continue;}
            require(body!=null,"Missing historical corpse "+uuid);
            require(body.getUniqueID().equals(uuid) && body.dimension.getId()==record.getInt("Dimension"),"UUID/dimension changed");
            require(body.getCustomNameTag().equals(source.contains("Name",8)?source.getString("Name"):source.getString("CustomName")),"Owner attribution changed");
            require(body.posX==source.getList("Pos",6).getDouble(0) && body.posY==source.getList("Pos",6).getDouble(1) && body.posZ==source.getList("Pos",6).getDouble(2),"Position changed in dimension "+world.dimension.getType().getId()+": "+body.getPositionVector());
            CompoundNBT actual=save(body);require(actual.getInt("LootableBodiesDataVersion")==cyano.lootable.upgrade.UpgradePreservation.DATA_VERSION,"Missing corpse migration marker");
            require(NameLifetimeChecks.same(source.get("LootableBodiesInvalidFields"),actual.get("LootableBodiesInvalidFields")),"Original invalid-field evidence changed");
            Map<String,Integer> expected=expectedInventory(source);Map<String,Integer> observed=inventory(actual);
            CompoundNBT recovery=ledger.getCompound("Recoveries").getCompound(id);
            if(!recovery.contains("Storage"))verifySlots(source,actual);
            Map<String,Integer> recovered=storage(world,recovery);
            merge(observed,recovered);require(expected.equals(observed),"Inventory conservation mismatch "+uuid+" expected="+expected+" actual="+observed);
            verifyRaw(source,actual);
            if(recover) {
                if(!ledger.getCompound("Recoveries").contains(id,10)) {ledger.getCompound("Recoveries").put(id,new CompoundNBT());recovery=ledger.getCompound("Recoveries").getCompound(id);}
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
                CompoundNBT record=bodies.getCompound(i);ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));EntityLootableBody body=find(world,record.getCompound("Body").getUniqueId("UUID"));
                if(body!=null && body.isEmpty()) {ledger.putString("Removed",body.getUniqueID().toString());BlockPos position=body.getPosition();world.removeEntity(body);world.getChunkAt(position).setModified(true);break;}
            }
            require(!ledger.getString("Removed").isEmpty(),"No safely empty corpse available for non-resurrection check");
        }
        // This harness stops before the game loop. Advance the clock as a real
        // played session would, so vanilla actually writes its loaded chunks.
        for(int dimension:new int[]{0,-1,1}) {ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(dimension));world.getWorldInfo().setGameTime(world.getGameTime()+1);}
        server.save(false,true,false);FixtureApi.flush(server);
        try(FileOutputStream output=new FileOutputStream(stored)){CompressedStreamTools.writeCompressed(ledger,output);}
        try(PrintWriter out=new PrintWriter(new File(folder,phase+".pass"),"UTF-8")){out.println("checks="+checks+" historical="+manifest.getString("Minecraft")+"/"+manifest.getString("ModVersion"));}
        System.out.println("LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase="+phase+" checks="+checks);
    }
    private static void require(boolean ok,String message){checks++;RuntimeChecks.require(ok,message);}
    private static CompoundNBT read(File file) throws IOException {try(FileInputStream input=new FileInputStream(file)){return CompressedStreamTools.readCompressed(input);}}
    private static CompoundNBT save(EntityLootableBody body){CompoundNBT nbt=new CompoundNBT();body.writeAdditional(nbt);return nbt;}
    private static EntityLootableBody find(ServerWorld world,UUID id){for(Entity entity:FixtureApi.entities(world))if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(id))return (EntityLootableBody)entity;return null;}
    private static boolean known(CompoundNBT item){return item.contains("id",8) && net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(new ResourceLocation(item.getString("id"))) && !item.getString("id").equals("minecraft:air");}
    private static CompoundNBT expectedItem(CompoundNBT source) {return expectedItem(source,sourceVersion);}
    static CompoundNBT expectedItem(CompoundNBT source,int version) {
        // Independent vanilla oracle. Production additionally checks conservation,
        // resolves registries and shields owned entity payloads; this path does not.
        if(!source.contains("id",8) || !source.contains("Count"))return source.copy();
        com.mojang.datafixers.DataFixer vanilla=net.minecraft.util.datafix.DataFixesManager.getDataFixer();
        CompoundNBT input=source.copy();
        if(version<704 && (source.getString("id").equals("minecraft:spawn_egg") || source.getString("id").equals("minecraft:chest") || source.getString("id").equals("minecraft:banner"))) {
            // These three sealed writer fixtures exercise the broken vanilla
            // namespace boundary. Their expected IDs are explicit, independent
            // of the production bridge and registry lookup.
            input=cyano.lootable.upgrade.NativeNbt.update(vanilla,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,input,512,703);
            CompoundNBT tag=input.getCompound("tag");
            if(source.getString("id").equals("minecraft:spawn_egg")) {
                require(tag.getCompound("EntityTag").getString("id").equals("Pig"),"Unexpected historical egg fixture");
                tag.getCompound("EntityTag").putString("id","minecraft:pig");
            } else tag.getCompound("BlockEntityTag").putString("id",source.getString("id"));
            version=705;
        }
        CompoundNBT result=cyano.lootable.upgrade.NativeNbt.update(vanilla,net.minecraft.util.datafix.TypeReferences.ITEM_STACK,input,version,cyano.lootable.upgrade.UpgradePreservation.MINECRAFT_VERSION);
        require(Objects.equals(source.get("Count"),result.get("Count")),"Vanilla oracle changed an item count");
        require(Objects.equals(source.get("Slot"),result.get("Slot")),"Vanilla oracle changed an item slot");
        require(NameLifetimeChecks.same(source.get("ForgeCaps"),result.get("ForgeCaps")),"Vanilla oracle changed opaque capabilities");
        return result;
    }
    private static void verifySlots(CompoundNBT source,CompoundNBT actual) {
        Map<Integer,CompoundNBT> slots=new TreeMap<>();
        ListNBT entries=source.getList("Equipment",10);
        for(int i=0;i<entries.size();i++) {
            CompoundNBT raw=entries.getCompound(i),item=expectedItem(raw);
            if(!known(item) || !raw.contains("Slot",1))continue;
            int slot=raw.getByte("Slot");if(slot<0 || slot>=48 || slots.containsKey(slot))continue;
            item.remove("Slot");slots.put(slot,item);
        }
        Map<Integer,CompoundNBT> saved=new TreeMap<>();ListNBT entriesNow=actual.getList("Equipment",10);
        for(int i=0;i<entriesNow.size();i++){CompoundNBT item=entriesNow.getCompound(i).copy();int slot=item.getByte("Slot");item.remove("Slot");saved.put(slot,item);}
        for(Map.Entry<Integer,CompoundNBT> entry:slots.entrySet())
            require(Objects.equals(TypedNbt.canonical(canonicalItem(entry.getValue())),TypedNbt.canonical(canonicalItem(saved.get(entry.getKey())))),"Historical inventory slot moved: "+entry.getKey()+" expected="+entry.getValue()+" actual="+saved.get(entry.getKey()));
    }
    private static CompoundNBT canonicalItem(CompoundNBT source) {
        if(source==null)return null;
        CompoundNBT item=source.copy();String id=item.getString("id");
        String[] colours={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
        for(int colour=0;colour<colours.length;colour++)if(id.equals("minecraft:"+colours[colour]+"_banner")) {
            CompoundNBT tag=item.getCompound("tag"),block=tag.getCompound("BlockEntityTag");
            // The 1.11 item fixer removes Base; native DFU instead leaves its
            // inverted copy behind. Its exact matching colour is redundant
            // with the flattened item ID. Everything else remains typed.
            if(block.contains("Base",3) && block.getInt("Base")==colour)block.remove("Base");
            if(block.getString("id").equals("minecraft:banner"))block.remove("id");
            if(block.isEmpty())tag.remove("BlockEntityTag");
        }
        return item;
    }
    private static void add(Map<String,Integer> map,CompoundNBT source,boolean indexed) {
        if(!source.contains("id",8) || source.getByte("Count")<=0)return;
        CompoundNBT item=canonicalItem(source);int count=item.getByte("Count")&255;item.remove("Count");if(!indexed)item.remove("Slot");
        String key=TypedNbt.canonical(item);map.put(key,map.getOrDefault(key,0)+count);
    }
    private static void merge(Map<String,Integer> a,Map<String,Integer> b){for(Map.Entry<String,Integer> item:b.entrySet())a.put(item.getKey(),a.getOrDefault(item.getKey(),0)+item.getValue());}
    private static Map<String,Integer> inventory(CompoundNBT body){Map<String,Integer> map=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}){ListNBT items=body.getList(key,10);for(int i=0;i<items.size();i++)add(map,items.getCompound(i),false);}return map;}
    private static Map<String,Integer> expectedInventory(CompoundNBT source) {
        Map<String,Integer> expected=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}) {ListNBT list=source.getList(key,10);for(int i=0;i<list.size();i++){CompoundNBT item=expectedItem(list.getCompound(i));if(known(item))add(expected,item,false);}}
        ListNBT records=source.getList("LootableBodiesUnresolved",10);for(int i=0;i<records.size();i++){CompoundNBT item=expectedItem(records.getCompound(i).getCompound("Data"));if(known(item))add(expected,item,false);}return expected;
    }
    private static void verifyRaw(CompoundNBT source,CompoundNBT saved) {
        List<CompoundNBT> expected=new ArrayList<>();
        for(String key:new String[]{"Equipment","Aux"}) {ListNBT list=source.getList(key,10);for(int i=0;i<list.size();i++)if(!known(expectedItem(list.getCompound(i)))){CompoundNBT record=new CompoundNBT();record.putString("Source",key);record.put("Data",list.getCompound(i).copy());expected.add(record);}}
        ListNBT records=source.getList("LootableBodiesUnresolved",10);for(int i=0;i<records.size();i++){CompoundNBT record=records.getCompound(i);if(!record.contains("Data",10) || !known(expectedItem(record.getCompound("Data"))))expected.add(record);}
        ListNBT actual=saved.getList("LootableBodiesUnresolved",10);require(expected.size()==actual.size(),"Raw recovery count changed");
        for(int i=0;i<expected.size();i++) {CompoundNBT e=expected.get(i),a=actual.getCompound(i);require(e.getString("Source").equals(a.getString("Source")) && NameLifetimeChecks.same(e.get("Data"),a.get("Data")),"Original recovery Source/Data changed");require(a.getCompound("SourceVersions").getInt("DataVersion")== (sourceVersion),"Recovery lost its original version context");}
    }
    private static Map<String,Integer> contents(IInventory inventory,boolean indexed) {Map<String,Integer> map=new TreeMap<>();for(int slot=0;slot<inventory.getSizeInventory();slot++){ItemStack stack=inventory.getStackInSlot(slot);if(stack.isEmpty())continue;CompoundNBT item=stack.write(new CompoundNBT());item.putByte("Slot",(byte)slot);add(map,item,indexed);}return map;}
    private static Map<String,Integer> indexed(ListNBT list) {Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++)add(map,expectedItem(list.getCompound(i)),true);return map;}
    private static ListNBT storageList(CompoundNBT recovery){if(!recovery.contains("Storage",9))recovery.put("Storage",new ListNBT());return recovery.getList("Storage",10);}
    private static BlockPos position(CompoundNBT nbt){return new BlockPos(nbt.getInt("x"),nbt.getInt("y"),nbt.getInt("z"));}
    private static Map<String,Integer> storage(ServerWorld world,CompoundNBT recovery) {Map<String,Integer> map=new TreeMap<>();ListNBT list=storageList(recovery);for(int i=0;i<list.size();i++){BlockPos position=position(list.getCompound(i));world.getChunk(position);TileEntity tile=world.getTileEntity(position);require(tile instanceof IInventory,"Recovery chest disappeared");merge(map,contents((IInventory)tile,false));}return map;}
    private static void recover(ServerWorld world,EntityLootableBody body,CompoundNBT recovery) {
        // Original fixtures deliberately retain their source terrain. Clear only
        // this disposable interaction space before ticking the body: Nether
        // netherrack otherwise invokes the existing wall-escape gameplay.
        BlockPos center=body.getPosition();
        for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)world.removeBlock(center.add(x,y,z),false);
        ServerPlayerEntity visitor=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("00000000-2222-4222-8222-000000000099"),"RecoveryVisitor"));visitor.setHealth(20);visitor.inventory.clear();visitor.setPosition(body.posX,body.posY,body.posZ);
        CorpseContainer container=new CorpseContainer(visitor.inventory,body);
        for(int attempt=0;attempt<200 && !inventory(save(body)).isEmpty();attempt++) {
            for(int slot=0;slot<48;slot++)container.transferStackInSlot(visitor,slot);
            flush(world,body,visitor.inventory,recovery);body.baseTick();
        }
        flush(world,body,visitor.inventory,recovery);
        require(contents(visitor.inventory,false).isEmpty(),"Recovery visitor retained unsaved items");
    }
    private static void flush(ServerWorld world,EntityLootableBody body,IInventory player,CompoundNBT recovery) {
        ListNBT stored=storageList(recovery);
        for(int slot=0;slot<player.getSizeInventory();slot++) {
            ItemStack stack=player.getStackInSlot(slot);if(stack.isEmpty())continue;IInventory destination=null;int empty=-1;
            if(stored.size()>0) {destination=(IInventory)world.getTileEntity(position(stored.getCompound(stored.size()-1)));for(int i=0;i<destination.getSizeInventory();i++)if(destination.getStackInSlot(i).isEmpty()){empty=i;break;}}
            if(empty<0){BlockPos pos=new BlockPos(body.posX+10,80+stored.size(),body.posZ+10);world.setBlockState(pos,net.minecraft.block.Blocks.CHEST.getDefaultState());destination=(IInventory)world.getTileEntity(pos);CompoundNBT place=new CompoundNBT();place.putInt("x",pos.getX());place.putInt("y",pos.getY());place.putInt("z",pos.getZ());stored.add(place);empty=0;}
            destination.setInventorySlotContents(empty,stack.copy());destination.markDirty();player.setInventorySlotContents(slot,ItemStack.EMPTY);
        }
    }
    private static void verifyChest(ServerWorld world,int wave) {
        ListNBT containers=manifest.getList("Containers",10);
        for(int i=0;i<containers.size();i++){CompoundNBT record=containers.getCompound(i);if(record.getInt("Dimension")!=world.dimension.getType().getId() || record.getInt("Wave")!=wave)continue;CompoundNBT source=record.getCompound("Tile");TileEntity tile=world.getTileEntity(position(source));require(tile instanceof IInventory,"Vanilla source chest disappeared");Map<String,Integer> expected=indexed(source.getList("Items",10)),actual=contents((IInventory)tile,true);if(!expected.equals(actual)){Map<String,Integer> missing=new TreeMap<>(expected),unexpected=new TreeMap<>(actual);missing.entrySet().removeAll(actual.entrySet());unexpected.entrySet().removeAll(expected.entrySet());System.out.println("VANILLA_CHEST_DIFF missing="+missing+" unexpected="+unexpected);}require(expected.equals(actual),"Vanilla source chest slots/data changed");}
    }
    private static void verifyPlayer(MinecraftServer server) throws Exception {
        ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));UUID id=manifest.getUniqueId("Player");
        CompoundNBT expected=read(new File(folder,"upgrade-player-source.nbt"));
        ServerPlayerEntity player=new ServerPlayerEntity(server,world,new GameProfile(id,"UpgradeFixture"),new PlayerInteractionManager(world));world.getSaveHandler().readPlayerData(player);
        // PlayerInventory serializes armour as 100..103 and offhand as 150.
        ListNBT actual=player.inventory.write(new ListNBT());require(indexedPlayer(expected,"Inventory").equals(indexedCurrent(actual)),"Player inventory slots, counts or custom data changed");
        require(indexedPlayer(expected,"EnderItems").equals(indexedCurrent(player.getInventoryEnderChest().write())),"Ender chest slots/data changed");
        require(player.getUniqueID().equals(id),"Player UUID changed");world.getSaveHandler().writePlayerData(player);
    }
    private static Map<String,Integer> indexedPlayer(CompoundNBT player,String key) {
        int version=player.contains("DataVersion",3)?player.getInt("DataVersion"):(sourceVersion);
        Map<String,Integer> map=new TreeMap<>();
        for(INBT entry:player.getList(key,10))add(map,expectedItem((CompoundNBT)entry,version),true);
        return map;
    }
    private static Map<String,Integer> indexedCurrent(ListNBT list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++)add(map,list.getCompound(i),true);return map;}
    private static void verifyVanilla(MinecraftServer server) {
        ListNBT list=manifest.getList("Vanilla",10);
        for(int i=0;i<list.size();i++) {CompoundNBT record=list.getCompound(i),source=record.getCompound("Entity");ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(record.getInt("Dimension")));ListNBT pos=source.getList("Pos",6);world.getChunk(((int)pos.getDouble(0))>>4,((int)pos.getDouble(2))>>4);Entity found=null;for(Entity e:FixtureApi.entities(world))if(e.getUniqueID().equals(source.getUniqueId("UUID")))found=e;require(found!=null,"Vanilla entity disappeared");CompoundNBT actual=new CompoundNBT();found.writeUnlessPassenger(actual);require(actual.getString("id").equals(record.getString("ExpectedType")),"Vanilla entity-name conversion failed");require(found.dimension.getId()==record.getInt("Dimension"),"Vanilla entity dimension changed");if(record.getString("ExpectedType").equals("minecraft:villager"))verifyVillager(source,actual);if(found instanceof IInventory)require(indexed(source.getList("Items",10)).equals(contents((IInventory)found,true)),"Vanilla minecart inventory changed");if(record.getString("ExpectedType").equals("minecraft:armor_stand"))require(indexedEquipment(source.getList("ArmorItems",10)).equals(indexedNativeEquipment(actual.getList("ArmorItems",10))),"Vanilla armour stand equipment changed");}
    }
    private static void verifyVillager(CompoundNBT source,CompoundNBT actual) {
        CompoundNBT expected=cyano.lootable.upgrade.NativeNbt.update(net.minecraft.util.datafix.DataFixesManager.getDataFixer(),net.minecraft.util.datafix.TypeReferences.ENTITY,source.copy(),sourceVersion,cyano.lootable.upgrade.UpgradePreservation.MINECRAFT_VERSION);
        require(NameLifetimeChecks.same(expected.get("VillagerData"),actual.get("VillagerData")),"Vanilla villager profession, career or level changed");
        require(indexed(source.getList("Inventory",10)).equals(indexedCurrent(actual.getList("Inventory",10))),"Vanilla villager inventory changed");
    }
    private static Map<String,Integer> indexedNativeEquipment(ListNBT list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){CompoundNBT item=list.getCompound(i).copy();item.putByte("Slot",(byte)i);add(map,item,true);}return map;}
    private static Map<String,Integer> indexedEquipment(ListNBT list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){CompoundNBT item=expectedItem(list.getCompound(i));item.putByte("Slot",(byte)i);add(map,item,true);}return map;}
}
