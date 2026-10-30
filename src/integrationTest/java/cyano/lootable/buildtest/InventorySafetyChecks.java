package cyano.lootable.buildtest;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.SimpleContainer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.profiling.ActiveProfiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TickList;
import net.minecraft.world.level.chunk.ChunkSource;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.eventbus.api.*;

/** Conservation regressions executed in real Forge, never an ordinary runtime. */
public final class InventorySafetyChecks {
    private interface Check { void run() throws Exception; }
    private static final List<String> results = new ArrayList<>();
    private static int failures;
    private static int sequence;
    private static ServerLevel world;
    private static MinecraftServer server;

    public static void run(MinecraftServer instance, ServerLevel target, File folder) throws Exception {
        server=instance; world=target; results.clear(); failures=0;
        check("failed_corpse_spawn_keeps_original_drops",InventorySafetyChecks::failedSpawn);
        check("cancelled_corpse_spawn_leaves_vanilla_drops",InventorySafetyChecks::cancelledSpawn);
        check("stale_equipment_cannot_overwrite_current_helmet",InventorySafetyChecks::staleEquipment);
        check("equipment_cache_consumed_and_ignored_deaths",InventorySafetyChecks::cacheLifecycle);
        check("failed_release_retains_inventory_and_body",InventorySafetyChecks::failedRelease);
        check("partial_release_retries_without_duplicates",InventorySafetyChecks::partialRelease);
        check("vanilla_equipment_drop_does_not_duplicate",InventorySafetyChecks::vanillaEquipment);
        check("vacuum_claims_each_entity_once",InventorySafetyChecks::vacuumOnce);
        check("empty_and_negative_removal_preserve_slots",InventorySafetyChecks::emptyRemoval);
        check("repeated_nbt_read_replaces_without_mutating_source",InventorySafetyChecks::repeatedRead);
        check("container_rejects_stale_access",InventorySafetyChecks::staleAccess);
        check("overflow_never_overwrites_and_respects_limits",InventorySafetyChecks::overflow);
        check("two_viewers_shift_click_conserve_custom_stacks",InventorySafetyChecks::twoViewers);
        check("empty_slot_validation_is_safe",InventorySafetyChecks::emptyValidation);
        check("dirty_inventory_schedules_chunk_save",InventorySafetyChecks::dirtyInventory);
        check("direct_and_overflow_mutations_schedule_save",InventorySafetyChecks::dirtyMutations);
        try(PrintWriter out=new PrintWriter(new File(folder,"inventory-checks.txt"),"UTF-8")) {
            for(String line:results) out.println(line);
            out.println("checks="+results.size()+" failures="+failures);
        }
        RuntimeChecks.require(failures==0,"Inventory regressions: "+failures+" / "+results.size());
        System.out.println("LOOTABLE_BODIES_INVENTORY_PASS checks="+results.size());
    }
    private static void check(String name,Check check) {
        List<Entity> before=new ArrayList<>(FixtureApi.entities(world));
        String keep=world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).toString();
        String mobLoot=world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT).toString();
        try {
            world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
            world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT).set(false,null);
            check.run(); results.add("PASS "+name);
            System.out.println("INVENTORY_CHECK_PASS "+name);
        } catch(Throwable error) {
            failures++; results.add("FAIL "+name+": "+error);
            System.out.println("INVENTORY_CHECK_FAIL "+name+": "+error);
            error.printStackTrace();
        } finally {
            world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(Boolean.parseBoolean(keep),null);
            world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT).set(Boolean.parseBoolean(mobLoot),null);
            for(Entity entity:new ArrayList<Entity>(FixtureApi.entities(world)))
                if(!before.contains(entity)) world.removeEntity(entity);
        }
    }
    private static ServerPlayer player() {
        ServerPlayer player=new ServerPlayer(server,world,
                new GameProfile(UUID.nameUUIDFromBytes(("inventory-fixture-"+(sequence++)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"InventoryFixture"));
        player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),player);
        player.setPos(64.5,80,64.5); RuntimeChecks.awaitChunk(world,4,4);
        return player;
    }
    private static EntityLootableBody body() {
        EntityLootableBody body=new EntityLootableBody(world);
        body.setPos(64.5,80,64.5); body.setNoGravity(true);
        RuntimeChecks.awaitChunk(world,4,4); RuntimeChecks.disableVacuum(body);
        return body;
    }
    private static ItemStack item(int id,int count) {
        ItemStack stack=RuntimeChecks.stack(id); stack.setCount(count); return stack;
    }
    private static List<ItemEntity> drops(ServerPlayer player,ItemStack... stacks) {
        List<ItemEntity> result=new ArrayList<>();
        for(ItemStack stack:stacks) result.add(new ItemEntity(player.getCommandSenderWorld(),player.getX(),player.getY(),player.getZ(),stack));
        return result;
    }
    private static void handle(PlayerDeathEventHandler handler,ServerPlayer player,List<ItemEntity> drops) {
        handler.entityDropEvent(new LivingDropsEvent(player,DamageSource.OUT_OF_WORLD,drops,0,false));
    }
    private static CompoundTag data(EntityLootableBody body) {
        CompoundTag result=new CompoundTag(); body.addAdditionalSaveData(result); return result;
    }
    private static CompoundTag encoded(ItemStack stack) { return stack.save(new CompoundTag()); }
    private static Map<String,Integer> inventory(EntityLootableBody body) {
        Map<String,Integer> result=new TreeMap<>();
        for(int i=0;i<body.getContainerSize();i++) add(result,body.getItem(i));
        ListTag aux=data(body).getList("Aux",10);
        for(int i=0;i<aux.size();i++) add(result,ItemStack.of(aux.getCompound(i)));
        return result;
    }
    private static void add(Map<String,Integer> result,ItemStack stack) {
        if((stack == null || stack.isEmpty()) || stack.getCount()<=0) return;
        CompoundTag tag=encoded(stack); tag.remove("Count");
        String key=tag.toString(); result.put(key,result.getOrDefault(key,0)+stack.getCount());
    }
    private static Map<String,Integer> dropped() {
        Map<String,Integer> result=new TreeMap<>();
        for(Entity entity:FixtureApi.entities(world)) if(entity instanceof ItemEntity && !entity.isRemoved()) add(result,((ItemEntity)entity).getItem());
        return result;
    }
    private static EntityLootableBody newest(Set<Entity> before) {
        for(Entity entity:FixtureApi.entities(world)) if(entity instanceof EntityLootableBody && !before.contains(entity)) return (EntityLootableBody)entity;
        throw new AssertionError("No corpse spawned");
    }
    private static void failedSpawn() {
        ServerPlayer player=player(); RejectingWorld rejected=new RejectingWorld(world);
        player.level=rejected;
        ItemStack bones=new ItemStack(Items.BONE,9); bones.setHoverName(new net.minecraft.network.chat.TextComponent("Saved bones"));
        List<ItemEntity> drops=drops(player,bones,item(19,23));
        List<CompoundTag> before=new ArrayList<>(); for(ItemEntity e:drops) before.add(encoded(e.getItem()));
        PlayerDeathEventHandler handler=new PlayerDeathEventHandler();
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        handle(handler,player,drops);
        RuntimeChecks.require(rejected.attempts==1,"Failed spawn branch was not reached");
        RuntimeChecks.require(drops.size()==before.size(),"Rejected corpse consumed vanilla death drops");
        for(int i=0;i<before.size();i++) RuntimeChecks.require(before.get(i).equals(encoded(drops.get(i).getItem())),"Rejected spawn mutated drop NBT/count");
    }
    public static final class RejectCorpse {
        int attempts;
        @SubscribeEvent public void join(EntityJoinWorldEvent event) {
            if(event.getEntity() instanceof EntityLootableBody) { attempts++; event.setCanceled(true); }
        }
    }
    private static void cancelledSpawn() {
        ServerPlayer player=player(); player.getInventory().items.set(0,item(4,1)); player.getInventory().items.set(9,item(19,23));
        Map<String,Integer> expected=new TreeMap<>(); add(expected,player.getInventory().items.get(0));add(expected,player.getInventory().items.get(9));
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        RejectCorpse reject=new RejectCorpse(); MinecraftForge.EVENT_BUS.register(reject);
        try { player.die(DamageSource.OUT_OF_WORLD); } finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        RuntimeChecks.require(reject.attempts==1,"Corpse cancellation not exercised");
        for(Entity entity:FixtureApi.entities(world)) RuntimeChecks.require(before.contains(entity) || !(entity instanceof EntityLootableBody),"Cancelled corpse spawn bypassed Forge cancellation");
        RuntimeChecks.require(expected.equals(dropped()),"Cancelled corpse did not fall back to exact vanilla drops");
    }
    private static void staleEquipment() {
        ServerPlayer player=player(); PlayerDeathEventHandler handler=new PlayerDeathEventHandler();
        ItemStack previous=item(0,1); player.setItemSlot(EquipmentSlot.HEAD,previous);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        handle(handler,player,drops(player,previous));
        ItemStack current=item(1,1); player.setItemSlot(EquipmentSlot.HEAD,current);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        Map<String,Integer> expected=new TreeMap<>(); add(expected,current);add(expected,previous);
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        handle(handler,player,drops(player,current,previous));
        EntityLootableBody corpse=newest(before);
        Map<String,Integer> actual=inventory(corpse);
        // Ignore only the explicitly configured extra bones/flesh.
        actual.entrySet().removeIf(e->e.getKey().contains("minecraft:bone") || e.getKey().contains("minecraft:rotten_flesh"));
        RuntimeChecks.require(expected.equals(actual),"Stale equipment overwrote a current stack");
    }
    @SuppressWarnings("unchecked") private static Map<Object,Object> cache(PlayerDeathEventHandler handler) throws Exception {
        Field field=PlayerDeathEventHandler.class.getDeclaredField("equipmentCache"); field.setAccessible(true);
        return (Map<Object,Object>)field.get(handler);
    }
    private static void cacheLifecycle() throws Exception {
        ServerPlayer player=player(); PlayerDeathEventHandler handler=new PlayerDeathEventHandler();
        player.setItemSlot(EquipmentSlot.HEAD,item(0,1));
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        handle(handler,player,drops(player,player.getItemBySlot(EquipmentSlot.HEAD)));
        RuntimeChecks.require(!cache(handler).containsKey(player),"Equipment cache retains completed death");
        world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(true,null);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        RuntimeChecks.require(!cache(handler).containsKey(player),"keepInventory death cached equipment");
        world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
        LivingDeathEvent canceled=new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD); canceled.setCanceled(true);
        handler.entityDeathEvent(canceled);
        RuntimeChecks.require(!cache(handler).containsKey(player),"Cancelled death cached equipment");
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        List<ItemEntity> original=drops(player,item(19,23));
        LivingDropsEvent canceledDrops=new LivingDropsEvent(player,DamageSource.OUT_OF_WORLD,original,0,false);
        canceledDrops.setCanceled(true);handler.entityDropEvent(canceledDrops);
        RuntimeChecks.require(!cache(handler).containsKey(player) && original.size()==1
                && RuntimeChecks.sameStack(original.get(0).getItem(),item(19,23)),"Cancelled drops retained stale cache or changed inventory");
    }
    public static final class RejectItems {
        boolean rejectAll;
        String rejectFixture;
        @SubscribeEvent public void join(EntityJoinWorldEvent event) {
            if(event.getEntity() instanceof ItemEntity) {
                ItemStack stack=((ItemEntity)event.getEntity()).getItem();
                if(rejectAll || stack.hasTag() && rejectFixture.equals(stack.getTag().getString("fixture"))) event.setCanceled(true);
            }
        }
    }
    private static void failedRelease() throws Exception {
        EntityLootableBody corpse=body(); corpse.setItem(6,item(19,23));
        RuntimeChecks.require(world.addFreshEntity(corpse),"Rejected-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems(); reject.rejectAll=true; MinecraftForge.EVENT_BUS.register(reject);
        try { corpse.kill(); for(int i=0;i<25;i++) corpse.baseTick(); }
        finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        RuntimeChecks.require(expected.equals(inventory(corpse)),"Rejected item spawn erased corpse inventory");
        RuntimeChecks.require(corpse.isAlive() && FixtureApi.entities(world).contains(corpse),"Rejected release removed the only inventory owner");
        CompoundTag saved=data(corpse); EntityLootableBody loaded=body();loaded.readAdditionalSaveData(saved);
        RuntimeChecks.require(expected.equals(inventory(loaded)),"Rejected items do not survive NBT reload");
        corpse.kill();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Retry did not drop exact saved stacks once");
    }
    private static void partialRelease() {
        EntityLootableBody corpse=body();corpse.setItem(6,item(19,23));corpse.setItem(7,item(20,11));
        RuntimeChecks.require(world.addFreshEntity(corpse),"Partial-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems();reject.rejectFixture="slot-20";MinecraftForge.EVENT_BUS.register(reject);
        try { corpse.kill();for(int i=0;i<12;i++)corpse.baseTick(); }
        finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        Map<String,Integer> combined=new TreeMap<>(dropped());inventory(corpse).forEach((k,v)->combined.put(k,combined.getOrDefault(k,0)+v));
        RuntimeChecks.require(expected.equals(combined),"Partial release lost/duplicated a stack");
        RuntimeChecks.require((corpse.getItem(6)).isEmpty() && RuntimeChecks.sameStack(corpse.getItem(7),item(20,11)),"Partial release did not retain only rejected slot");
        corpse.kill();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Partial retry duplicated previously accepted drops");
    }
    private static void vanillaEquipment() {
        world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBLOOT).set(true,null);
        EntityLootableBody corpse=body();corpse.setItem(0,item(0,1));corpse.setDropChance(EquipmentSlot.HEAD,2F);
        RuntimeChecks.require(world.addFreshEntity(corpse),"Equipment-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        corpse.kill();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Vanilla mob equipment path duplicates corpse inventory");
    }
    private static void vacuumOnce() {
        EntityLootableBody first=body(),second=body();
        try { Field f=EntityLootableBody.class.getDeclaredField("vacuumTime");f.setAccessible(true);f.setByte(first,(byte)0);f.setByte(second,(byte)0); }
        catch(Exception error){throw new AssertionError(error);}
        ItemEntity entity=new ItemEntity(world,64.5,80,64.5,item(19,23));RuntimeChecks.require(world.addFreshEntity(entity),"Vacuum input spawn");
        Map<String,Integer> expected=new TreeMap<>();add(expected,entity.getItem().copy());
        first.baseTick();second.baseTick();
        Map<String,Integer> all=inventory(first);inventory(second).forEach((k,v)->all.put(k,all.getOrDefault(k,0)+v));
        RuntimeChecks.require(expected.equals(all),"Two corpses claimed the same dying item entity");
    }
    private static void emptyRemoval() {
        EntityLootableBody corpse=body();
        RuntimeChecks.require((corpse.removeItem(6,1)).isEmpty(),"Empty removal must return EMPTY");
        corpse.setItem(6,item(19,23)); CompoundTag expected=encoded(corpse.getItem(6));
        RuntimeChecks.require((corpse.removeItem(6,-1)).isEmpty(),"Negative removal created a stack");
        RuntimeChecks.require(expected.equals(encoded(corpse.getItem(6))),"Negative removal changed stack count");
        RuntimeChecks.require((corpse.removeItem(6,0)).isEmpty(),"Zero removal created a stack");
        ItemStack removed=corpse.removeItem(6,40);RuntimeChecks.require(RuntimeChecks.sameStack(removed,item(19,23)) && (corpse.getItem(6)).isEmpty(),"Full extraction did not clear slot");
    }
    private static void repeatedRead() {
        EntityLootableBody source=body();for(int i=6;i<48;i++)source.setItem(i,item(i,1));source.addItem(item(70,7));
        CompoundTag input=data(source),immutable=input.copy();
        EntityLootableBody target=body(); target.readAdditionalSaveData(input); target.readAdditionalSaveData(input);
        RuntimeChecks.require(data(target).get("Equipment").equals(input.get("Equipment")) && data(target).get("Aux").equals(input.get("Aux")),"Repeated read appended overflow or changed slots");
        CompoundTag sparse=new CompoundTag();ListTag list=new ListTag();CompoundTag slot=encoded(item(19,23));slot.putByte("Slot",(byte)47);list.add(slot);sparse.put("Equipment",list);
        target.readAdditionalSaveData(sparse);
        RuntimeChecks.require((target.getItem(6)).isEmpty() && RuntimeChecks.sameStack(target.getItem(47),item(19,23)) && !data(target).contains("Aux"),"Sparse reread kept stale inventory/overflow");
        RuntimeChecks.require(immutable.equals(input),"Reader mutated source NBT");
    }
    private static void staleAccess() {
        ServerPlayer player=player();EntityLootableBody corpse=body();CorpseContainer container=new CorpseContainer(player.getInventory(),corpse);
        RuntimeChecks.require(container.stillValid(player),"Near living access failed");
        player.setPos(100,80,100);RuntimeChecks.require(!container.stillValid(player),"Distant container remained accessible");
        player.setPos(64.5,80,64.5);corpse.discard();RuntimeChecks.require(!container.stillValid(player),"Removed corpse remained accessible");
        corpse=body();container=new CorpseContainer(player.getInventory(),corpse);player.level=new RejectingWorld(world);
        RuntimeChecks.require(!container.stillValid(player),"Cross-world container remained accessible");
        player.level=world;player.setHealth(0);
        RuntimeChecks.require(!container.stillValid(player),"Dead player retained container access");
        player.setHealth(player.getMaxHealth());corpse.kill();corpse.baseTick();
        RuntimeChecks.require(!container.stillValid(player),"Dying corpse remained accessible");
    }
    private static void overflow() {
        EntityLootableBody corpse=body();for(int i=6;i<48;i++)corpse.setItem(i,item(i,1));corpse.addItem(item(70,7));
        CompoundTag before=data(corpse);corpse.baseTick();
        RuntimeChecks.require(before.get("Equipment").equals(data(corpse).get("Equipment")),"Full inventory overwritten by overflow");
        corpse.removeItemNoUpdate(47);corpse.baseTick();RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getItem(47),item(70,7)),"Overflow did not fill free slot");
        EntityLootableBody large=body();large.addItem(item(19,100));
        Map<String,Integer> expected=new TreeMap<>();add(expected,item(19,100));
        RuntimeChecks.require(expected.equals(inventory(large)),"Oversized incoming stack lost items");
        for(int i=6;i<48;i++) RuntimeChecks.require((large.getItem(i)).isEmpty() || large.getItem(i).getCount()<=64,"Oversized input bypassed slot capacity");
        EntityLootableBody queued=body();for(int i=6;i<48;i++)queued.setItem(i,item(i,1));
        queued.addItem(item(70,100));queued.removeItemNoUpdate(47);expected=inventory(queued);
        queued.baseTick();
        RuntimeChecks.require(expected.equals(inventory(queued)) && queued.getItem(47).getCount()==64,"Overflow transfer lost items or exceeded slot capacity");
        queued.removeItemNoUpdate(46);queued.baseTick();
        RuntimeChecks.require(RuntimeChecks.sameStack(queued.getItem(46),item(70,36)) && !data(queued).contains("Aux"),"Overflow remainder changed count or custom NBT");
    }
    private static void twoViewers() {
        EntityLootableBody corpse=body();corpse.setItem(6,item(19,23));ServerPlayer a=player(),b=player();
        CorpseContainer ca=new CorpseContainer(a.getInventory(),corpse),cb=new CorpseContainer(b.getInventory(),corpse);
        RuntimeChecks.require(RuntimeChecks.sameStack(ca.quickMoveStack(a,6),item(19,23)),"First recovery failed");
        RuntimeChecks.require((cb.quickMoveStack(b,6)).isEmpty(),"Second viewer recovered the same stack");
        Map<String,Integer> all=inventory(corpse);for(ItemStack s:a.getInventory().items)add(all,s);for(ItemStack s:b.getInventory().items)add(all,s);
        Map<String,Integer> expected=new TreeMap<>();add(expected,item(19,23));RuntimeChecks.require(expected.equals(all),"Two viewer recovery changed inventory/NBT");
    }
    private static void emptyValidation() {
        EntityLootableBody corpse=body();ServerPlayer player=player();CorpseContainer container=new CorpseContainer(player.getInventory(),corpse);
        RuntimeChecks.require(!corpse.canPlaceItem(0,null),"Empty armor input accepted");
        RuntimeChecks.require(!container.getSlot(0).mayPlace(null),"Container empty armor input accepted");
    }
    private static net.minecraft.world.level.chunk.LevelChunk cleanChunk(EntityLootableBody corpse) {
        net.minecraft.world.level.chunk.LevelChunk chunk=world.getChunkAt(corpse.blockPosition());
        chunk.setUnsaved(false);
        RuntimeChecks.require(!chunk.isUnsaved(),"Dirty-chunk test precondition");
        return chunk;
    }
    private static void dirtyInventory() {
        EntityLootableBody corpse=body();corpse.setItem(6,item(19,23));
        RuntimeChecks.require(world.addFreshEntity(corpse),"Dirty-chunk corpse spawn");
        net.minecraft.world.level.chunk.LevelChunk chunk=cleanChunk(corpse);corpse.setChanged();
        RuntimeChecks.require(chunk.isUnsaved(),"Inventory markDirty does not schedule a chunk save");
    }
    private static void dirtyMutations() {
        EntityLootableBody corpse=body();RuntimeChecks.require(world.addFreshEntity(corpse),"Mutation corpse spawn");
        net.minecraft.world.level.chunk.LevelChunk chunk=cleanChunk(corpse);corpse.setItem(6,item(19,23));
        RuntimeChecks.require(chunk.isUnsaved(),"Slot insertion not scheduled for save");
        cleanChunk(corpse);corpse.removeItem(6,1);RuntimeChecks.require(chunk.isUnsaved(),"Extraction not scheduled for save");
        cleanChunk(corpse);corpse.removeItemNoUpdate(6);RuntimeChecks.require(chunk.isUnsaved(),"Removal not scheduled for save");
        cleanChunk(corpse);corpse.initializeItems(new ItemStack[]{item(20,11)});RuntimeChecks.require(chunk.isUnsaved(),"Initial inventory not scheduled for save");
        for(int i=6;i<48;i++)corpse.setItem(i,item(i,1));
        cleanChunk(corpse);corpse.addItem(item(70,7));RuntimeChecks.require(chunk.isUnsaved(),"Overflow insertion not scheduled for save");
    }
    static void seedBlockedRelease(ServerLevel target,File folder) throws Exception {
        EntityLootableBody corpse=new EntityLootableBody(target);
        corpse.setUUID(UUID.fromString("00000000-0000-4000-8000-000000000105"));
        corpse.setPos(48.5,80,8.5);corpse.setNoGravity(true);corpse.setUserName("BlockedRelease");
        RuntimeChecks.awaitChunk(target,3,0);
        for(int i=0;i<48;i++)corpse.setItem(i,RuntimeChecks.stack(i));
        corpse.addItem(RuntimeChecks.stack(70));corpse.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(corpse);
        RuntimeChecks.require(target.addFreshEntity(corpse),"Blocked-release fixture spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems();reject.rejectAll=true;MinecraftForge.EVENT_BUS.register(reject);
        try {corpse.kill();for(int i=0;i<25;i++)corpse.baseTick();}
        finally {MinecraftForge.EVENT_BUS.unregister(reject);}
        RuntimeChecks.require(corpse.isAlive() && FixtureApi.entities(target).contains(corpse) && expected.equals(inventory(corpse)),"Blocked-release fixture lost inventory/owner");
        RuntimeChecks.saveExpected(folder,"rejected-release",corpse);
    }
    /** Controlled false-return seam; no dimension registration, disk writes or chunks of its own. */
    private static final class RejectingWorld extends Level {
        private final ServerLevel delegate; int attempts;
        RejectingWorld(ServerLevel target) {
            super((net.minecraft.world.level.storage.WritableLevelData)target.getLevelData(),target.dimension(),target.dimensionType(),()->net.minecraft.util.profiling.InactiveProfiler.INSTANCE,false,false,target.getSeed());
            delegate=target;
        }
        @Override public net.minecraft.core.RegistryAccess registryAccess(){return delegate.registryAccess();}
        @Override public float getShade(net.minecraft.core.Direction direction,boolean shade){return delegate.getShade(direction,shade);}
        @Override public ChunkSource getChunkSource(){return delegate.getChunkSource();}
        @Override public net.minecraft.world.level.biome.Biome getUncachedNoiseBiome(int x,int y,int z){return delegate.getUncachedNoiseBiome(x,y,z);}

        @Override public net.minecraft.world.level.TickList<net.minecraft.world.level.block.Block> getBlockTicks(){return delegate.getBlockTicks();}
        @Override public net.minecraft.world.level.TickList<net.minecraft.world.level.material.Fluid> getLiquidTicks(){return delegate.getLiquidTicks();}
        @Override public net.minecraft.world.item.crafting.RecipeManager getRecipeManager(){return delegate.getRecipeManager();}
        @Override public net.minecraft.world.scores.Scoreboard getScoreboard(){return delegate.getScoreboard();}
        @Override public net.minecraft.tags.TagContainer getTagManager(){return delegate.getTagManager();}
        @Override public net.minecraft.server.MinecraftServer getServer(){return delegate.getServer();}
        @Override public long getGameTime(){return delegate==null?0:delegate.getGameTime();}

        @Override public void sendBlockUpdated(net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState oldState,net.minecraft.world.level.block.state.BlockState state,int flags){delegate.sendBlockUpdated(pos,oldState,state,flags);}
        @Override public void playSound(net.minecraft.world.entity.player.Player player,double x,double y,double z,net.minecraft.sounds.SoundEvent sound,net.minecraft.sounds.SoundSource category,float volume,float pitch){delegate.playSound(player,x,y,z,sound,category,volume,pitch);}
        @Override public void playSound(net.minecraft.world.entity.player.Player player,Entity entity,net.minecraft.sounds.SoundEvent sound,net.minecraft.sounds.SoundSource category,float volume,float pitch){delegate.playSound(player,entity,sound,category,volume,pitch);}
        @Override public Entity getEntity(int id){return delegate.getEntity(id);}
        @Override public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(String id){return delegate.getMapData(id);}
        @Override public void setMapData(String id,net.minecraft.world.level.saveddata.maps.MapItemSavedData data){delegate.setMapData(id,data);}
        @Override public int getFreeMapId(){return delegate.getFreeMapId();}
        @Override public void destroyBlockProgress(int id,net.minecraft.core.BlockPos pos,int progress){delegate.destroyBlockProgress(id,pos,progress);}
        @Override public void levelEvent(net.minecraft.world.entity.player.Player player,int type,net.minecraft.core.BlockPos pos,int data){delegate.levelEvent(player,type,pos,data);}
        @Override public void gameEvent(Entity source,net.minecraft.world.level.gameevent.GameEvent event,net.minecraft.core.BlockPos pos){delegate.gameEvent(source,event,pos);}
        @Override public java.util.List<? extends net.minecraft.world.entity.player.Player> players(){return delegate.players();}
        @Override public String gatherChunkSourceStats(){return delegate.gatherChunkSourceStats();}
        @Override public net.minecraft.world.level.entity.LevelEntityGetter<Entity> getEntities(){return delegate.getEntities();}
        @Override public boolean addFreshEntity(Entity entity){attempts++;return false;}
    }
}
