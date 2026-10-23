package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.WorldInfo;
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
    private static WorldServer world;
    private static MinecraftServer server;

    public static void run(MinecraftServer instance, WorldServer target, File folder) throws Exception {
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
        List<Entity> before=new ArrayList<>(world.loadedEntityList);
        String keep=world.getGameRules().get("keepInventory").getString();
        String mobLoot=world.getGameRules().get("doMobLoot").getString();
        try {
            world.getGameRules().setOrCreateGameRule("keepInventory","false",null);
            world.getGameRules().setOrCreateGameRule("doMobLoot","false",null);
            check.run(); results.add("PASS "+name);
            System.out.println("INVENTORY_CHECK_PASS "+name);
        } catch(Throwable error) {
            failures++; results.add("FAIL "+name+": "+error);
            System.out.println("INVENTORY_CHECK_FAIL "+name+": "+error);
            error.printStackTrace();
        } finally {
            world.getGameRules().setOrCreateGameRule("keepInventory",keep,null);
            world.getGameRules().setOrCreateGameRule("doMobLoot",mobLoot,null);
            for(Entity entity:new ArrayList<Entity>(world.loadedEntityList))
                if(!before.contains(entity)) world.removeEntityDangerously(entity);
        }
    }
    private static EntityPlayerMP player() {
        EntityPlayerMP player=new EntityPlayerMP(server,world,
                new GameProfile(UUID.nameUUIDFromBytes(("inventory-fixture-"+(sequence++)).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"InventoryFixture"),
                new net.minecraft.server.management.PlayerInteractionManager(world));
        player.connection=new net.minecraft.network.NetHandlerPlayServer(server,
                new net.minecraft.network.NetworkManager(net.minecraft.network.EnumPacketDirection.SERVERBOUND),player);
        player.setPosition(64.5,80,64.5); RuntimeChecks.awaitChunk(world,4,4);
        return player;
    }
    private static EntityLootableBody body() {
        EntityLootableBody body=new EntityLootableBody(world);
        body.setPosition(64.5,80,64.5); body.setNoGravity(true);
        RuntimeChecks.awaitChunk(world,4,4); RuntimeChecks.disableVacuum(body);
        return body;
    }
    private static ItemStack item(int id,int count) {
        ItemStack stack=RuntimeChecks.stack(id); stack.setCount(count); return stack;
    }
    private static List<EntityItem> drops(EntityPlayerMP player,ItemStack... stacks) {
        List<EntityItem> result=new ArrayList<>();
        for(ItemStack stack:stacks) result.add(new EntityItem(player.getEntityWorld(),player.posX,player.posY,player.posZ,stack));
        return result;
    }
    private static void handle(PlayerDeathEventHandler handler,EntityPlayerMP player,List<EntityItem> drops) {
        handler.entityDropEvent(new LivingDropsEvent(player,DamageSource.OUT_OF_WORLD,drops,0,false));
    }
    private static NBTTagCompound data(EntityLootableBody body) {
        NBTTagCompound result=new NBTTagCompound(); body.writeAdditional(result); return result;
    }
    private static NBTTagCompound encoded(ItemStack stack) { return stack.write(new NBTTagCompound()); }
    private static Map<String,Integer> inventory(EntityLootableBody body) {
        Map<String,Integer> result=new TreeMap<>();
        for(int i=0;i<body.getSizeInventory();i++) add(result,body.getStackInSlot(i));
        NBTTagList aux=data(body).getList("Aux",10);
        for(int i=0;i<aux.size();i++) add(result,ItemStack.read(aux.getCompound(i)));
        return result;
    }
    private static void add(Map<String,Integer> result,ItemStack stack) {
        if((stack == null || stack.isEmpty()) || stack.getCount()<=0) return;
        NBTTagCompound tag=encoded(stack); tag.removeTag("Count");
        String key=tag.toString(); result.put(key,result.getOrDefault(key,0)+stack.getCount());
    }
    private static Map<String,Integer> dropped() {
        Map<String,Integer> result=new TreeMap<>();
        for(Entity entity:world.loadedEntityList) if(entity instanceof EntityItem && !entity.removed) add(result,((EntityItem)entity).getItem());
        return result;
    }
    private static EntityLootableBody newest(Set<Entity> before) {
        for(Entity entity:world.loadedEntityList) if(entity instanceof EntityLootableBody && !before.contains(entity)) return (EntityLootableBody)entity;
        throw new AssertionError("No corpse spawned");
    }
    private static void failedSpawn() {
        EntityPlayerMP player=player(); RejectingWorld rejected=new RejectingWorld(world);
        player.world=rejected;
        ItemStack bones=new ItemStack(Items.BONE,9); bones.setDisplayName(new net.minecraft.util.text.TextComponentString("Saved bones"));
        List<EntityItem> drops=drops(player,bones,item(19,23));
        List<NBTTagCompound> before=new ArrayList<>(); for(EntityItem e:drops) before.add(encoded(e.getItem()));
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
        EntityPlayerMP player=player(); player.inventory.mainInventory.set(0,item(4,1)); player.inventory.mainInventory.set(9,item(19,23));
        Map<String,Integer> expected=new TreeMap<>(); add(expected,player.inventory.mainInventory.get(0));add(expected,player.inventory.mainInventory.get(9));
        Set<Entity> before=new HashSet<>(world.loadedEntityList);
        RejectCorpse reject=new RejectCorpse(); MinecraftForge.EVENT_BUS.register(reject);
        try { player.onDeath(DamageSource.OUT_OF_WORLD); } finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        RuntimeChecks.require(reject.attempts==1,"Corpse cancellation not exercised");
        for(Entity entity:world.loadedEntityList) RuntimeChecks.require(before.contains(entity) || !(entity instanceof EntityLootableBody),"Cancelled corpse spawn bypassed Forge cancellation");
        RuntimeChecks.require(expected.equals(dropped()),"Cancelled corpse did not fall back to exact vanilla drops");
    }
    private static void staleEquipment() {
        EntityPlayerMP player=player(); PlayerDeathEventHandler handler=new PlayerDeathEventHandler();
        ItemStack previous=item(0,1); player.setItemStackToSlot(EntityEquipmentSlot.HEAD,previous);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        handle(handler,player,drops(player,previous));
        ItemStack current=item(1,1); player.setItemStackToSlot(EntityEquipmentSlot.HEAD,current);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        Map<String,Integer> expected=new TreeMap<>(); add(expected,current);add(expected,previous);
        Set<Entity> before=new HashSet<>(world.loadedEntityList);
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
        EntityPlayerMP player=player(); PlayerDeathEventHandler handler=new PlayerDeathEventHandler();
        player.setItemStackToSlot(EntityEquipmentSlot.HEAD,item(0,1));
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        handle(handler,player,drops(player,player.getItemStackFromSlot(EntityEquipmentSlot.HEAD)));
        RuntimeChecks.require(!cache(handler).containsKey(player),"Equipment cache retains completed death");
        world.getGameRules().setOrCreateGameRule("keepInventory","true",null);
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        RuntimeChecks.require(!cache(handler).containsKey(player),"keepInventory death cached equipment");
        world.getGameRules().setOrCreateGameRule("keepInventory","false",null);
        LivingDeathEvent canceled=new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD); canceled.setCanceled(true);
        handler.entityDeathEvent(canceled);
        RuntimeChecks.require(!cache(handler).containsKey(player),"Cancelled death cached equipment");
        handler.entityDeathEvent(new LivingDeathEvent(player,DamageSource.OUT_OF_WORLD));
        List<EntityItem> original=drops(player,item(19,23));
        LivingDropsEvent canceledDrops=new LivingDropsEvent(player,DamageSource.OUT_OF_WORLD,original,0,false);
        canceledDrops.setCanceled(true);handler.entityDropEvent(canceledDrops);
        RuntimeChecks.require(!cache(handler).containsKey(player) && original.size()==1
                && RuntimeChecks.sameStack(original.get(0).getItem(),item(19,23)),"Cancelled drops retained stale cache or changed inventory");
    }
    public static final class RejectItems {
        boolean rejectAll;
        String rejectFixture;
        @SubscribeEvent public void join(EntityJoinWorldEvent event) {
            if(event.getEntity() instanceof EntityItem) {
                ItemStack stack=((EntityItem)event.getEntity()).getItem();
                if(rejectAll || stack.hasTag() && rejectFixture.equals(stack.getTag().getString("fixture"))) event.setCanceled(true);
            }
        }
    }
    private static void failedRelease() throws Exception {
        EntityLootableBody corpse=body(); corpse.setInventorySlotContents(6,item(19,23));
        RuntimeChecks.require(world.spawnEntity(corpse),"Rejected-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems(); reject.rejectAll=true; MinecraftForge.EVENT_BUS.register(reject);
        try { corpse.onKillCommand(); for(int i=0;i<25;i++) corpse.baseTick(); }
        finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        RuntimeChecks.require(expected.equals(inventory(corpse)),"Rejected item spawn erased corpse inventory");
        RuntimeChecks.require(corpse.isAlive() && world.loadedEntityList.contains(corpse),"Rejected release removed the only inventory owner");
        NBTTagCompound saved=data(corpse); EntityLootableBody loaded=body();loaded.readAdditional(saved);
        RuntimeChecks.require(expected.equals(inventory(loaded)),"Rejected items do not survive NBT reload");
        corpse.onKillCommand();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Retry did not drop exact saved stacks once");
    }
    private static void partialRelease() {
        EntityLootableBody corpse=body();corpse.setInventorySlotContents(6,item(19,23));corpse.setInventorySlotContents(7,item(20,11));
        RuntimeChecks.require(world.spawnEntity(corpse),"Partial-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems();reject.rejectFixture="slot-20";MinecraftForge.EVENT_BUS.register(reject);
        try { corpse.onKillCommand();for(int i=0;i<12;i++)corpse.baseTick(); }
        finally { MinecraftForge.EVENT_BUS.unregister(reject); }
        Map<String,Integer> combined=new TreeMap<>(dropped());inventory(corpse).forEach((k,v)->combined.put(k,combined.getOrDefault(k,0)+v));
        RuntimeChecks.require(expected.equals(combined),"Partial release lost/duplicated a stack");
        RuntimeChecks.require((corpse.getStackInSlot(6)).isEmpty() && RuntimeChecks.sameStack(corpse.getStackInSlot(7),item(20,11)),"Partial release did not retain only rejected slot");
        corpse.onKillCommand();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Partial retry duplicated previously accepted drops");
    }
    private static void vanillaEquipment() {
        world.getGameRules().setOrCreateGameRule("doMobLoot","true",null);
        EntityLootableBody corpse=body();corpse.setInventorySlotContents(0,item(0,1));corpse.setDropChance(EntityEquipmentSlot.HEAD,2F);
        RuntimeChecks.require(world.spawnEntity(corpse),"Equipment-release owner spawn");
        Map<String,Integer> expected=inventory(corpse);
        corpse.onKillCommand();for(int i=0;i<12;i++)corpse.baseTick();
        RuntimeChecks.require(expected.equals(dropped()),"Vanilla mob equipment path duplicates corpse inventory");
    }
    private static void vacuumOnce() {
        EntityLootableBody first=body(),second=body();
        try { Field f=EntityLootableBody.class.getDeclaredField("vacuumTime");f.setAccessible(true);f.setByte(first,(byte)0);f.setByte(second,(byte)0); }
        catch(Exception error){throw new AssertionError(error);}
        EntityItem entity=new EntityItem(world,64.5,80,64.5,item(19,23));RuntimeChecks.require(world.spawnEntity(entity),"Vacuum input spawn");
        Map<String,Integer> expected=new TreeMap<>();add(expected,entity.getItem().copy());
        first.baseTick();second.baseTick();
        Map<String,Integer> all=inventory(first);inventory(second).forEach((k,v)->all.put(k,all.getOrDefault(k,0)+v));
        RuntimeChecks.require(expected.equals(all),"Two corpses claimed the same dying item entity");
    }
    private static void emptyRemoval() {
        EntityLootableBody corpse=body();
        RuntimeChecks.require((corpse.decrStackSize(6,1)).isEmpty(),"Empty removal must return EMPTY");
        corpse.setInventorySlotContents(6,item(19,23)); NBTTagCompound expected=encoded(corpse.getStackInSlot(6));
        RuntimeChecks.require((corpse.decrStackSize(6,-1)).isEmpty(),"Negative removal created a stack");
        RuntimeChecks.require(expected.equals(encoded(corpse.getStackInSlot(6))),"Negative removal changed stack count");
        RuntimeChecks.require((corpse.decrStackSize(6,0)).isEmpty(),"Zero removal created a stack");
        ItemStack removed=corpse.decrStackSize(6,40);RuntimeChecks.require(RuntimeChecks.sameStack(removed,item(19,23)) && (corpse.getStackInSlot(6)).isEmpty(),"Full extraction did not clear slot");
    }
    private static void repeatedRead() {
        EntityLootableBody source=body();for(int i=6;i<48;i++)source.setInventorySlotContents(i,item(i,1));source.addItem(item(70,7));
        NBTTagCompound input=data(source),immutable=input.copy();
        EntityLootableBody target=body(); target.readAdditional(input); target.readAdditional(input);
        RuntimeChecks.require(data(target).getTag("Equipment").equals(input.getTag("Equipment")) && data(target).getTag("Aux").equals(input.getTag("Aux")),"Repeated read appended overflow or changed slots");
        NBTTagCompound sparse=new NBTTagCompound();NBTTagList list=new NBTTagList();NBTTagCompound slot=encoded(item(19,23));slot.setByte("Slot",(byte)47);list.add(slot);sparse.setTag("Equipment",list);
        target.readAdditional(sparse);
        RuntimeChecks.require((target.getStackInSlot(6)).isEmpty() && RuntimeChecks.sameStack(target.getStackInSlot(47),item(19,23)) && !data(target).hasKey("Aux"),"Sparse reread kept stale inventory/overflow");
        RuntimeChecks.require(immutable.equals(input),"Reader mutated source NBT");
    }
    private static void staleAccess() {
        EntityPlayerMP player=player();EntityLootableBody corpse=body();CorpseContainer container=new CorpseContainer(player.inventory,corpse);
        RuntimeChecks.require(container.canInteractWith(player),"Near living access failed");
        player.setPosition(100,80,100);RuntimeChecks.require(!container.canInteractWith(player),"Distant container remained accessible");
        player.setPosition(64.5,80,64.5);corpse.remove();RuntimeChecks.require(!container.canInteractWith(player),"Removed corpse remained accessible");
        corpse=body();container=new CorpseContainer(player.inventory,corpse);player.world=new RejectingWorld(world);
        RuntimeChecks.require(!container.canInteractWith(player),"Cross-world container remained accessible");
        player.world=world;player.setHealth(0);
        RuntimeChecks.require(!container.canInteractWith(player),"Dead player retained container access");
        player.setHealth(player.getMaxHealth());corpse.onKillCommand();corpse.baseTick();
        RuntimeChecks.require(!container.canInteractWith(player),"Dying corpse remained accessible");
    }
    private static void overflow() {
        EntityLootableBody corpse=body();for(int i=6;i<48;i++)corpse.setInventorySlotContents(i,item(i,1));corpse.addItem(item(70,7));
        NBTTagCompound before=data(corpse);corpse.baseTick();
        RuntimeChecks.require(before.getTag("Equipment").equals(data(corpse).getTag("Equipment")),"Full inventory overwritten by overflow");
        corpse.removeStackFromSlot(47);corpse.baseTick();RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getStackInSlot(47),item(70,7)),"Overflow did not fill free slot");
        EntityLootableBody large=body();large.addItem(item(19,100));
        Map<String,Integer> expected=new TreeMap<>();add(expected,item(19,100));
        RuntimeChecks.require(expected.equals(inventory(large)),"Oversized incoming stack lost items");
        for(int i=6;i<48;i++) RuntimeChecks.require((large.getStackInSlot(i)).isEmpty() || large.getStackInSlot(i).getCount()<=64,"Oversized input bypassed slot capacity");
        EntityLootableBody queued=body();for(int i=6;i<48;i++)queued.setInventorySlotContents(i,item(i,1));
        queued.addItem(item(70,100));queued.removeStackFromSlot(47);expected=inventory(queued);
        queued.baseTick();
        RuntimeChecks.require(expected.equals(inventory(queued)) && queued.getStackInSlot(47).getCount()==64,"Overflow transfer lost items or exceeded slot capacity");
        queued.removeStackFromSlot(46);queued.baseTick();
        RuntimeChecks.require(RuntimeChecks.sameStack(queued.getStackInSlot(46),item(70,36)) && !data(queued).hasKey("Aux"),"Overflow remainder changed count or custom NBT");
    }
    private static void twoViewers() {
        EntityLootableBody corpse=body();corpse.setInventorySlotContents(6,item(19,23));EntityPlayerMP a=player(),b=player();
        CorpseContainer ca=new CorpseContainer(a.inventory,corpse),cb=new CorpseContainer(b.inventory,corpse);
        RuntimeChecks.require(RuntimeChecks.sameStack(ca.transferStackInSlot(a,6),item(19,23)),"First recovery failed");
        RuntimeChecks.require((cb.transferStackInSlot(b,6)).isEmpty(),"Second viewer recovered the same stack");
        Map<String,Integer> all=inventory(corpse);for(ItemStack s:a.inventory.mainInventory)add(all,s);for(ItemStack s:b.inventory.mainInventory)add(all,s);
        Map<String,Integer> expected=new TreeMap<>();add(expected,item(19,23));RuntimeChecks.require(expected.equals(all),"Two viewer recovery changed inventory/NBT");
    }
    private static void emptyValidation() {
        EntityLootableBody corpse=body();EntityPlayerMP player=player();CorpseContainer container=new CorpseContainer(player.inventory,corpse);
        RuntimeChecks.require(!corpse.isItemValidForSlot(0,null),"Empty armor input accepted");
        RuntimeChecks.require(!container.getSlot(0).isItemValid(null),"Container empty armor input accepted");
    }
    private static net.minecraft.world.chunk.Chunk cleanChunk(EntityLootableBody corpse) {
        net.minecraft.world.chunk.Chunk chunk=world.getChunk(corpse.getPosition());
        chunk.setLastSaveTime(world.getGameTime());chunk.setModified(false);
        RuntimeChecks.require(!chunk.needsSaving(false),"Dirty-chunk test precondition");
        return chunk;
    }
    private static void dirtyInventory() {
        EntityLootableBody corpse=body();corpse.setInventorySlotContents(6,item(19,23));
        RuntimeChecks.require(world.spawnEntity(corpse),"Dirty-chunk corpse spawn");
        net.minecraft.world.chunk.Chunk chunk=cleanChunk(corpse);corpse.markDirty();
        RuntimeChecks.require(chunk.needsSaving(false),"Inventory markDirty does not schedule a chunk save");
    }
    private static void dirtyMutations() {
        EntityLootableBody corpse=body();RuntimeChecks.require(world.spawnEntity(corpse),"Mutation corpse spawn");
        net.minecraft.world.chunk.Chunk chunk=cleanChunk(corpse);corpse.setInventorySlotContents(6,item(19,23));
        RuntimeChecks.require(chunk.needsSaving(false),"Slot insertion not scheduled for save");
        cleanChunk(corpse);corpse.decrStackSize(6,1);RuntimeChecks.require(chunk.needsSaving(false),"Extraction not scheduled for save");
        cleanChunk(corpse);corpse.removeStackFromSlot(6);RuntimeChecks.require(chunk.needsSaving(false),"Removal not scheduled for save");
        cleanChunk(corpse);corpse.initializeItems(new ItemStack[]{item(20,11)});RuntimeChecks.require(chunk.needsSaving(false),"Initial inventory not scheduled for save");
        for(int i=6;i<48;i++)corpse.setInventorySlotContents(i,item(i,1));
        cleanChunk(corpse);corpse.addItem(item(70,7));RuntimeChecks.require(chunk.needsSaving(false),"Overflow insertion not scheduled for save");
    }
    static void seedBlockedRelease(WorldServer target,File folder) throws Exception {
        EntityLootableBody corpse=new EntityLootableBody(target);
        corpse.setUniqueId(UUID.fromString("00000000-0000-4000-8000-000000000105"));
        corpse.setPosition(48.5,80,8.5);corpse.setNoGravity(true);corpse.setUserName("BlockedRelease");
        RuntimeChecks.awaitChunk(target,3,0);
        for(int i=0;i<48;i++)corpse.setInventorySlotContents(i,RuntimeChecks.stack(i));
        corpse.addItem(RuntimeChecks.stack(70));corpse.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(corpse);
        RuntimeChecks.require(target.spawnEntity(corpse),"Blocked-release fixture spawn");
        Map<String,Integer> expected=inventory(corpse);
        RejectItems reject=new RejectItems();reject.rejectAll=true;MinecraftForge.EVENT_BUS.register(reject);
        try {corpse.onKillCommand();for(int i=0;i<25;i++)corpse.baseTick();}
        finally {MinecraftForge.EVENT_BUS.unregister(reject);}
        RuntimeChecks.require(corpse.isAlive() && target.loadedEntityList.contains(corpse) && expected.equals(inventory(corpse)),"Blocked-release fixture lost inventory/owner");
        RuntimeChecks.saveExpected(folder,"rejected-release",corpse);
    }
    /** Controlled false-return seam; no dimension registration, disk writes or chunks of its own. */
    private static final class RejectingWorld extends World {
        private final WorldServer delegate; int attempts;
        RejectingWorld(WorldServer target) {
            super(target.getSaveHandler(),target.getMapStorage(),target.getWorldInfo(),target.dimension.getType().create(),new Profiler(),false);
            delegate=target;
        }
        @Override protected IChunkProvider createChunkProvider(){return delegate.getChunkProvider();}
        @Override public boolean isChunkLoaded(int x,int z,boolean empty){return true;}
        @Override public net.minecraft.world.ITickList<net.minecraft.block.Block> getPendingBlockTicks(){return delegate.getPendingBlockTicks();}
        @Override public net.minecraft.world.ITickList<net.minecraft.fluid.Fluid> getPendingFluidTicks(){return delegate.getPendingFluidTicks();}
        @Override public net.minecraft.item.crafting.RecipeManager getRecipeManager(){return delegate.getRecipeManager();}
        @Override public net.minecraft.scoreboard.Scoreboard getScoreboard(){return delegate.getScoreboard();}
        @Override public net.minecraft.tags.NetworkTagManager getTags(){return delegate.getTags();}
        @Override public net.minecraft.server.MinecraftServer getServer(){return delegate.getServer();}
        @Override public long getGameTime(){return delegate==null?0:delegate.getGameTime();}
        @Override public boolean spawnEntity(Entity entity){attempts++;return false;}
    }
}
