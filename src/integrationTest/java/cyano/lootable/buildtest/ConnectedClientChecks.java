package cyano.lootable.buildtest;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/** Real connected-player death, container packets and persisted recovery. */
final class ConnectedClientChecks {
    private interface Action { void run() throws Exception; }
    private int stage, frames;
    private Future<?> pending;
    private UUID playerId, corpseId;
    private CompoundTag visibleExpected;
    private File report;

    void start(Minecraft mc) {
        playerId=mc.player.getUUID();
        report=new File(mc.gameDirectory,"connected-checks.txt");
        RuntimeChecks.require(!report.exists(),"Refusing stale connected-client report");
    }
    boolean tick(Minecraft mc) throws Exception {
        frames++;
        if(stage==0) {
            schedule(mc,() -> prepareDeath(mc)); next(1);
        } else if(stage==1 && done() && mc.screen instanceof DeathScreen && frames>20) {
            screenshot(mc,"connected-player-death.png");
            mc.player.respawn(); next(2);
        } else if(stage==2 && mc.player!=null && mc.player.isAlive() && !(mc.screen instanceof DeathScreen)) {
            schedule(mc,() -> {
                ServerPlayer player=player(mc); EntityLootableBody corpse=corpse(mc);
                RuntimeChecks.require(player.isAlive(),"Connected respawn failed");
                player.connection.teleport(24.5,80,7.5,0,0);
                RuntimeChecks.saveExpected(folder(mc),"connected-death-before",corpse);
                visibleExpected=read(folder(mc),"connected-death-before");
                mark("connected_player_death_and_respawn");
            }); next(3);
        } else if(stage==3 && done()) {
            if(open(mc)) next(4);
        } else if(stage==4 && gui(mc) && frames>20) {
            verifyGui(mc); screenshot(mc,"connected-corpse-before-recovery.png");
            RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(6).getItem(),RuntimeChecks.stack(19)),"Client recovery slot NBT differs");
            mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,6,0,ClickType.QUICK_MOVE,mc.player);
            next(5);
        } else if(stage==5 && frames>30) {
            schedule(mc,() -> {
                verifyRecovery(mc,"connected-death-before",6,19,"connected-after-recovery");
                savePlayer(mc,"connected-player-after-recovery");
                mark("network_shift_click_exact_nbt");
            }); next(6);
        } else if(stage==6 && done()) {
            verifyGui(mc); checkPlayer(mc.player.getInventory(),19);
            screenshot(mc,"connected-corpse-after-shift-recovery.png");
            quit(mc); next(7);
        } else if(stage==7 && FixtureConnections.stopped(mc)) {
            mc.loadLevel("build-fixture"); next(8);
        } else if(stage==8 && ready(mc) && frames>30) {
            schedule(mc,() -> {
                verifyReload(mc,"connected-after-recovery","connected-player-after-recovery");
                RuntimeChecks.require(player(mc).getInventory().items.get(9).isEmpty(),"Normal-click destination not empty");
                mark("first_save_reload_corpse_and_player");
            }); next(9);
        } else if(stage==9 && done()) {
            if(open(mc)) next(10);
        } else if(stage==10 && gui(mc) && frames>20) {
            verifyGui(mc); checkPlayer(mc.player.getInventory(),19);
            screenshot(mc,"connected-corpse-first-reload.png");
            RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.containerMenu.getSlot(7).getItem(),RuntimeChecks.stack(20)),"Client normal-click slot NBT differs");
            int window=mc.player.containerMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(window,7,0,ClickType.PICKUP,mc.player);
            mc.gameMode.handleInventoryMouseClick(window,48,0,ClickType.PICKUP,mc.player);
            next(11);
        } else if(stage==11 && frames>30) {
            schedule(mc,() -> {
                verifyRecovery(mc,"connected-after-recovery",7,20,"connected-final");
                checkPlayer(player(mc).getInventory(),19);
                RuntimeChecks.require(player(mc).containerMenu.getCarried().isEmpty(),"Server cursor retained a duplicate");
                savePlayer(mc,"connected-player-final");
                mark("network_normal_click_exact_nbt");
            }); next(12);
        } else if(stage==12 && done()) {
            verifyGui(mc); checkPlayer(mc.player.getInventory(),19);checkPlayer(mc.player.getInventory(),20);
            RuntimeChecks.require((mc.player.containerMenu.getCarried()).isEmpty(),"Client cursor retained a duplicate");
            screenshot(mc,"connected-corpse-after-normal-recovery.png");
            quit(mc); next(13);
        } else if(stage==13 && FixtureConnections.stopped(mc)) {
            mc.loadLevel("build-fixture"); next(14);
        } else if(stage==14 && ready(mc) && frames>30) {
            schedule(mc,() -> {
                verifyReload(mc,"connected-final","connected-player-final");
                checkPlayer(player(mc).getInventory(),19);checkPlayer(player(mc).getInventory(),20);
                mark("second_save_reload_corpse_and_player");
            }); next(15);
        } else if(stage==15 && done()) {
            if(open(mc)) next(16);
        } else if(stage==16 && gui(mc) && frames>20) {
            verifyGui(mc); checkPlayer(mc.player.getInventory(),19);checkPlayer(mc.player.getInventory(),20);
            screenshot(mc,"connected-corpse-final-reload.png");
            System.out.println("LOOTABLE_BODIES_CONNECTED_CLIENT_PASS");
            return true;
        }
        return false;
    }
    private void prepareDeath(Minecraft mc) throws Exception {
        MinecraftServer server=mc.getSingleplayerServer(); ServerPlayer player=player(mc);
        for(int x=21;x<=27;x++) for(int z=5;z<=11;z++)
            player.level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
        player.level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
        player.level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,null);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent();player.getInventory().selected=0;
        for(int slot=0;slot<6;slot++)player.setItemSlot(EntityLootableBody.EQUIPMENT_SLOTS[slot],RuntimeChecks.stack(slot));
        for(int slot=9;slot<=11;slot++)player.getInventory().items.set(slot,RuntimeChecks.stack(slot+10));
        player.connection.teleport(24.5,80,8.5,0,0);
        player.containerMenu.broadcastChanges(); player.kill();
        RuntimeChecks.require(!player.isAlive(),"Connected player did not die");
        EntityLootableBody corpse=null;
        for(Entity entity:FixtureApi.entities(player.level))
            if(entity instanceof EntityLootableBody && player.getGameProfile().getName().equals(((EntityLootableBody)entity).getCustomNameTag())) corpse=(EntityLootableBody)entity;
        RuntimeChecks.require(corpse!=null,"Connected death did not create a corpse");
        corpseId=corpse.getUUID();corpse.setNoGravity(true);corpse.setNoAi(true);corpse.setDeltaMovement(0,corpse.getDeltaMovement().y,corpse.getDeltaMovement().z);corpse.setDeltaMovement(corpse.getDeltaMovement().x,0,corpse.getDeltaMovement().z);corpse.setDeltaMovement(corpse.getDeltaMovement().x,corpse.getDeltaMovement().y,0);
        RuntimeChecks.disableVacuum(corpse);
        for(int slot=0;slot<6;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getItem(slot),RuntimeChecks.stack(slot)),"Connected death equipment slot "+slot);
        for(int slot=6;slot<=8;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getItem(slot),RuntimeChecks.stack(slot+13)),"Connected death main slot "+slot);
        RuntimeChecks.require(player.getInventory().save(new ListTag()).size()==0,"Death retained player inventory alongside corpse");
        System.out.println("CONNECTED_CORPSE_UUID "+corpseId);
    }
    private void verifyRecovery(Minecraft mc,String before,int slot,int item,String after) throws Exception {
        EntityLootableBody corpse=corpse(mc);
        RuntimeChecks.require((corpse.getItem(slot)).isEmpty(),"Network recovery left source stack");
        checkPlayer(player(mc).getInventory(),item);
        CompoundTag expected=read(folder(mc),before),actual=new CompoundTag();
        ListTag equipment=expected.getList("Equipment",10);
        for(int i=equipment.size()-1;i>=0;i--)if(equipment.getCompound(i).getByte("Slot")==slot)equipment.remove(i);
        corpse.saveAsPassenger(actual);RuntimeChecks.compare(expected,actual);
        RuntimeChecks.saveExpected(folder(mc),after,corpse);visibleExpected=actual.copy();
    }
    private void verifyReload(Minecraft mc,String corpseName,String playerName) throws Exception {
        RuntimeChecks.awaitChunk(mc.getSingleplayerServer().getLevel(FixtureApi.dimensionKey(0)),1,0);
        EntityLootableBody corpse=corpse(mc);RuntimeChecks.require(corpse.isAlive(),"Saved corpse is no longer accessible");
        corpse.baseTick();RuntimeChecks.verifyExpected(folder(mc),corpseName,corpse);
        CompoundTag expected=read(folder(mc),playerName);
        RuntimeChecks.require(expected.get("Inventory").equals(player(mc).getInventory().save(new ListTag())),"Recovered player inventory changed on disk reload");
        player(mc).connection.teleport(24.5,80,7.5,0,0);
        visibleExpected=read(folder(mc),corpseName);
    }
    private void savePlayer(Minecraft mc,String name) throws Exception {
        CompoundTag expected=new CompoundTag();expected.put("Inventory",player(mc).getInventory().save(new ListTag()));
        try(FileOutputStream out=new FileOutputStream(new File(folder(mc),name+".nbt"))){NbtIo.writeCompressed(expected,out);}
        mc.getSingleplayerServer().getPlayerList().saveAll();mc.getSingleplayerServer().saveAllChunks(false,true,false);
    }
    private void verifyGui(Minecraft mc) {
        RuntimeChecks.require(gui(mc) && mc.player.containerMenu.slots.size()==84,"Connected GUI missing slots");
        ItemStack[] expected=new ItemStack[48];ListTag list=visibleExpected.getList("Equipment",10);
        for(int i=0;i<list.size();i++){CompoundTag tag=list.getCompound(i);expected[tag.getByte("Slot")]=ItemStack.of(tag);}
        for(int i=0;i<48;i++)RuntimeChecks.require(RuntimeChecks.sameStack(expected[i],mc.player.containerMenu.getSlot(i).getItem()),"Client GUI slot differs "+i);
    }
    private static void checkPlayer(net.minecraft.world.entity.player.Inventory inventory,int index) {
        ItemStack expected=RuntimeChecks.stack(index);int count=0;
        for(int i=0;i<inventory.getContainerSize();i++){
            ItemStack stack=inventory.getItem(i);
            if((stack != null && !stack.isEmpty()) && stack.hasTag() && stack.getTag().equals(expected.getTag())) {
                RuntimeChecks.require(ItemStack.isSame(stack,expected),"Recovered item type/damage differs");count+=stack.getCount();
            }
        }
        RuntimeChecks.require(count==expected.getCount(),"Recovered stack lost/duplicated: "+index+" count="+count);
    }
    private ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);}
    private EntityLootableBody corpse(Minecraft mc){return RuntimeChecks.find(mc.getSingleplayerServer().getLevel(FixtureApi.dimensionKey(0)),corpseId);}
    private File folder(Minecraft mc){return mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();}
    private static CompoundTag read(File folder,String name) throws Exception {
        try(FileInputStream in=new FileInputStream(new File(folder,name+".nbt"))){return NbtIo.readCompressed(in);}
    }
    private void schedule(Minecraft mc,Action action){pending=mc.getSingleplayerServer().submitAsync(() -> {try{action.run();}catch(Exception error){throw new RuntimeException(error);}});}
    private boolean done() throws Exception {if(!pending.isDone())return false;pending.get();return true;}
    private void next(int value){stage=value;frames=0;System.out.println("CONNECTED_CLIENT_STAGE "+stage);}
    private static boolean ready(Minecraft mc){return mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null;}
    private static boolean gui(Minecraft mc){return mc.screen instanceof CorpseGUIContainer;}
    private boolean open(Minecraft mc){
        for(Entity entity:FixtureApi.entities(mc.level))if(entity instanceof EntityLootableBody && entity.getUUID().equals(corpseId)){
            mc.gameMode.interact(mc.player,entity,InteractionHand.MAIN_HAND);return true;
        }
        return false;
    }
    private static void screenshot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->System.out.println(message.getString()));}
    private static void quit(Minecraft mc){mc.player.closeContainer();FixtureConnections.disconnect(mc);}
    private void mark(String name) throws Exception {
        try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+name+"\n");}
        System.out.println("CLIENT_CHECK_PASS "+name);
    }
}
