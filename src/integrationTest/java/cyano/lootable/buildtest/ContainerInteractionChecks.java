package cyano.lootable.buildtest;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Server packet/transaction tests; packets are decoded from their actual wire layout. */
final class ContainerInteractionChecks {
    private interface Check { void run() throws Exception; }
    private static MinecraftServer server;
    private static ServerLevel world;
    private static int sequence, failures;
    private static final List<String> results=new ArrayList<>();

    static void run(MinecraftServer instance,ServerLevel target,File folder) throws Exception {
        server=instance;world=target;failures=0;results.clear();
        check("hotbar_all_nine_empty_and_occupied",ContainerInteractionChecks::hotbar);
        check("hotbar_armor_validation_and_limits",ContainerInteractionChecks::armorSwap);
        check("shift_wearable_stack_obeys_slot_limit",ContainerInteractionChecks::wearableShift);
        check("left_drag_even_split_and_cursor_remainder",()->dragDistribution(0));
        check("right_drag_one_per_slot_and_cursor_remainder",()->dragDistribution(1));
        check("drag_rejects_invalid_full_and_different_nbt",ContainerInteractionChecks::dragMixed);
        check("drag_armor_respects_capacity",ContainerInteractionChecks::dragArmor);
        check("drag_cancel_and_out_of_order_do_not_move_items",ContainerInteractionChecks::dragCancel);
        check("full_player_shift_is_unchanged",ContainerInteractionChecks::fullPlayer);
        check("full_player_partial_merge_conserves_remainder",ContainerInteractionChecks::partialPlayer);
        check("full_corpse_keeps_existing_aux",ContainerInteractionChecks::fullCorpse);
        check("full_hotbar_displacement_success_conserves_items",()->displacement(false,false));
        check("full_hotbar_rejected_drop_keeps_displaced_item",()->displacement(true,false));
        check("full_hotbar_cancelled_toss_keeps_displaced_item",()->displacement(false,true));
        check("cursor_close_success_keeps_vanilla_drop",()->closeCursor(false,false));
        check("cursor_close_rejected_drop_with_player_space",()->closeCursor(true,false));
        check("cursor_close_rejected_drop_with_full_inventories",()->closeCursor(true,true));
        check("cursor_close_preserves_existing_death_capture",ContainerInteractionChecks::capturedCursor);
        check("cursor_close_after_release_keeps_live_inventory_owner",ContainerInteractionChecks::dyingCursor);
        check("two_player_same_slot_both_orders_and_resync",ContainerInteractionChecks::twoPlayerShift);
        check("two_player_drag_vs_pickup_preserves_both_tags",()->interleavedDrag(false));
        check("two_player_drag_vs_hotbar_preserves_both_tags",()->interleavedDrag(true));
        check("two_player_cursor_close_and_stale_click",ContainerInteractionChecks::twoPlayerClose);
        check("distant_packet_cannot_extract_before_tick_close",()->stale(false));
        check("removed_corpse_packet_cannot_extract",()->stale(true));
        check("drag_departure_resets_pending_distribution",ContainerInteractionChecks::staleDrag);
        try(PrintWriter out=new PrintWriter(new File(folder,"container-checks.txt"),"UTF-8")) {
            for(String line:results)out.println(line);
            out.println("checks="+results.size()+" failures="+failures);
        }
        RuntimeChecks.require(failures==0,"Container regressions: "+failures+" / "+results.size());
        System.out.println("LOOTABLE_BODIES_CONTAINER_PASS checks="+results.size());
    }

    static void runDrops(MinecraftServer instance,ServerLevel target,File folder) throws Exception {
        server=instance;world=target;failures=0;results.clear();
        for(boolean creative:new boolean[]{false,true}) for(int source=0;source<3;source++)
            for(int button=0;button<2;button++) for(int rejection=0;rejection<3;rejection++) {
                final boolean c=creative;final int a=source,b=button,d=rejection;
                check((c?"creative":"survival")+"_"+new String[]{"throw_corpse","throw_player","outside"}[a]+"_"+b+"_"+new String[]{"accepted","cancelled_toss","rejected_spawn"}[d],()->throwing(c,a,b,d,false));
            }
        for(boolean creative:new boolean[]{false,true}) for(int source:new int[]{0,2}) {
            final boolean c=creative;final int a=source;
            check((c?"creative":"survival")+"_capture_"+a,()->throwing(c,a,0,0,true));
        }
        for(boolean toss:new boolean[]{false,true}) {
            final boolean t=toss;
            check("creative_full_close_"+(t?"cancelled_toss":"rejected_spawn"),()->creativeFallback(t,false));
            check("creative_full_displacement_"+(t?"cancelled_toss":"rejected_spawn"),()->creativeFallback(t,true));
        }
        for(int mode=0;mode<6;mode++) { final int m=mode;check("double_click_"+m,()->collect(m)); }
        check("cancelled_close_preserves_hotbar_insertion_order",()->fallbackOrder(false));
        check("cancelled_close_preserves_offhand_merge",()->fallbackOrder(true));
        check("creative_clone_copies_complete_nbt",()->cloneStack(true,false));
        check("survival_cannot_clone",()->cloneStack(false,false));
        check("creative_clone_occupied_cursor_unchanged",()->cloneStack(true,true));
        check("creative_fill_drag_respects_limits_and_nbt",()->creativeDrag(true));
        check("survival_cannot_use_creative_fill_drag",()->creativeDrag(false));
        check("creative_inventory_packet_targets_player_not_corpse",()->creativePacket(true,false));
        check("survival_rejects_creative_inventory_packet",()->creativePacket(false,false));
        check("creative_cancelled_minted_drop_leaves_corpse_unchanged",()->creativePacket(true,true));
        for(int rejection=0;rejection<3;rejection++) {final int r=rejection;check("damaged_equipment_throw_"+r,()->toolThrow(r));}
        check("throw_interrupts_pending_drag_without_dropping",()->interruptDrag(false));
        check("outside_click_interrupts_pending_drag_without_dropping",()->interruptDrag(true));
        try(PrintWriter out=new PrintWriter(new File(folder,"drop-creative-checks.txt"),"UTF-8")) {
            for(String line:results)out.println(line);out.println("checks="+results.size()+" failures="+failures);
        }
        RuntimeChecks.require(failures==0,"Drop/creative regressions: "+failures+" / "+results.size());
        System.out.println("LOOTABLE_BODIES_DROP_CREATIVE_PASS checks="+results.size());
    }
    private static void toolThrow(int rejection) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);b.setItem(4,item(4,1));Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.toss=rejection==1;reject.join=rejection==2;MinecraftForge.EVENT_BUS.register(reject);
        try{packet(p,4,0,ClickType.THROW,null);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);equal(b.getItem(4),rejection==0?null:item(4,1),"Equipment throw changed damage/enchantment/custom data");
    }
    private static void interruptDrag(boolean outside) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);b.setItem(6,item(120,17));p.containerMenu.setCarried(item(121,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,7,1,ClickType.QUICK_CRAFT,null);
        packet(p,outside?-999:6,0,outside?ClickType.PICKUP:ClickType.THROW,null);
        equal(p.containerMenu.getCarried(),item(121,10),"Interrupted drag dropped cursor");equal(b.getItem(6),item(120,17),"Interrupted drag threw source");conserved(before,b,p);
    }
    private static void throwing(boolean creative,int source,int button,int rejection,boolean capture) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();p.setGameMode(creative?GameType.CREATIVE:GameType.SURVIVAL);
        fill(b);fill(p);open(p,b);ItemStack original=item(120,17);
        if(source==0)b.setItem(6,original.copy());
        else if(source==1)p.getInventory().items.set(9,original.copy());else p.containerMenu.setCarried(original.copy());
        if(capture){p.captureDrops(new java.util.ArrayList<>());p.captureDrops().add(new ItemEntity(world,96.5,80,96.5,item(121,11)));}
        Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=rejection==1;reject.join=rejection==2;
        RecordingConnection connection=(RecordingConnection)p.connection;int initialSyncs=connection.resyncs;
        MinecraftForge.EVENT_BUS.register(reject);
        try{packet(p,source==2?-999:source==0?6:48,button,source==2?ClickType.PICKUP:ClickType.THROW,null);}
        finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);RuntimeChecks.require((p.captureDrops()!=null)==capture,"Toss changed capture state");
        int amount=source==2?(button==0?17:1):(button==0?1:17);
        ItemStack remaining=rejection==0 && amount==17?null:item(120,rejection==0?17-amount:17);
        equal(source==2?p.containerMenu.getCarried():p.containerMenu.getSlot(source==0?6:48).getItem(),remaining,"Drop quantity/source restoration differs");
        if(rejection!=0)RuntimeChecks.require(connection.resyncs>initialSyncs,"Rejected predicted drop did not resync initiating client");
        if(capture)RuntimeChecks.require(p.captureDrops().size()==2,"Toss discarded pre-existing captured item");
        if(rejection==0 && !capture){
            int dropped=0;for(Entity e:FixtureApi.entities(world))if(e instanceof ItemEntity && !e.isRemoved()){equal(((ItemEntity)e).getItem(),item(120,amount),"Accepted drop NBT differs");dropped++;}
            RuntimeChecks.require(dropped==1,"Accepted toss did not create exactly one entity");
        }
    }
    private static void creativeFallback(boolean toss,boolean swap) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();fill(b);fill(p);p.setGameMode(GameType.CREATIVE);open(p,b);
        if(swap){b.setItem(0,item(0,1));p.getInventory().items.set(0,pumpkins(64));}
        else p.containerMenu.setCarried(item(120,17));
        Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=toss;reject.join=!toss;MinecraftForge.EVENT_BUS.register(reject);
        try{if(swap)packet(p,0,0,ClickType.SWAP,null);else close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);RuntimeChecks.require(b.isAlive(),"Creative fallback owner no longer alive");
    }
    private static void collect(int mode) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player(),q=player();open(p,b);open(q,b);
        b.setItem(6,item(120,20));b.setItem(7,item(120,30));b.setItem(8,item(121,14));
        ItemStack differentDamage=item(120,12);differentDamage.setDamageValue((differentDamage.getDamageValue()+1)%16);b.setItem(9,differentDamage);
        p.getInventory().items.set(9,item(120,10));p.containerMenu.setCarried(mode==3?ItemStack.EMPTY:item(120,mode==2?64:5));
        if(mode==4)p.setGameMode(GameType.CREATIVE);
        if(mode==5){q.containerMenu.setCarried(item(120,4));packet(q,10,0,ClickType.PICKUP_ALL,null);}
        Map<String,Integer> before=total(b,p,q);int wanted=mode==3?0:mode==2?64:mode==5?15:64;
        packet(p,10,mode==1?1:0,ClickType.PICKUP_ALL,null);
        RuntimeChecks.require(((p.containerMenu.getCarried()).isEmpty()?0:p.containerMenu.getCarried().getCount())==wanted,"Double click count/capacity differs");
        equal(b.getItem(8),item(121,14),"Double click collected different custom NBT");equal(b.getItem(9),differentDamage,"Double click collected different damage");
        if(mode==0)equal(p.getInventory().items.get(9),item(120,1),"Forward collection order differs");
        if(mode==1)equal(b.getItem(6),item(120,1),"Reverse collection order differs");
        conserved(before,b,p,q);
    }
    private static void fallbackOrder(boolean offhand) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);
        if(offhand){fill(p);p.getInventory().offhand.set(0,item(120,60));}
        p.containerMenu.setCarried(item(120,10));Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(p.getInventory().getItem(offhand?40:0),item(120,offhand?64:10),"Rejected-drop fallback changed vanilla player insertion priority");conserved(before,b,p);
    }
    private static void cloneStack(boolean creative,boolean occupied) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();p.setGameMode(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);b.setItem(6,item(120,17));
        if(occupied)p.containerMenu.setCarried(item(121,4));Map<String,Integer> expected=total(b,p);
        if(creative && !occupied)add(expected,item(120,64));packet(p,6,2,ClickType.CLONE,null);
        equal(b.getItem(6),item(120,17),"Creative clone removed source");equal(p.containerMenu.getCarried(),occupied?item(121,4):creative?item(120,64):null,"Clone cursor/NBT differs");conserved(expected,b,p);
    }
    private static void creativeDrag(boolean creative) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();p.setGameMode(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);p.containerMenu.setCarried(pumpkins(64));Map<String,Integer> expected=total(b,p);
        if(creative){add(expected,pumpkins(65));} // 1 armor + two main stacks, with original cursor consumed by vanilla 1.10.
        drag(p,2,0,6,7);equal(b.getItem(0),creative?pumpkins(1):null,"Creative fill armor capacity differs");
        equal(b.getItem(6),creative?pumpkins(64):null,"Creative fill main capacity differs");equal(b.getItem(7),creative?pumpkins(64):null,"Creative fill second main differs");conserved(expected,b,p);
    }
    private static void creativePacket(boolean creative,boolean cancelled) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();p.setGameMode(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);b.setItem(6,item(120,17));CompoundTag unchanged=data(b);
        RejectedDrops reject=new RejectedDrops();reject.toss=cancelled;MinecraftForge.EVENT_BUS.register(reject);FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ServerboundSetCreativeModeSlotPacket(cancelled?-1:9,item(121,23)).write(wire);
            p.connection.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(wire));
            RuntimeChecks.require(!wire.isReadable(),"Native creative packet left undecoded fields");
        }
        finally{wire.release();MinecraftForge.EVENT_BUS.unregister(reject);}
        RuntimeChecks.require(unchanged.equals(data(b)),"Creative inventory packet changed corpse instead of player");equal(p.getInventory().items.get(9),creative&&!cancelled?item(121,23):null,"Creative player packet permission/target differs");
        if(cancelled)RuntimeChecks.require(FixtureApi.entities(world).stream().noneMatch(e->e instanceof ItemEntity && !e.isRemoved()),"Cancelled creative minted drop spawned anyway");
    }

    private static void check(String name,Check test) {
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        try {test.run();results.add("PASS "+name);System.out.println("CONTAINER_CHECK_PASS "+name);}
        catch(Throwable error){failures++;results.add("FAIL "+name+": "+error);System.out.println("CONTAINER_CHECK_FAIL "+name+": "+error);error.printStackTrace();}
        finally{for(Entity entity:new ArrayList<Entity>(FixtureApi.entities(world)))if(!before.contains(entity))world.removeEntity(entity);}
    }
    private static ServerPlayer player() {
        ServerPlayer p=new ServerPlayer(server,world,
                new GameProfile(UUID.nameUUIDFromBytes(("container-player-"+sequence++).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"ContainerFixture"));
        p.connection=new RecordingConnection(p);p.setGameMode(GameType.SURVIVAL);
        p.setPos(96.5,80,96.5);world.getChunk(6,6);return p;
    }
    private static EntityLootableBody body(){
        EntityLootableBody b=new EntityLootableBody(world);b.setPos(96.5,80,96.5);b.setNoGravity(true);RuntimeChecks.disableVacuum(b);
        world.getChunk(6,6);RuntimeChecks.require(world.addFreshEntity(b),"Container corpse spawn");return b;
    }
    private static CorpseContainer open(ServerPlayer p,EntityLootableBody b){
        CorpseContainer c=new CorpseContainer(7,p.getInventory(),b);p.containerMenu=c;p.initMenu(c);return c;
    }
    private static ItemStack item(int id,int count){ItemStack s=RuntimeChecks.stack(id);s.setCount(count);return s;}
    private static ItemStack pumpkins(int count){ItemStack s=new ItemStack(Blocks.CARVED_PUMPKIN,count);s.setTag(item(120,1).getTag().copy());return s;}
    private static void fill(ServerPlayer p){for(int i=0;i<36;i++)p.getInventory().items.set(i,item(100+i,64));}
    private static void fill(EntityLootableBody b){for(int i=0;i<48;i++)b.setItem(i,item(i,i<6?1:64));b.addItem(item(70,31));}
    private static void packet(ServerPlayer p,int slot,int button,ClickType type,ItemStack predicted) throws Exception {
        packetWithState(p,slot,button,type,predicted,p.containerMenu.getStateId());
    }
    private static void packetWithState(ServerPlayer p,int slot,int button,ClickType type,ItemStack predicted,int stateId) throws Exception {
        it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack> changed=new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        if(predicted!=null && slot>=0)changed.put(slot,predicted.copy());
        // These server-side fixtures deliberately report pre-action predictions;
        // native broadcasts must repair them. Real clients cover normal prediction.
        ServerboundContainerClickPacket outgoing=new ServerboundContainerClickPacket(
                p.containerMenu.containerId,stateId,slot,button,type,p.containerMenu.getCarried().copy(),changed);
        FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            outgoing.write(wire);ServerboundContainerClickPacket decoded=new ServerboundContainerClickPacket(wire);
            RuntimeChecks.require(!wire.isReadable(),"Native menu packet left undecoded fields");
            p.connection.handleContainerClick(decoded);
        } finally {wire.release();}
    }
    private static void click(ServerPlayer p,int slot,int button,ClickType type) throws Exception {
        ItemStack predicted=null;
        if((type==ClickType.PICKUP || type==ClickType.QUICK_MOVE) && slot>=0){
            ItemStack s=p.containerMenu.getSlot(slot).getItem();predicted=s.copy();
        }
        packet(p,slot,button,type,predicted);
    }
    private static void close(ServerPlayer p) throws Exception {
        FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            new ServerboundContainerClosePacket(p.containerMenu.containerId).write(wire);
            p.connection.handleContainerClose(new ServerboundContainerClosePacket(wire));
            RuntimeChecks.require(!wire.isReadable(),"Native close packet left undecoded fields");
        } finally {wire.release();}
    }
    private static void drag(ServerPlayer p,int mode,int... slots) throws Exception {
        packet(p,-999,mode<<2,ClickType.QUICK_CRAFT,null);
        for(int slot:slots)packet(p,slot,1|(mode<<2),ClickType.QUICK_CRAFT,null);
        packet(p,-999,2|(mode<<2),ClickType.QUICK_CRAFT,null);
    }
    private static void equal(ItemStack actual,ItemStack expected,String reason){RuntimeChecks.require(RuntimeChecks.sameStack(actual == null ? ItemStack.EMPTY : actual,expected == null ? ItemStack.EMPTY : expected),reason);}
    private static CompoundTag data(EntityLootableBody b){CompoundTag n=new CompoundTag();b.addAdditionalSaveData(n);return n;}
    private static void add(Map<String,Integer> all,ItemStack s){
        if((s == null || s.isEmpty()) || s.getCount()<=0)return;CompoundTag n=s.save(new CompoundTag());n.remove("Count");
        String key=n.toString();all.put(key,all.getOrDefault(key,0)+s.getCount());
    }
    private static Map<String,Integer> total(EntityLootableBody b,ServerPlayer... players){
        Map<String,Integer> all=new TreeMap<>();
        for(int i=0;i<48;i++)add(all,b.getItem(i));ListTag aux=data(b).getList("Aux",10);
        for(int i=0;i<aux.size();i++)add(all,ItemStack.of(aux.getCompound(i)));
        for(ServerPlayer p:players){for(int i=0;i<p.getInventory().getContainerSize();i++)add(all,p.getInventory().getItem(i));add(all,p.containerMenu.getCarried());for(ItemEntity e:p.captureDrops()==null?java.util.Collections.<ItemEntity>emptyList():p.captureDrops())add(all,e.getItem());}
        for(Entity e:FixtureApi.entities(world))if(e instanceof ItemEntity && !e.isRemoved())add(all,((ItemEntity)e).getItem());
        return all;
    }
    private static void conserved(Map<String,Integer> expected,EntityLootableBody b,ServerPlayer... players){
        RuntimeChecks.require(expected.equals(total(b,players)),"Item count/type/damage/custom NBT lost or duplicated");
        CompoundTag before=data(b),immutable=before.copy();EntityLootableBody copy=new EntityLootableBody(world);copy.readAdditionalSaveData(before);
        RuntimeChecks.require(Objects.equals(before.get("Equipment"),data(copy).get("Equipment")) && Objects.equals(before.get("Aux"),data(copy).get("Aux")),"Post-interaction NBT round trip changed inventory");
        RuntimeChecks.require(before.equals(immutable),"Post-interaction reader mutated saved input");
    }
    private static void hotbar() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);
        for(int key=0;key<9;key++){
            p.getInventory().items.set(key,item(30+key,13));Map<String,Integer> before=total(b,p);
            click(p,6,key,ClickType.SWAP);equal(b.getItem(6),item(30+key,13),"Empty hotbar swap failed");equal(p.getInventory().items.get(key),null,"Hotbar source remained");
            click(p,6,key,ClickType.SWAP);equal(b.getItem(6),null,"Hotbar return left source");
            b.setItem(6,item(19,23));before=total(b,p);
            click(p,6,key,ClickType.SWAP);equal(b.getItem(6),item(30+key,13),"Occupied hotbar incoming differs");equal(p.getInventory().items.get(key),item(19,23),"Occupied hotbar outgoing differs");
            click(p,6,key,ClickType.SWAP);conserved(before,b,p);b.setItem(6,null);p.getInventory().items.set(key,ItemStack.EMPTY);
        }
    }
    private static void armorSwap() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.getInventory().items.set(0,item(4,1));
        click(p,0,0,ClickType.SWAP);equal(b.getItem(0),null,"Sword inserted into armor slot");
        p.getInventory().items.set(0,pumpkins(64));Map<String,Integer> before=total(b,p);click(p,0,0,ClickType.SWAP);
        equal(b.getItem(0),pumpkins(1),"Hotbar armor limit ignored");equal(p.getInventory().items.get(0),pumpkins(63),"Hotbar armor remainder lost");conserved(before,b,p);
    }
    private static void wearableShift() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();p.getInventory().items.set(9,pumpkins(64));open(p,b);
        Map<String,Integer> before=total(b,p);click(p,48,0,ClickType.QUICK_MOVE);
        RuntimeChecks.require(!(b.getItem(0)).isEmpty() && b.getItem(0).getCount()==1,"Shift-click put a stack into the one-item head slot");
        conserved(before,b,p);
    }
    private static void dragDistribution(int mode) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(item(19,10));Map<String,Integer> before=total(b,p);
        drag(p,mode,6,7,8);int each=mode==0?3:1;
        for(int i=6;i<=8;i++)equal(b.getItem(i),item(19,each),"Drag distribution differs");
        equal(p.containerMenu.getCarried(),item(19,10-3*each),"Drag cursor remainder differs");conserved(before,b,p);
    }
    private static void dragMixed() throws Exception {
        EntityLootableBody b=body();b.setItem(6,item(19,64));b.setItem(7,item(19,63));b.setItem(8,item(20,11));
        ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(item(19,12));Map<String,Integer> before=total(b,p);
        drag(p,0,0,6,7,8,9,48);equal(b.getItem(0),null,"Drag bypassed armor validity");equal(b.getItem(6),item(19,64),"Drag changed saturated slot");equal(b.getItem(8),item(20,11),"Drag merged different NBT");
        RuntimeChecks.require(b.getItem(7).getCount()<=64,"Drag overfilled partial slot");conserved(before,b,p);
    }
    private static void dragArmor() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(pumpkins(64));Map<String,Integer> before=total(b,p);
        drag(p,0,0,6);equal(b.getItem(0),pumpkins(1),"Drag head limit ignored");equal(b.getItem(6),pumpkins(32),"Drag main allocation differs");equal(p.containerMenu.getCarried(),pumpkins(31),"Drag head remainder lost");conserved(before,b,p);
    }
    private static void dragCancel() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(item(19,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,2,ClickType.QUICK_CRAFT,null);packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,6,1,ClickType.QUICK_CRAFT,null);
        packet(p,48,0,ClickType.PICKUP,null);packet(p,-999,2,ClickType.QUICK_CRAFT,null);
        equal(b.getItem(6),null,"Cancelled drag wrote selected slot");equal(p.containerMenu.getCarried(),item(19,10),"Cancelled drag consumed cursor");conserved(before,b,p);
    }
    private static void fullPlayer() throws Exception {
        EntityLootableBody b=body();b.setItem(6,item(19,23));ServerPlayer p=player();fill(p);open(p,b);
        Map<String,Integer> before=total(b,p);packet(p,6,0,ClickType.QUICK_MOVE,null);equal(b.getItem(6),item(19,23),"Full player inventory consumed corpse stack");conserved(before,b,p);
    }
    private static void partialPlayer() throws Exception {
        EntityLootableBody b=body();b.setItem(6,item(19,23));ServerPlayer p=player();fill(p);p.getInventory().items.set(35,item(19,63));open(p,b);
        Map<String,Integer> before=total(b,p);click(p,6,0,ClickType.QUICK_MOVE);equal(p.getInventory().items.get(35),item(19,64),"Partial merge did not fill one space");equal(b.getItem(6),item(19,22),"Partial merge discarded remainder");conserved(before,b,p);
    }
    private static void fullCorpse() throws Exception {
        EntityLootableBody b=body();fill(b);ServerPlayer p=player();p.getInventory().items.set(9,item(110,23));open(p,b);
        CompoundTag unchanged=data(b);Map<String,Integer> before=total(b,p);packet(p,48,0,ClickType.QUICK_MOVE,null);b.baseTick();
        RuntimeChecks.require(unchanged.get("Equipment").equals(data(b).get("Equipment")) && unchanged.get("Aux").equals(data(b).get("Aux")),"Full corpse overwrote indexed/Aux contents");conserved(before,b,p);
    }
    public static final class RejectedDrops {
        boolean join,toss;
        @SubscribeEvent public void join(EntityJoinWorldEvent e){if(join && e.getEntity() instanceof ItemEntity)e.setCanceled(true);}
        @SubscribeEvent public void toss(ItemTossEvent e){if(toss)e.setCanceled(true);}
    }
    private static void displacement(boolean rejectJoin,boolean rejectToss) throws Exception {
        EntityLootableBody b=body();b.setItem(0,item(0,1));ServerPlayer p=player();fill(p);p.getInventory().items.set(0,pumpkins(64));open(p,b);Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=rejectJoin;reject.toss=rejectToss;MinecraftForge.EVENT_BUS.register(reject);
        try{click(p,0,0,ClickType.SWAP);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(b.getItem(0),pumpkins(1),"Displacement changed normal incoming swap");equal(p.getInventory().items.get(0),pumpkins(63),"Displacement changed hotbar remainder");conserved(before,b,p);
        if(!rejectJoin && !rejectToss)RuntimeChecks.require(FixtureApi.entities(world).stream().anyMatch(e->e instanceof ItemEntity && !e.isRemoved()),"Successful displacement no longer drops normally");
    }
    private static void closeCursor(boolean rejected,boolean full) throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();if(full){fill(b);fill(p);}open(p,b);p.containerMenu.setCarried(item(120,17));Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=rejected;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(p.containerMenu.getCarried(),null,"Closing left a nonsaved cursor stack");conserved(before,b,p);
        if(!rejected)RuntimeChecks.require(FixtureApi.entities(world).stream().anyMatch(e->e instanceof ItemEntity && !e.isRemoved()),"Cursor close no longer performs normal accepted drop");
    }
    private static void capturedCursor() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(item(120,17));
        p.captureDrops(new java.util.ArrayList<>());p.captureDrops().add(new ItemEntity(world,96.5,80,96.5,item(121,11)));Map<String,Integer> before=total(b,p);
        close(p);RuntimeChecks.require(p.captureDrops()!=null,"Container close changed caller's drop capture state");conserved(before,b,p);
    }
    private static void dyingCursor() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();fill(p);open(p,b);p.containerMenu.setCarried(item(120,17));
        b.kill();b.baseTick();Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        for(int i=0;i<15;i++)b.baseTick();
        RuntimeChecks.require(b.isAlive() || b.isEmpty(),"Cursor fallback was put into an owner still scheduled for removal");
        conserved(before,b,p);
    }
    private static void twoPlayerShift() throws Exception {
        for(int order=0;order<2;order++){
            EntityLootableBody b=body();b.setItem(6,item(19,23));ServerPlayer a=player(),c=player();open(a,b);open(c,b);
            ServerPlayer first=order==0?a:c,second=order==0?c:a;Map<String,Integer> before=total(b,a,c);
            int staleState=second.containerMenu.getStateId();
            click(first,6,0,ClickType.QUICK_MOVE);second.containerMenu.broadcastChanges();
            RecordingConnection staleConnection=(RecordingConnection)second.connection;int beforeResync=staleConnection.resyncs;
            packetWithState(second,6,0,ClickType.QUICK_MOVE,item(19,23),staleState);
            a.containerMenu.broadcastChanges();c.containerMenu.broadcastChanges();
            RuntimeChecks.require(staleConnection.resyncs>beforeResync && staleConnection.lastContentStateId==second.containerMenu.getStateId(),
                    "Stale menu state did not receive an authoritative native state correction");
            // Accepted clicks rely on the initiating client's prediction; only the
            // rejected stale viewer requires an authoritative window resync.
            equal(first.containerMenu.getSlot(6).getItem(),null,"First server container remained stale");
            equal(((RecordingConnection)second.connection).slots.get(6),null,"Rejected viewer source remained stale");conserved(before,b,a,c);
        }
    }
    private static void interleavedDrag(boolean swap) throws Exception {
        EntityLootableBody b=body();ServerPlayer a=player(),c=player();open(a,b);open(c,b);a.containerMenu.setCarried(item(19,10));
        if(swap)c.getInventory().items.set(0,item(20,7));else c.containerMenu.setCarried(item(20,7));Map<String,Integer> before=total(b,a,c);
        packet(a,-999,0,ClickType.QUICK_CRAFT,null);packet(a,6,1,ClickType.QUICK_CRAFT,null);packet(a,7,1,ClickType.QUICK_CRAFT,null);
        click(c,6,0,swap?ClickType.SWAP:ClickType.PICKUP);packet(a,-999,2,ClickType.QUICK_CRAFT,null);
        equal(b.getItem(6),item(20,7),"Other player's stack was overwritten by drag");equal(b.getItem(7),item(19,5),"Surviving drag slot differs");equal(a.containerMenu.getCarried(),item(19,5),"Interrupted drag lost remainder");conserved(before,b,a,c);
    }
    private static void twoPlayerClose() throws Exception {
        EntityLootableBody b=body();b.setItem(6,item(19,23));ServerPlayer a=player(),c=player();fill(a);open(a,b);open(c,b);Map<String,Integer> before=total(b,a,c);
        click(a,6,0,ClickType.PICKUP);packet(c,6,0,ClickType.QUICK_MOVE,item(19,23));RejectedDrops reject=new RejectedDrops();reject.join=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(a);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        c.containerMenu.broadcastChanges();conserved(before,b,a,c);
    }
    private static void stale(boolean removed) throws Exception {
        EntityLootableBody b=body();b.setItem(6,item(19,23));ServerPlayer p=player();open(p,b);Map<String,Integer> before=total(b,p);
        if(removed)b.discard();else p.setPos(200,80,200);
        packet(p,6,0,ClickType.QUICK_MOVE,null);equal(b.getItem(6),item(19,23),"Stale packet extracted before tick closed GUI");conserved(before,b,p);
    }
    private static void staleDrag() throws Exception {
        EntityLootableBody b=body();ServerPlayer p=player();open(p,b);p.containerMenu.setCarried(item(19,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,6,1,ClickType.QUICK_CRAFT,null);p.setPos(200,80,200);
        packet(p,-999,2,ClickType.QUICK_CRAFT,null);equal(b.getItem(6),null,"Distant drag still distributed items");
        p.setPos(96.5,80,96.5);drag(p,0,7);equal(b.getItem(7),item(19,10),"New drag reused invalid selection");conserved(before,b,p);
    }
    private static final class RecordingConnection extends ServerGamePacketListenerImpl {
        final Map<Integer,ItemStack> slots=new HashMap<>();int resyncs,lastContentStateId=-1;
        ItemStack carried=ItemStack.EMPTY;
        RecordingConnection(ServerPlayer p){super(server,new Connection(PacketFlow.SERVERBOUND),p);}
        @Override public void send(Packet<?> packet){
            NativeMenuObservation observed=NativeMenuObservation.decode(packet);if(observed==null)return;
            if(observed.content) {
                slots.clear();for(int index=0;index<observed.items.size();index++)slots.put(index,observed.items.get(index));
                carried=observed.carried;lastContentStateId=observed.stateId;resyncs++;
            } else if(observed.containerId>=0)slots.put(observed.slot,observed.item);
            else if(observed.containerId==-1)carried=observed.item;
        }
    }
}
