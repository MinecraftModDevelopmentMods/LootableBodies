package cyano.lootable.buildtest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Acceptance against worlds written by the actual historical production jars. */
final class WorldUpgradeChecks {
    private static CompoundTag manifest,ledger;
    private static File folder;
    private static int sourceVersion;
    private static int checks;
    static void run(MinecraftServer server,String phase) throws Exception {
        folder=server.getLevel(FixtureApi.dimensionKey(0)).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        manifest=read(new File(folder,"upgrade-fixtures.nbt"));sourceVersion=HistoricalVersions.dataVersion(manifest.getString("Minecraft"));
        CompoundTag writerInventory=null;
        String writerHash=System.getProperty("lootablebodies.testWriterInventory");
        if(writerHash!=null) {
            File writerFile=new File(folder,"upgrade-writer-inventory.nbt");
            require(writerHash.equals(HexFormat.of().withUpperCase().formatHex(backupDigest(writerFile.toPath()))),
                    "Sealed bridge inventory oracle changed");
            writerInventory=read(writerFile);
            require(writerInventory.getAllKeys().size()==manifest.getList("Bodies",10).size(),
                    "Missing sealed bridge corpse inventories");
        }
        File stored=new File(folder,"upgrade-recovered.nbt");ledger=stored.isFile()?read(stored):new CompoundTag();checks=0;
        if(!ledger.contains("Recoveries",10))ledger.put("Recoveries",new CompoundTag());
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        int wave=phase.startsWith("upgrade-near")?0:phase.startsWith("upgrade-far")?1:phase.equals("upgrade-late")?2:-1;
        boolean recover=phase.equals("upgrade-near-recover") || phase.equals("upgrade-far-reload") || phase.equals("upgrade-late") || phase.equals("upgrade-return");
        ListTag bodies=manifest.getList("Bodies",10);
        for(int i=0;i<bodies.size();i++) {
            CompoundTag record=bodies.getCompound(i);if(wave>=0 && record.getInt("Wave")!=wave)continue;
            ServerLevel world=server.getLevel(FixtureApi.dimensionKey(record.getInt("Dimension")));CompoundTag source=record.getCompound("Body");
            int x=net.minecraft.util.Mth.floor(source.getList("Pos",6).getDouble(0)),z=net.minecraft.util.Mth.floor(source.getList("Pos",6).getDouble(2));RuntimeChecks.awaitChunk(world,x>>4,z>>4);
            UUID uuid=HistoricalUuid.read(source,"UUID");EntityLootableBody body=find(world,uuid);String id=uuid.toString();
            if(ledger.getString("Removed").equals(id)) {require(body==null,"Journal resurrected a deliberately removed corpse");continue;}
            require(body!=null,"Missing historical corpse "+uuid);
            require(body.getUUID().equals(uuid) && FixtureApi.dimensionId(body.level())==record.getInt("Dimension"),"UUID/dimension changed");
            require(body.getCustomNameTag().equals(source.contains("Name",8)?source.getString("Name"):source.getString("CustomName")),"Owner attribution changed");
            require(body.getX()==source.getList("Pos",6).getDouble(0) && body.getY()==source.getList("Pos",6).getDouble(1) && body.getZ()==source.getList("Pos",6).getDouble(2),"Position changed in dimension "+FixtureApi.dimensionId(world)+": "+body.position());
            CompoundTag actual=save(body);require(actual.getInt("LootableBodiesDataVersion")==cyano.lootable.upgrade.UpgradePreservation.DATA_VERSION,"Missing corpse migration marker");
            require(NameLifetimeChecks.same(source.get("LootableBodiesInvalidFields"),actual.get("LootableBodiesInvalidFields")),"Original invalid-field evidence changed");
            CompoundTag rawSource=source;
            int rawSourceVersion=sourceVersion;
            Map<Integer,CompoundTag> bridgeBanners=Map.of();
            if(writerInventory!=null) {
                CompoundTag writer=writerInventory.getCompound(id);
                require(writer.contains("Inventory",10) && writer.contains("DataVersion",3),
                        "Missing sealed writer inventory for "+id);
                rawSource=writer.getCompound("Inventory");
                rawSourceVersion=writer.getInt("DataVersion");
                bridgeBanners=bridgeBannerExpectations(source,rawSource,rawSourceVersion);
            }
            Map<String,Integer> expected=expectedInventory(source,bridgeBanners);Map<String,Integer> observed=inventory(actual);
            CompoundTag recovery=ledger.getCompound("Recoveries").getCompound(id);
            if(!recovery.contains("Storage"))verifySlots(source,actual,bridgeBanners);
            Map<String,Integer> recovered=storage(world,recovery);
            merge(observed,recovered);require(expected.equals(observed),"Inventory conservation mismatch "+uuid+" expected="+expected+" actual="+observed);
            verifyRaw(rawSource,actual,rawSourceVersion);
            if(recover) {
                if(!ledger.getCompound("Recoveries").contains(id,10)) {ledger.getCompound("Recoveries").put(id,new CompoundTag());recovery=ledger.getCompound("Recoveries").getCompound(id);}
                recover(world,body,recovery);
                require(inventory(save(body)).isEmpty(),"Supported inventory remained after GUI recovery");
                require(expected.equals(storage(world,recovery)),"GUI recovery lost or duplicated complete item data");
                verifyRaw(rawSource,save(body),rawSourceVersion);
            }
            verifyChest(world,record.getInt("Wave"));
            verifyBiomes(world,record.getInt("Wave"));
        }
        verifyPlayer(server);
        if(wave==0 || wave<0) verifyVanilla(server);
        if(ledger.contains("RemovedRawBackupSha256",7)) {
            var backup=new File(folder,"upgrade-removed-original.nbt").toPath();
            require(Arrays.equals(ledger.getByteArray("RemovedRawBackupSha256"),backupDigest(backup)),
                    "Removed raw-only fixture backup changed before reopen");
        }
        if(phase.equals("upgrade-final") && ledger.getString("Removed").isEmpty()) {
            EntityLootableBody rawOnly=null;
            for(int i=0;i<bodies.size();i++) {
                CompoundTag record=bodies.getCompound(i);ServerLevel world=server.getLevel(FixtureApi.dimensionKey(record.getInt("Dimension")));EntityLootableBody body=find(world,HistoricalUuid.read(record.getCompound("Body"),"UUID"));
                if(body==null)continue;
                if(body.isEmpty()){removeFixture(body);break;}
                CompoundTag saved=save(body);
                if(rawOnly==null && saved.getList("Equipment",10).isEmpty() && saved.getList("Aux",10).isEmpty()
                        && !saved.getList("LootableBodiesUnresolved",10).isEmpty())rawOnly=body;
            }
            if(ledger.getString("Removed").isEmpty() && rawOnly!=null) {
                // Some genuine old worlds leave unsupported capability data
                // on every body. Keep the complete raw-only entity before
                // deliberately discarding one in this disposable test world.
                CompoundTag original=new CompoundTag();
                require(rawOnly.saveAsPassenger(original),"Could not back up raw-only fixture");
                var backup=new File(folder,"upgrade-removed-original.nbt").toPath();
                try(OutputStream stream=java.nio.file.Files.newOutputStream(backup,java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    NbtIo.writeCompressed(original,stream);
                }
                require(TypedNbt.same(original,read(backup.toFile())),"Incomplete raw-only fixture backup");
                ledger.putByteArray("RemovedRawBackupSha256",backupDigest(backup));
                removeFixture(rawOnly);
            }
            require(!ledger.getString("Removed").isEmpty(),"No eligible corpse for non-resurrection check");
        }
        // This harness stops before the game loop. Advance the clock as a real
        // played session would, so vanilla actually writes its loaded chunks.
        for(int dimension:new int[]{0,-1,1}) {ServerLevel world=server.getLevel(FixtureApi.dimensionKey(dimension));((net.minecraft.world.level.storage.ServerLevelData)world.getLevelData()).setGameTime(world.getGameTime()+1);}
        server.saveAllChunks(false,true,false);FixtureApi.flush(server);
        try(FileOutputStream output=new FileOutputStream(stored)){NbtIo.writeCompressed(ledger,output);}
        try(PrintWriter out=new PrintWriter(new File(folder,phase+".pass"),"UTF-8")){out.println("checks="+checks+" historical="+manifest.getString("Minecraft")+"/"+manifest.getString("ModVersion"));}
        System.out.println("LOOTABLE_BODIES_WORLD_UPGRADE_PASS phase="+phase+" checks="+checks);
    }
    private static byte[] backupDigest(java.nio.file.Path backup) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(backup));
    }
    private static void removeFixture(EntityLootableBody body) {
        ledger.putString("Removed",body.getUUID().toString());
        ServerLevel world=(ServerLevel)body.level();
        BlockPos position=body.blockPosition();
        body.discard();
        world.getChunkAt(position).setUnsaved(true);
    }
    private static void require(boolean ok,String message){checks++;RuntimeChecks.require(ok,message);}
    private static CompoundTag read(File file) throws IOException {try(FileInputStream input=new FileInputStream(file)){return NbtIo.readCompressed(input,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));}}
    private static CompoundTag save(EntityLootableBody body){CompoundTag nbt=new CompoundTag();body.addAdditionalSaveData(nbt);return nbt;}
    private static EntityLootableBody find(ServerLevel world,UUID id){for(Entity entity:FixtureApi.entities(world))if(entity instanceof EntityLootableBody && entity.getUUID().equals(id))return (EntityLootableBody)entity;return null;}
    private static boolean known(CompoundTag item){
        return item.contains("id",8) && item.getInt("count")>0 && !FixtureStackData.load(item).isEmpty();
    }
    private static CompoundTag expectedCorpseItem(CompoundTag source) {
        return expectedCorpseItem(source,sourceVersion);
    }
    private static CompoundTag expectedCorpseItem(CompoundTag source,int version) {
        // Our corpses retain the whole original when the native codec cannot
        // carry its old capability payload. Vanilla containers use their
        // own codec and are verified against that codec separately.
        return source.contains("ForgeCaps")?source.copy():expectedItem(source,version);
    }
    static CompoundTag expectedItem(CompoundTag source,int version) {
        // Independent vanilla oracle. Production additionally checks conservation,
        // resolves registries and shields owned entity payloads; this path does not.
        if(!source.contains("id",8) || !source.contains("Count"))return source.copy();
        com.mojang.datafixers.DataFixer vanilla=NativeVanillaDataFixer.get();
        CompoundTag input=source.copy();
        if(version<704 && (source.getString("id").equals("minecraft:spawn_egg") || source.getString("id").equals("minecraft:chest") || source.getString("id").equals("minecraft:banner"))) {
            // These three sealed writer fixtures exercise the broken vanilla
            // namespace boundary. Their expected IDs are explicit, independent
            // of the production bridge and registry lookup.
            input=cyano.lootable.upgrade.NativeNbt.update(vanilla,net.minecraft.util.datafix.fixes.References.ITEM_STACK,input,512,703);
            CompoundTag tag=input.getCompound("tag");
            if(source.getString("id").equals("minecraft:spawn_egg")) {
                require(tag.getCompound("EntityTag").getString("id").equals("Pig"),"Unexpected historical egg fixture");
                tag.getCompound("EntityTag").putString("id","minecraft:pig");
            } else tag.getCompound("BlockEntityTag").putString("id",source.getString("id"));
            version=705;
        }
        CompoundTag result=cyano.lootable.upgrade.NativeNbt.update(vanilla,net.minecraft.util.datafix.fixes.References.ITEM_STACK,input,version,cyano.lootable.upgrade.UpgradePreservation.MINECRAFT_VERSION);
        if(source.getInt("Count")<=0)return source.copy();
        require(result.contains("count",3) && source.getInt("Count")==result.getInt("count"),"Vanilla oracle changed an item count");
        require(Objects.equals(source.get("Slot"),result.get("Slot")),"Vanilla oracle changed an item slot");
        ItemStack nativeStack=FixtureStackData.load(result);
        if(nativeStack.isEmpty())return result;
        require(nativeStack.getCount()==result.getInt("count"),"Native oracle codec changed an item count");
        CompoundTag saved=FixtureStackData.save(nativeStack);
        if(result.contains("Slot"))saved.put("Slot",result.get("Slot").copy());
        return saved;
    }
    private static Map<Integer,CompoundTag> bridgeBannerExpectations(CompoundTag seed,CompoundTag writer,int writerVersion) {
        Map<Integer,CompoundTag> expected=new HashMap<>();
        ListTag original=seed.getList("Equipment",10),written=writer.getList("Equipment",10);
        for(int i=0;i<original.size();i++) {
            CompoundTag seedItem=original.getCompound(i);
            String originalId=seedItem.getString("id");
            if(!originalId.equals("minecraft:banner") && !originalId.endsWith("_banner"))continue;
            CompoundTag banner=expectedCorpseItem(seedItem);
            if(!known(banner) || !banner.getString("id").endsWith("_banner"))continue;
            int slot=seedItem.getByte("Slot");
            CompoundTag writtenBanner=null;
            for(int w=0;w<written.size();w++) {
                CompoundTag item=written.getCompound(w);
                if(item.contains("Slot",1) && item.getByte("Slot")==slot) {
                    require(writtenBanner==null,"Duplicate sealed bridge banner slot "+slot);
                    writtenBanner=expectedCorpseItem(item,writerVersion);
                }
            }
            require(writtenBanner!=null && known(writtenBanner)
                    && NameLifetimeChecks.same(semanticBannerColour(banner),semanticBannerColour(writtenBanner)),
                    "Sealed bridge banner changed original colour, slot, count or payload");
            expected.put(slot,writtenBanner);
        }
        return expected;
    }
    private static CompoundTag semanticBannerColour(CompoundTag source) {
        CompoundTag item=canonicalItem(source),components=item.getCompound("components");
        String id=item.getString("id");
        if(id.startsWith("minecraft:") && id.endsWith("_banner")
                && components.getString("minecraft:base_color").equals(id.substring(10,id.length()-7))) {
            // A historical writer can move the same base colour into Damage.
            // Require the sealed writer's exact resulting components at runtime.
            components.remove("minecraft:base_color");
            if(components.isEmpty())item.remove("components");
        }
        return item;
    }
    private static void verifySlots(CompoundTag source,CompoundTag actual,Map<Integer,CompoundTag> bridgeBanners) {
        Map<Integer,CompoundTag> slots=new TreeMap<>();
        ListTag entries=source.getList("Equipment",10);
        for(int i=0;i<entries.size();i++) {
            CompoundTag raw=entries.getCompound(i);
            int slot=raw.getByte("Slot");
            CompoundTag item=bridgeBanners.containsKey(slot)?bridgeBanners.get(slot).copy():expectedCorpseItem(raw);
            if(!known(item) || !raw.contains("Slot",1))continue;
            if(slot<0 || slot>=48 || slots.containsKey(slot))continue;
            item.remove("Slot");slots.put(slot,item);
        }
        Map<Integer,CompoundTag> saved=new TreeMap<>();ListTag entriesNow=actual.getList("Equipment",10);
        for(int i=0;i<entriesNow.size();i++){CompoundTag item=entriesNow.getCompound(i).copy();int slot=item.getByte("Slot");item.remove("Slot");saved.put(slot,item);}
        for(Map.Entry<Integer,CompoundTag> entry:slots.entrySet())
            require(Objects.equals(TypedNbt.canonical(canonicalItem(entry.getValue())),TypedNbt.canonical(canonicalItem(saved.get(entry.getKey())))),"Historical inventory slot moved: "+entry.getKey()+" expected="+entry.getValue()+" actual="+saved.get(entry.getKey()));
    }
    private static CompoundTag canonicalItem(CompoundTag source) {
        if(source==null)return null;
        CompoundTag item=source.copy();String id=item.getString("id");
        String[] colours={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
        for(int colour=0;colour<colours.length;colour++)if(id.equals("minecraft:"+colours[colour]+"_banner")) {
            CompoundTag components=item.getCompound("components"),block=components.getCompound("minecraft:block_entity_data");
            // Compare the exact colour in the item ID once. Pattern and any
            // remaining typed block-entity data still contribute to the seal.
            if(block.contains("Base",3) && block.getInt("Base")==colour)block.remove("Base");
            if(block.getAllKeys().equals(Set.of("id")) && block.getString("id").equals("minecraft:banner"))
                components.remove("minecraft:block_entity_data");
            if(components.isEmpty())item.remove("components");
        }
        return item;
    }
    private static void add(Map<String,Integer> map,CompoundTag source,boolean indexed) {
        if(!source.contains("id",8) || !source.contains("count",3) || source.getInt("count")<=0)return;
        CompoundTag item=canonicalItem(source);int count=item.getInt("count");item.remove("count");if(!indexed)item.remove("Slot");
        String key=TypedNbt.canonical(item);map.put(key,map.getOrDefault(key,0)+count);
    }
    private static void merge(Map<String,Integer> a,Map<String,Integer> b){for(Map.Entry<String,Integer> item:b.entrySet())a.put(item.getKey(),a.getOrDefault(item.getKey(),0)+item.getValue());}
    private static Map<String,Integer> inventory(CompoundTag body){Map<String,Integer> map=new TreeMap<>();for(String key:new String[]{"Equipment","Aux"}){ListTag items=body.getList(key,10);for(int i=0;i<items.size();i++)add(map,items.getCompound(i),false);}return map;}
    private static Map<String,Integer> expectedInventory(CompoundTag source,Map<Integer,CompoundTag> bridgeBanners) {
        Map<String,Integer> expected=new TreeMap<>();
        for(String key:new String[]{"Equipment","Aux"}) {
            ListTag list=source.getList(key,10);
            for(int i=0;i<list.size();i++) {
                CompoundTag raw=list.getCompound(i),item="Equipment".equals(key)?bridgeBanners.get((int)raw.getByte("Slot")):null;
                if(item==null)item=expectedCorpseItem(raw);
                if(known(item))add(expected,item,false);
            }
        }
        ListTag records=source.getList("LootableBodiesUnresolved",10);for(int i=0;i<records.size();i++){CompoundTag item=expectedCorpseItem(records.getCompound(i).getCompound("Data"));if(known(item))add(expected,item,false);}return expected;
    }
    private static void verifyRaw(CompoundTag source,CompoundTag saved,int writerVersion) {
        List<CompoundTag> expected=new ArrayList<>();
        for(String key:new String[]{"Equipment","Aux"}) {ListTag list=source.getList(key,10);for(int i=0;i<list.size();i++)if(!known(expectedCorpseItem(list.getCompound(i),writerVersion))){CompoundTag record=new CompoundTag();record.putString("Source",key);record.put("Data",list.getCompound(i).copy());expected.add(record);}}
        ListTag records=source.getList("LootableBodiesUnresolved",10);
        for(int i=0;i<records.size();i++){
            CompoundTag record=records.getCompound(i);
            int version=record.contains("SourceVersions",10)?record.getCompound("SourceVersions").getInt("DataVersion"):writerVersion;
            if(!record.contains("Data",10) || !known(expectedCorpseItem(record.getCompound("Data"),version)))expected.add(record);
        }
        ListTag actual=saved.getList("LootableBodiesUnresolved",10);require(expected.size()==actual.size(),"Raw recovery count changed");
        for(int i=0;i<expected.size();i++) {
            CompoundTag e=expected.get(i),a=actual.getCompound(i);
            require(e.getString("Source").equals(a.getString("Source")) && NameLifetimeChecks.same(e.get("Data"),a.get("Data")),
                    "Original recovery Source/Data changed");
            if(e.contains("SourceVersions",10))
                require(NameLifetimeChecks.same(e.get("SourceVersions"),a.get("SourceVersions")),"Recovery lost its original context");
            else require(a.getCompound("SourceVersions").getInt("DataVersion")==writerVersion,"Recovery lost its writer version context");
        }
    }
    private static Map<String,Integer> contents(Container inventory,boolean indexed) {
        Map<String,Integer> map=new TreeMap<>();
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            ItemStack stack=inventory.getItem(slot);
            if(stack.isEmpty())continue;
            CompoundTag item=FixtureStackData.save(stack);
            item.putByte("Slot",(byte)slot);
            if(indexed)addVanillaSeed(map,item);
            else add(map,item,false);
        }
        return map;
    }
    private static void addVanillaSeed(Map<String,Integer> map,CompoundTag item) {
        // Seed manifests describe the original item before a genuine old
        // vanilla writer could move the same banner colour into the item ID.
        add(map,semanticBannerColour(item),true);
    }
    private static Map<String,Integer> indexed(ListTag list) {
        Map<String,Integer> map=new TreeMap<>();
        for(int i=0;i<list.size();i++)addVanillaSeed(map,expectedItem(list.getCompound(i),sourceVersion));
        return map;
    }
    private static ListTag storageList(CompoundTag recovery){if(!recovery.contains("Storage",9))recovery.put("Storage",new ListTag());return recovery.getList("Storage",10);}
    private static BlockPos position(CompoundTag nbt){return new BlockPos(nbt.getInt("x"),nbt.getInt("y"),nbt.getInt("z"));}
    private static Map<String,Integer> storage(ServerLevel world,CompoundTag recovery) {Map<String,Integer> map=new TreeMap<>();ListTag list=storageList(recovery);for(int i=0;i<list.size();i++){BlockPos position=position(list.getCompound(i));world.getChunk(position);BlockEntity tile=world.getBlockEntity(position);require(tile instanceof Container,"Recovery chest disappeared");merge(map,contents((Container)tile,false));}return map;}
    private static void recover(ServerLevel world,EntityLootableBody body,CompoundTag recovery) {
        // Original fixtures deliberately retain their source terrain. Clear only
        // this disposable interaction space before ticking the body: Nether
        // netherrack otherwise invokes the existing wall-escape gameplay.
        BlockPos center=body.blockPosition();
        for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)world.removeBlock(center.offset(x,y,z),false);
        ServerPlayer visitor=new ServerPlayer(world.getServer(),world,new GameProfile(UUID.fromString("00000000-2222-4222-8222-000000000099"),"RecoveryVisitor"),net.minecraft.server.level.ClientInformation.createDefault());visitor.setHealth(20);visitor.getInventory().clearContent();visitor.setPos(body.getX(),body.getY(),body.getZ());
        CorpseContainer container=new CorpseContainer(visitor.getInventory(),body);
        for(int attempt=0;attempt<200 && !inventory(save(body)).isEmpty();attempt++) {
            for(int slot=0;slot<48;slot++)container.quickMoveStack(visitor,slot);
            flush(world,body,visitor.getInventory(),recovery);body.baseTick();
        }
        flush(world,body,visitor.getInventory(),recovery);
        require(contents(visitor.getInventory(),false).isEmpty(),"Recovery visitor retained unsaved items");
    }
    private static void flush(ServerLevel world,EntityLootableBody body,Container player,CompoundTag recovery) {
        ListTag stored=storageList(recovery);
        for(int slot=0;slot<player.getContainerSize();slot++) {
            ItemStack stack=player.getItem(slot);if(stack.isEmpty())continue;Container destination=null;int empty=-1;
            if(stored.size()>0) {destination=(Container)world.getBlockEntity(position(stored.getCompound(stored.size()-1)));for(int i=0;i<destination.getContainerSize();i++)if(destination.getItem(i).isEmpty()){empty=i;break;}}
            if(empty<0){BlockPos pos=BlockPos.containing(body.getX()+10,80+stored.size(),body.getZ()+10);world.setBlockAndUpdate(pos,net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());destination=(Container)world.getBlockEntity(pos);CompoundTag place=new CompoundTag();place.putInt("x",pos.getX());place.putInt("y",pos.getY());place.putInt("z",pos.getZ());stored.add(place);empty=0;}
            destination.setItem(empty,stack.copy());destination.setChanged();player.setItem(slot,ItemStack.EMPTY);
        }
    }
    private static void verifyChest(ServerLevel world,int wave) {
        ListTag containers=manifest.getList("Containers",10);
        for(int i=0;i<containers.size();i++){CompoundTag record=containers.getCompound(i);if(record.getInt("Dimension")!=FixtureApi.dimensionId(world) || record.getInt("Wave")!=wave)continue;CompoundTag source=record.getCompound("Tile");BlockEntity tile=world.getBlockEntity(position(source));require(tile instanceof Container,"Vanilla source chest disappeared");Map<String,Integer> expected=indexed(source.getList("Items",10)),actual=contents((Container)tile,true);if(!expected.equals(actual)){Map<String,Integer> missing=new TreeMap<>(expected),unexpected=new TreeMap<>(actual);missing.entrySet().removeAll(actual.entrySet());unexpected.entrySet().removeAll(expected.entrySet());System.out.println("VANILLA_CHEST_DIFF missing="+missing+" unexpected="+unexpected);}require(expected.equals(actual),"Vanilla source chest slots/data changed");}
    }
    private static void verifyPlayer(MinecraftServer server) throws Exception {
        ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));UUID id=HistoricalUuid.read(manifest,"Player");
        CompoundTag expected=read(new File(folder,"upgrade-player-source.nbt"));
        ServerPlayer player=new ServerPlayer(server,world,new GameProfile(id,"UpgradeFixture"),net.minecraft.server.level.ClientInformation.createDefault());FixtureApi.playerData(server).load(player);
        // PlayerInventory serializes armour as 100..103 and offhand as 150.
        ListTag actual=player.getInventory().save(new ListTag());require(indexedPlayer(expected,"Inventory").equals(indexedCurrent(actual)),"Player inventory slots, counts or custom data changed");
        require(indexedPlayer(expected,"EnderItems").equals(indexedCurrent(player.getEnderChestInventory().createTag(world.registryAccess()))),"Ender chest slots/data changed");
        require(player.getUUID().equals(id),"Player UUID changed");
        if(manifest.contains("Recipe",8)) {
            require(HistoricalUnlocks.sugarPair(manifest.getString("Recipe"),manifest.getString("Advancement")),"Unexpected historical recipe/advancement fixture");
            net.minecraft.world.item.crafting.RecipeHolder<?> recipe=server.getRecipeManager().byKey(new ResourceLocation("minecraft:sugar_from_sugar_cane")).orElse(null);
            require(recipe!=null && player.getRecipeBook().contains(recipe),"Native sugar recipe conversion lost its unlock");
            net.minecraft.advancements.AdvancementHolder advancement=server.getAdvancements().get(new ResourceLocation("minecraft:recipes/misc/sugar_from_sugar_cane"));
            require(advancement!=null && player.getAdvancements().getOrStartProgress(advancement).isDone(),"Native sugar advancement conversion lost its completion");
            player.getAdvancements().save();
        }
        FixtureApi.playerData(server).save(player);
    }
    private static void verifyBiomes(ServerLevel world,int wave) {
        ListTag samples=manifest.getList("BiomeSamples",10);
        for(int i=0;i<samples.size();i++) {
            CompoundTag record=samples.getCompound(i);
            if(record.getInt("Dimension")!=FixtureApi.dimensionId(world) || record.getInt("Wave")!=wave)continue;
            Native118BiomeChecks.verify(world,record,wave);
        }
    }
    private static Map<String,Integer> indexedPlayer(CompoundTag player,String key) {
        int version=player.contains("DataVersion",3)?player.getInt("DataVersion"):(sourceVersion);
        Map<String,Integer> map=new TreeMap<>();
        for(Tag entry:player.getList(key,10))add(map,expectedItem((CompoundTag)entry,version),true);
        return map;
    }
    private static Map<String,Integer> indexedCurrent(ListTag list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++)add(map,list.getCompound(i),true);return map;}
    private static void verifyVanilla(MinecraftServer server) {
        ListTag list=manifest.getList("Vanilla",10);
        for(int i=0;i<list.size();i++) {CompoundTag record=list.getCompound(i),source=record.getCompound("Entity");ServerLevel world=server.getLevel(FixtureApi.dimensionKey(record.getInt("Dimension")));ListTag pos=source.getList("Pos",6);RuntimeChecks.awaitChunk(world,net.minecraft.util.Mth.floor(pos.getDouble(0))>>4,net.minecraft.util.Mth.floor(pos.getDouble(2))>>4);Entity found=null;for(Entity e:FixtureApi.entities(world))if(e.getUUID().equals(HistoricalUuid.read(source,"UUID")))found=e;require(found!=null,"Vanilla entity disappeared");CompoundTag actual=new CompoundTag();found.saveAsPassenger(actual);require(actual.getString("id").equals(record.getString("ExpectedType")),"Vanilla entity-name conversion failed");require(FixtureApi.dimensionId(found.level())==record.getInt("Dimension"),"Vanilla entity dimension changed");if(record.getString("ExpectedType").equals("minecraft:villager"))verifyVillager(source,actual);if(found instanceof Container)require(indexed(source.getList("Items",10)).equals(contents((Container)found,true)),"Vanilla minecart inventory changed");if(record.getString("ExpectedType").equals("minecraft:armor_stand"))require(indexedEquipment(source.getList("ArmorItems",10)).equals(indexedNativeEquipment(actual.getList("ArmorItems",10))),"Vanilla armour stand equipment changed");}
    }
    private static void verifyVillager(CompoundTag source,CompoundTag actual) {
        CompoundTag expected=cyano.lootable.upgrade.NativeNbt.update(NativeVanillaDataFixer.get(),net.minecraft.util.datafix.fixes.References.ENTITY,source.copy(),sourceVersion,cyano.lootable.upgrade.UpgradePreservation.MINECRAFT_VERSION);
        require(NameLifetimeChecks.same(expected.get("VillagerData"),actual.get("VillagerData")),"Vanilla villager profession, career or level changed");
        Map<String,Integer> observed=new TreeMap<>();
        for(Tag item:actual.getList("Inventory",10))addVanillaSeed(observed,(CompoundTag)item);
        require(indexed(source.getList("Inventory",10)).equals(observed),"Vanilla villager inventory changed");
    }
    private static Map<String,Integer> indexedNativeEquipment(ListTag list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){CompoundTag item=list.getCompound(i).copy();item.putByte("Slot",(byte)i);addVanillaSeed(map,item);}return map;}
    private static Map<String,Integer> indexedEquipment(ListTag list){Map<String,Integer> map=new TreeMap<>();for(int i=0;i<list.size();i++){CompoundTag item=expectedItem(list.getCompound(i),sourceVersion);item.putByte("Slot",(byte)i);addVanillaSeed(map,item);}return map;}
}
