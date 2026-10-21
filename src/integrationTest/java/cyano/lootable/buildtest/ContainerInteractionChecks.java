package cyano.lootable.buildtest;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Server packet/transaction tests; packets are decoded from their actual wire layout. */
final class ContainerInteractionChecks {
    private interface Check { void run() throws Exception; }
    private static MinecraftServer server;
    private static WorldServer world;
    private static int sequence, failures;
    private static short transaction;
    private static final List<String> results=new ArrayList<>();

    static void run(MinecraftServer instance,WorldServer target,File folder) throws Exception {
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

    static void runDrops(MinecraftServer instance,WorldServer target,File folder) throws Exception {
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
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);b.setInventorySlotContents(4,item(4,1));Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.toss=rejection==1;reject.join=rejection==2;MinecraftForge.EVENT_BUS.register(reject);
        try{packet(p,4,0,ClickType.THROW,null);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);equal(b.getStackInSlot(4),rejection==0?null:item(4,1),"Equipment throw changed damage/enchantment/custom data");
    }
    private static void interruptDrag(boolean outside) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);b.setInventorySlotContents(6,item(120,17));p.inventory.setItemStack(item(121,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,7,1,ClickType.QUICK_CRAFT,null);
        packet(p,outside?-999:6,0,outside?ClickType.PICKUP:ClickType.THROW,null);
        equal(p.inventory.getItemStack(),item(121,10),"Interrupted drag dropped cursor");equal(b.getStackInSlot(6),item(120,17),"Interrupted drag threw source");conserved(before,b,p);
    }
    private static void throwing(boolean creative,int source,int button,int rejection,boolean capture) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();p.setGameType(creative?GameType.CREATIVE:GameType.SURVIVAL);
        fill(b);fill(p);open(p,b);ItemStack original=item(120,17);
        if(source==0)b.setInventorySlotContents(6,original.copy());
        else if(source==1)p.inventory.mainInventory[9]=original.copy();else p.inventory.setItemStack(original.copy());
        if(capture){p.captureDrops=true;p.capturedDrops.add(new EntityItem(world,96.5,80,96.5,item(121,11)));}
        Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=rejection==1;reject.join=rejection==2;
        RecordingConnection connection=(RecordingConnection)p.connection;int initialSyncs=connection.resyncs;
        MinecraftForge.EVENT_BUS.register(reject);
        try{packet(p,source==2?-999:source==0?6:48,button,source==2?ClickType.PICKUP:ClickType.THROW,null);}
        finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);RuntimeChecks.require(p.captureDrops==capture,"Toss changed capture state");
        int amount=source==2?(button==0?17:1):(button==0?1:17);
        ItemStack remaining=rejection==0 && amount==17?null:item(120,rejection==0?17-amount:17);
        equal(source==2?p.inventory.getItemStack():p.openContainer.getSlot(source==0?6:48).getStack(),remaining,"Drop quantity/source restoration differs");
        if(rejection!=0)RuntimeChecks.require(connection.resyncs>initialSyncs,"Rejected predicted drop did not resync initiating client");
        if(capture)RuntimeChecks.require(p.capturedDrops.size()==2,"Toss discarded pre-existing captured item");
        if(rejection==0 && !capture){
            int dropped=0;for(Entity e:world.loadedEntityList)if(e instanceof EntityItem && !e.isDead){equal(((EntityItem)e).getEntityItem(),item(120,amount),"Accepted drop NBT differs");dropped++;}
            RuntimeChecks.require(dropped==1,"Accepted toss did not create exactly one entity");
        }
    }
    private static void creativeFallback(boolean toss,boolean swap) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();fill(b);fill(p);p.setGameType(GameType.CREATIVE);open(p,b);
        if(swap){b.setInventorySlotContents(0,item(0,1));p.inventory.mainInventory[0]=pumpkins(64);}
        else p.inventory.setItemStack(item(120,17));
        Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=toss;reject.join=!toss;MinecraftForge.EVENT_BUS.register(reject);
        try{if(swap)packet(p,0,0,ClickType.SWAP,null);else close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        conserved(before,b,p);RuntimeChecks.require(b.isEntityAlive(),"Creative fallback owner no longer alive");
    }
    private static void collect(int mode) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player(),q=player();open(p,b);open(q,b);
        b.setInventorySlotContents(6,item(120,20));b.setInventorySlotContents(7,item(120,30));b.setInventorySlotContents(8,item(121,14));
        ItemStack differentDamage=item(120,12);differentDamage.setItemDamage((differentDamage.getItemDamage()+1)%16);b.setInventorySlotContents(9,differentDamage);
        p.inventory.mainInventory[9]=item(120,10);p.inventory.setItemStack(mode==3?null:item(120,mode==2?64:5));
        if(mode==4)p.setGameType(GameType.CREATIVE);
        if(mode==5){q.inventory.setItemStack(item(120,4));packet(q,10,0,ClickType.PICKUP_ALL,null);}
        Map<String,Integer> before=total(b,p,q);int wanted=mode==3?0:mode==2?64:mode==5?15:64;
        packet(p,10,mode==1?1:0,ClickType.PICKUP_ALL,null);
        RuntimeChecks.require((p.inventory.getItemStack()==null?0:p.inventory.getItemStack().stackSize)==wanted,"Double click count/capacity differs");
        equal(b.getStackInSlot(8),item(121,14),"Double click collected different custom NBT");equal(b.getStackInSlot(9),differentDamage,"Double click collected different damage");
        if(mode==0)equal(p.inventory.mainInventory[9],item(120,1),"Forward collection order differs");
        if(mode==1)equal(b.getStackInSlot(6),item(120,1),"Reverse collection order differs");
        conserved(before,b,p,q);
    }
    private static void fallbackOrder(boolean offhand) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);
        if(offhand){fill(p);p.inventory.offHandInventory[0]=item(120,60);}
        p.inventory.setItemStack(item(120,10));Map<String,Integer> before=total(b,p);RejectedDrops reject=new RejectedDrops();reject.toss=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(p.inventory.getStackInSlot(offhand?40:0),item(120,offhand?64:10),"Rejected-drop fallback changed vanilla player insertion priority");conserved(before,b,p);
    }
    private static void cloneStack(boolean creative,boolean occupied) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();p.setGameType(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);b.setInventorySlotContents(6,item(120,17));
        if(occupied)p.inventory.setItemStack(item(121,4));Map<String,Integer> expected=total(b,p);
        if(creative && !occupied)add(expected,item(120,64));packet(p,6,2,ClickType.CLONE,null);
        equal(b.getStackInSlot(6),item(120,17),"Creative clone removed source");equal(p.inventory.getItemStack(),occupied?item(121,4):creative?item(120,64):null,"Clone cursor/NBT differs");conserved(expected,b,p);
    }
    private static void creativeDrag(boolean creative) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();p.setGameType(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);p.inventory.setItemStack(pumpkins(64));Map<String,Integer> expected=total(b,p);
        if(creative){add(expected,pumpkins(65));} // 1 armor + two main stacks, with original cursor consumed by vanilla 1.10.
        drag(p,2,0,6,7);equal(b.getStackInSlot(0),creative?pumpkins(1):null,"Creative fill armor capacity differs");
        equal(b.getStackInSlot(6),creative?pumpkins(64):null,"Creative fill main capacity differs");equal(b.getStackInSlot(7),creative?pumpkins(64):null,"Creative fill second main differs");conserved(expected,b,p);
    }
    private static void creativePacket(boolean creative,boolean cancelled) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();p.setGameType(creative?GameType.CREATIVE:GameType.SURVIVAL);open(p,b);b.setInventorySlotContents(6,item(120,17));NBTTagCompound unchanged=data(b);
        RejectedDrops reject=new RejectedDrops();reject.toss=cancelled;MinecraftForge.EVENT_BUS.register(reject);PacketBuffer wire=new PacketBuffer(Unpooled.buffer());
        try{wire.writeShort(cancelled?-1:9);wire.writeItemStack(item(121,23));CPacketCreativeInventoryAction packet=new CPacketCreativeInventoryAction();packet.readPacketData(wire);p.connection.processCreativeInventoryAction(packet);}
        finally{wire.release();MinecraftForge.EVENT_BUS.unregister(reject);}
        RuntimeChecks.require(unchanged.equals(data(b)),"Creative inventory packet changed corpse instead of player");equal(p.inventory.mainInventory[9],creative&&!cancelled?item(121,23):null,"Creative player packet permission/target differs");
        if(cancelled)RuntimeChecks.require(world.loadedEntityList.stream().noneMatch(e->e instanceof EntityItem && !e.isDead),"Cancelled creative minted drop spawned anyway");
    }

    private static void check(String name,Check test) {
        Set<Entity> before=new HashSet<>(world.loadedEntityList);
        try {test.run();results.add("PASS "+name);System.out.println("CONTAINER_CHECK_PASS "+name);}
        catch(Throwable error){failures++;results.add("FAIL "+name+": "+error);System.out.println("CONTAINER_CHECK_FAIL "+name+": "+error);error.printStackTrace();}
        finally{for(Entity entity:new ArrayList<Entity>(world.loadedEntityList))if(!before.contains(entity))world.removeEntityDangerously(entity);}
    }
    private static EntityPlayerMP player() {
        EntityPlayerMP p=new EntityPlayerMP(server,world,
                new GameProfile(UUID.nameUUIDFromBytes(("container-player-"+sequence++).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"ContainerFixture"),
                new net.minecraft.server.management.PlayerInteractionManager(world));
        p.connection=new RecordingConnection(p);p.setGameType(GameType.SURVIVAL);
        p.setPosition(96.5,80,96.5);world.getChunkFromChunkCoords(6,6);return p;
    }
    private static EntityLootableBody body(){
        EntityLootableBody b=new EntityLootableBody(world);b.setPosition(96.5,80,96.5);b.setNoGravity(true);RuntimeChecks.disableVacuum(b);
        world.getChunkFromChunkCoords(6,6);RuntimeChecks.require(world.spawnEntity(b),"Container corpse spawn");return b;
    }
    private static CorpseContainer open(EntityPlayerMP p,EntityLootableBody b){
        CorpseContainer c=new CorpseContainer(p.inventory,b);c.windowId=7;p.openContainer=c;c.addListener(p);return c;
    }
    private static ItemStack item(int id,int count){ItemStack s=RuntimeChecks.stack(id);s.stackSize=count;return s;}
    private static ItemStack pumpkins(int count){ItemStack s=new ItemStack(Blocks.PUMPKIN,count);s.setTagCompound(item(120,1).getTagCompound().copy());return s;}
    private static void fill(EntityPlayerMP p){for(int i=0;i<36;i++)p.inventory.mainInventory[i]=item(100+i,64);}
    private static void fill(EntityLootableBody b){for(int i=0;i<48;i++)b.setInventorySlotContents(i,item(i,i<6?1:64));b.addItem(item(70,31));}
    private static void packet(EntityPlayerMP p,int slot,int button,ClickType type,ItemStack predicted) throws Exception {
        PacketBuffer wire=new PacketBuffer(Unpooled.buffer());
        try{
            wire.writeByte(p.openContainer.windowId);wire.writeShort(slot);wire.writeByte(button);wire.writeShort(++transaction);
            wire.writeEnumValue(type);wire.writeItemStack(predicted);
            CPacketClickWindow packet=new CPacketClickWindow();packet.readPacketData(wire);p.connection.processClickWindow(packet);
        } finally{wire.release();}
    }
    private static void click(EntityPlayerMP p,int slot,int button,ClickType type) throws Exception {
        ItemStack predicted=null;
        if((type==ClickType.PICKUP || type==ClickType.QUICK_MOVE) && slot>=0){
            ItemStack s=p.openContainer.getSlot(slot).getStack();predicted=s==null?null:s.copy();
        }
        packet(p,slot,button,type,predicted);
    }
    private static void close(EntityPlayerMP p) throws Exception {
        PacketBuffer wire=new PacketBuffer(Unpooled.buffer());
        try{wire.writeByte(p.openContainer.windowId);CPacketCloseWindow packet=new CPacketCloseWindow();packet.readPacketData(wire);p.connection.processCloseWindow(packet);}
        finally{wire.release();}
    }
    private static void drag(EntityPlayerMP p,int mode,int... slots) throws Exception {
        packet(p,-999,mode<<2,ClickType.QUICK_CRAFT,null);
        for(int slot:slots)packet(p,slot,1|(mode<<2),ClickType.QUICK_CRAFT,null);
        packet(p,-999,2|(mode<<2),ClickType.QUICK_CRAFT,null);
    }
    private static void equal(ItemStack actual,ItemStack expected,String reason){RuntimeChecks.require(ItemStack.areItemStacksEqual(actual,expected),reason);}
    private static NBTTagCompound data(EntityLootableBody b){NBTTagCompound n=new NBTTagCompound();b.writeEntityToNBT(n);return n;}
    private static void add(Map<String,Integer> all,ItemStack s){
        if(s==null || s.stackSize<=0)return;NBTTagCompound n=s.writeToNBT(new NBTTagCompound());n.removeTag("Count");
        String key=n.toString();all.put(key,all.getOrDefault(key,0)+s.stackSize);
    }
    private static Map<String,Integer> total(EntityLootableBody b,EntityPlayerMP... players){
        Map<String,Integer> all=new TreeMap<>();
        for(int i=0;i<48;i++)add(all,b.getStackInSlot(i));NBTTagList aux=data(b).getTagList("Aux",10);
        for(int i=0;i<aux.tagCount();i++)add(all,ItemStack.loadItemStackFromNBT(aux.getCompoundTagAt(i)));
        for(EntityPlayerMP p:players){for(int i=0;i<p.inventory.getSizeInventory();i++)add(all,p.inventory.getStackInSlot(i));add(all,p.inventory.getItemStack());for(EntityItem e:p.capturedDrops)add(all,e.getEntityItem());}
        for(Entity e:world.loadedEntityList)if(e instanceof EntityItem && !e.isDead)add(all,((EntityItem)e).getEntityItem());
        return all;
    }
    private static void conserved(Map<String,Integer> expected,EntityLootableBody b,EntityPlayerMP... players){
        RuntimeChecks.require(expected.equals(total(b,players)),"Item count/type/damage/custom NBT lost or duplicated");
        NBTTagCompound before=data(b),immutable=before.copy();EntityLootableBody copy=new EntityLootableBody(world);copy.readEntityFromNBT(before);
        RuntimeChecks.require(Objects.equals(before.getTag("Equipment"),data(copy).getTag("Equipment")) && Objects.equals(before.getTag("Aux"),data(copy).getTag("Aux")),"Post-interaction NBT round trip changed inventory");
        RuntimeChecks.require(before.equals(immutable),"Post-interaction reader mutated saved input");
    }
    private static void hotbar() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);
        for(int key=0;key<9;key++){
            p.inventory.mainInventory[key]=item(30+key,13);Map<String,Integer> before=total(b,p);
            click(p,6,key,ClickType.SWAP);equal(b.getStackInSlot(6),item(30+key,13),"Empty hotbar swap failed");equal(p.inventory.mainInventory[key],null,"Hotbar source remained");
            click(p,6,key,ClickType.SWAP);equal(b.getStackInSlot(6),null,"Hotbar return left source");
            b.setInventorySlotContents(6,item(19,23));before=total(b,p);
            click(p,6,key,ClickType.SWAP);equal(b.getStackInSlot(6),item(30+key,13),"Occupied hotbar incoming differs");equal(p.inventory.mainInventory[key],item(19,23),"Occupied hotbar outgoing differs");
            click(p,6,key,ClickType.SWAP);conserved(before,b,p);b.setInventorySlotContents(6,null);p.inventory.mainInventory[key]=null;
        }
    }
    private static void armorSwap() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.mainInventory[0]=item(4,1);
        click(p,0,0,ClickType.SWAP);equal(b.getStackInSlot(0),null,"Sword inserted into armor slot");
        p.inventory.mainInventory[0]=pumpkins(64);Map<String,Integer> before=total(b,p);click(p,0,0,ClickType.SWAP);
        equal(b.getStackInSlot(0),pumpkins(1),"Hotbar armor limit ignored");equal(p.inventory.mainInventory[0],pumpkins(63),"Hotbar armor remainder lost");conserved(before,b,p);
    }
    private static void wearableShift() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();p.inventory.mainInventory[9]=pumpkins(64);open(p,b);
        Map<String,Integer> before=total(b,p);click(p,48,0,ClickType.QUICK_MOVE);
        RuntimeChecks.require(b.getStackInSlot(0)!=null && b.getStackInSlot(0).stackSize==1,"Shift-click put a stack into the one-item head slot");
        conserved(before,b,p);
    }
    private static void dragDistribution(int mode) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(item(19,10));Map<String,Integer> before=total(b,p);
        drag(p,mode,6,7,8);int each=mode==0?3:1;
        for(int i=6;i<=8;i++)equal(b.getStackInSlot(i),item(19,each),"Drag distribution differs");
        equal(p.inventory.getItemStack(),item(19,10-3*each),"Drag cursor remainder differs");conserved(before,b,p);
    }
    private static void dragMixed() throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,64));b.setInventorySlotContents(7,item(19,63));b.setInventorySlotContents(8,item(20,11));
        EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(item(19,12));Map<String,Integer> before=total(b,p);
        drag(p,0,0,6,7,8,9,48);equal(b.getStackInSlot(0),null,"Drag bypassed armor validity");equal(b.getStackInSlot(6),item(19,64),"Drag changed saturated slot");equal(b.getStackInSlot(8),item(20,11),"Drag merged different NBT");
        RuntimeChecks.require(b.getStackInSlot(7).stackSize<=64,"Drag overfilled partial slot");conserved(before,b,p);
    }
    private static void dragArmor() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(pumpkins(64));Map<String,Integer> before=total(b,p);
        drag(p,0,0,6);equal(b.getStackInSlot(0),pumpkins(1),"Drag head limit ignored");equal(b.getStackInSlot(6),pumpkins(32),"Drag main allocation differs");equal(p.inventory.getItemStack(),pumpkins(31),"Drag head remainder lost");conserved(before,b,p);
    }
    private static void dragCancel() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(item(19,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,2,ClickType.QUICK_CRAFT,null);packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,6,1,ClickType.QUICK_CRAFT,null);
        packet(p,48,0,ClickType.PICKUP,null);packet(p,-999,2,ClickType.QUICK_CRAFT,null);
        equal(b.getStackInSlot(6),null,"Cancelled drag wrote selected slot");equal(p.inventory.getItemStack(),item(19,10),"Cancelled drag consumed cursor");conserved(before,b,p);
    }
    private static void fullPlayer() throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,23));EntityPlayerMP p=player();fill(p);open(p,b);
        Map<String,Integer> before=total(b,p);packet(p,6,0,ClickType.QUICK_MOVE,null);equal(b.getStackInSlot(6),item(19,23),"Full player inventory consumed corpse stack");conserved(before,b,p);
    }
    private static void partialPlayer() throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,23));EntityPlayerMP p=player();fill(p);p.inventory.mainInventory[35]=item(19,63);open(p,b);
        Map<String,Integer> before=total(b,p);click(p,6,0,ClickType.QUICK_MOVE);equal(p.inventory.mainInventory[35],item(19,64),"Partial merge did not fill one space");equal(b.getStackInSlot(6),item(19,22),"Partial merge discarded remainder");conserved(before,b,p);
    }
    private static void fullCorpse() throws Exception {
        EntityLootableBody b=body();fill(b);EntityPlayerMP p=player();p.inventory.mainInventory[9]=item(110,23);open(p,b);
        NBTTagCompound unchanged=data(b);Map<String,Integer> before=total(b,p);packet(p,48,0,ClickType.QUICK_MOVE,null);b.onEntityUpdate();
        RuntimeChecks.require(unchanged.getTag("Equipment").equals(data(b).getTag("Equipment")) && unchanged.getTag("Aux").equals(data(b).getTag("Aux")),"Full corpse overwrote indexed/Aux contents");conserved(before,b,p);
    }
    public static final class RejectedDrops {
        boolean join,toss;
        @SubscribeEvent public void join(EntityJoinWorldEvent e){if(join && e.getEntity() instanceof EntityItem)e.setCanceled(true);}
        @SubscribeEvent public void toss(ItemTossEvent e){if(toss)e.setCanceled(true);}
    }
    private static void displacement(boolean rejectJoin,boolean rejectToss) throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(0,item(0,1));EntityPlayerMP p=player();fill(p);p.inventory.mainInventory[0]=pumpkins(64);open(p,b);Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=rejectJoin;reject.toss=rejectToss;MinecraftForge.EVENT_BUS.register(reject);
        try{click(p,0,0,ClickType.SWAP);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(b.getStackInSlot(0),pumpkins(1),"Displacement changed normal incoming swap");equal(p.inventory.mainInventory[0],pumpkins(63),"Displacement changed hotbar remainder");conserved(before,b,p);
        if(!rejectJoin && !rejectToss)RuntimeChecks.require(world.loadedEntityList.stream().anyMatch(e->e instanceof EntityItem && !e.isDead),"Successful displacement no longer drops normally");
    }
    private static void closeCursor(boolean rejected,boolean full) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();if(full){fill(b);fill(p);}open(p,b);p.inventory.setItemStack(item(120,17));Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=rejected;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        equal(p.inventory.getItemStack(),null,"Closing left a nonsaved cursor stack");conserved(before,b,p);
        if(!rejected)RuntimeChecks.require(world.loadedEntityList.stream().anyMatch(e->e instanceof EntityItem && !e.isDead),"Cursor close no longer performs normal accepted drop");
    }
    private static void capturedCursor() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(item(120,17));
        p.captureDrops=true;p.capturedDrops.add(new EntityItem(world,96.5,80,96.5,item(121,11)));Map<String,Integer> before=total(b,p);
        close(p);RuntimeChecks.require(p.captureDrops,"Container close changed caller's drop capture state");conserved(before,b,p);
    }
    private static void dyingCursor() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();fill(p);open(p,b);p.inventory.setItemStack(item(120,17));
        b.onKillCommand();b.onEntityUpdate();Map<String,Integer> before=total(b,p);
        RejectedDrops reject=new RejectedDrops();reject.join=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(p);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        for(int i=0;i<15;i++)b.onEntityUpdate();
        RuntimeChecks.require(b.isEntityAlive() || b.isEmpty(),"Cursor fallback was put into an owner still scheduled for removal");
        conserved(before,b,p);
    }
    private static void twoPlayerShift() throws Exception {
        for(int order=0;order<2;order++){
            EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,23));EntityPlayerMP a=player(),c=player();open(a,b);open(c,b);
            EntityPlayerMP first=order==0?a:c,second=order==0?c:a;Map<String,Integer> before=total(b,a,c);
            click(first,6,0,ClickType.QUICK_MOVE);packet(second,6,0,ClickType.QUICK_MOVE,item(19,23));
            a.openContainer.detectAndSendChanges();c.openContainer.detectAndSendChanges();
            RuntimeChecks.require(!second.openContainer.getCanCraft(second),"Stale transaction was not rejected");
            RuntimeChecks.require(((RecordingConnection)second.connection).resyncs>0 && ((RecordingConnection)second.connection).rejections>0,"Stale viewer did not receive inventory resync");
            // Accepted clicks rely on the initiating client's prediction; only the
            // rejected stale viewer requires an authoritative window resync.
            equal(first.openContainer.getSlot(6).getStack(),null,"First server container remained stale");
            equal(((RecordingConnection)second.connection).slots.get(6),null,"Rejected viewer source remained stale");conserved(before,b,a,c);
        }
    }
    private static void interleavedDrag(boolean swap) throws Exception {
        EntityLootableBody b=body();EntityPlayerMP a=player(),c=player();open(a,b);open(c,b);a.inventory.setItemStack(item(19,10));
        if(swap)c.inventory.mainInventory[0]=item(20,7);else c.inventory.setItemStack(item(20,7));Map<String,Integer> before=total(b,a,c);
        packet(a,-999,0,ClickType.QUICK_CRAFT,null);packet(a,6,1,ClickType.QUICK_CRAFT,null);packet(a,7,1,ClickType.QUICK_CRAFT,null);
        click(c,6,0,swap?ClickType.SWAP:ClickType.PICKUP);packet(a,-999,2,ClickType.QUICK_CRAFT,null);
        equal(b.getStackInSlot(6),item(20,7),"Other player's stack was overwritten by drag");equal(b.getStackInSlot(7),item(19,5),"Surviving drag slot differs");equal(a.inventory.getItemStack(),item(19,5),"Interrupted drag lost remainder");conserved(before,b,a,c);
    }
    private static void twoPlayerClose() throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,23));EntityPlayerMP a=player(),c=player();fill(a);open(a,b);open(c,b);Map<String,Integer> before=total(b,a,c);
        click(a,6,0,ClickType.PICKUP);packet(c,6,0,ClickType.QUICK_MOVE,item(19,23));RejectedDrops reject=new RejectedDrops();reject.join=true;MinecraftForge.EVENT_BUS.register(reject);
        try{close(a);}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        c.openContainer.detectAndSendChanges();conserved(before,b,a,c);
    }
    private static void stale(boolean removed) throws Exception {
        EntityLootableBody b=body();b.setInventorySlotContents(6,item(19,23));EntityPlayerMP p=player();open(p,b);Map<String,Integer> before=total(b,p);
        if(removed)b.setDead();else p.setPosition(200,80,200);
        packet(p,6,0,ClickType.QUICK_MOVE,null);equal(b.getStackInSlot(6),item(19,23),"Stale packet extracted before tick closed GUI");conserved(before,b,p);
    }
    private static void staleDrag() throws Exception {
        EntityLootableBody b=body();EntityPlayerMP p=player();open(p,b);p.inventory.setItemStack(item(19,10));Map<String,Integer> before=total(b,p);
        packet(p,-999,0,ClickType.QUICK_CRAFT,null);packet(p,6,1,ClickType.QUICK_CRAFT,null);p.setPosition(200,80,200);
        packet(p,-999,2,ClickType.QUICK_CRAFT,null);equal(b.getStackInSlot(6),null,"Distant drag still distributed items");
        p.setPosition(96.5,80,96.5);drag(p,0,7);equal(b.getStackInSlot(7),item(19,10),"New drag reused invalid selection");conserved(before,b,p);
    }
    private static final class RecordingConnection extends NetHandlerPlayServer {
        final Map<Integer,ItemStack> slots=new HashMap<>();int resyncs,rejections;
        RecordingConnection(EntityPlayerMP p){super(server,new NetworkManager(EnumPacketDirection.SERVERBOUND),p);}
        @Override public void sendPacket(Packet<?> packet){
            if(!(packet instanceof SPacketWindowItems || packet instanceof SPacketSetSlot || packet instanceof SPacketConfirmTransaction))return;
            PacketBuffer wire=new PacketBuffer(Unpooled.buffer());
            try{packet.writePacketData(wire);int window=wire.readByte();
                if(packet instanceof SPacketWindowItems){int n=wire.readShort();for(int i=0;i<n;i++)slots.put(i,wire.readItemStack());resyncs++;}
                else if(packet instanceof SPacketSetSlot){int slot=wire.readShort();ItemStack s=wire.readItemStack();if(window>=0)slots.put(slot,s);}
                else{wire.readShort();if(!wire.readBoolean())rejections++;}
            }catch(IOException error){throw new RuntimeException(error);}finally{wire.release();}
        }
    }
}
