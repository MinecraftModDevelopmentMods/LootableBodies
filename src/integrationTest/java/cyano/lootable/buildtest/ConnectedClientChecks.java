package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGameOver;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;

/** Real connected-player death, container packets and persisted recovery. */
final class ConnectedClientChecks {
    private interface Action { void run() throws Exception; }
    private int stage, frames;
    private Future<?> pending;
    private UUID playerId, corpseId;
    private NBTTagCompound visibleExpected;
    private File report;

    void start(Minecraft mc) {
        playerId=mc.player.getUniqueID();
        report=new File(mc.gameDir,"connected-checks.txt");
        RuntimeChecks.require(!report.exists(),"Refusing stale connected-client report");
    }
    boolean tick(Minecraft mc) throws Exception {
        frames++;
        if(stage==0) {
            schedule(mc,() -> prepareDeath(mc)); next(1);
        } else if(stage==1 && done() && mc.currentScreen instanceof GuiGameOver && frames>20) {
            screenshot(mc,"connected-player-death.png");
            mc.player.respawnPlayer(); next(2);
        } else if(stage==2 && mc.player!=null && mc.player.isAlive() && !(mc.currentScreen instanceof GuiGameOver)) {
            schedule(mc,() -> {
                EntityPlayerMP player=player(mc); EntityLootableBody corpse=corpse(mc);
                RuntimeChecks.require(player.isAlive(),"Connected respawn failed");
                player.connection.setPlayerLocation(24.5,80,7.5,0,0);
                RuntimeChecks.saveExpected(folder(mc),"connected-death-before",corpse);
                visibleExpected=read(folder(mc),"connected-death-before");
                mark("connected_player_death_and_respawn");
            }); next(3);
        } else if(stage==3 && done()) {
            if(open(mc)) next(4);
        } else if(stage==4 && gui(mc) && frames>20) {
            verifyGui(mc); screenshot(mc,"connected-corpse-before-recovery.png");
            RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(6).getStack(),RuntimeChecks.stack(19)),"Client recovery slot NBT differs");
            mc.playerController.windowClick(mc.player.openContainer.windowId,6,0,ClickType.QUICK_MOVE,mc.player);
            next(5);
        } else if(stage==5 && frames>30) {
            schedule(mc,() -> {
                verifyRecovery(mc,"connected-death-before",6,19,"connected-after-recovery");
                savePlayer(mc,"connected-player-after-recovery");
                mark("network_shift_click_exact_nbt");
            }); next(6);
        } else if(stage==6 && done()) {
            verifyGui(mc); checkPlayer(mc.player.inventory,19);
            screenshot(mc,"connected-corpse-after-shift-recovery.png");
            quit(mc); next(7);
        } else if(stage==7 && mc.world==null && !mc.isIntegratedServerRunning()) {
            mc.launchIntegratedServer("build-fixture","Build fixture",null); next(8);
        } else if(stage==8 && ready(mc) && frames>30) {
            schedule(mc,() -> {
                verifyReload(mc,"connected-after-recovery","connected-player-after-recovery");
                RuntimeChecks.require(player(mc).inventory.mainInventory.get(9).isEmpty(),"Normal-click destination not empty");
                mark("first_save_reload_corpse_and_player");
            }); next(9);
        } else if(stage==9 && done()) {
            if(open(mc)) next(10);
        } else if(stage==10 && gui(mc) && frames>20) {
            verifyGui(mc); checkPlayer(mc.player.inventory,19);
            screenshot(mc,"connected-corpse-first-reload.png");
            RuntimeChecks.require(RuntimeChecks.sameStack(mc.player.openContainer.getSlot(7).getStack(),RuntimeChecks.stack(20)),"Client normal-click slot NBT differs");
            int window=mc.player.openContainer.windowId;
            mc.playerController.windowClick(window,7,0,ClickType.PICKUP,mc.player);
            mc.playerController.windowClick(window,48,0,ClickType.PICKUP,mc.player);
            next(11);
        } else if(stage==11 && frames>30) {
            schedule(mc,() -> {
                verifyRecovery(mc,"connected-after-recovery",7,20,"connected-final");
                checkPlayer(player(mc).inventory,19);
                RuntimeChecks.require((player(mc).inventory.getItemStack()).isEmpty(),"Server cursor retained a duplicate");
                savePlayer(mc,"connected-player-final");
                mark("network_normal_click_exact_nbt");
            }); next(12);
        } else if(stage==12 && done()) {
            verifyGui(mc); checkPlayer(mc.player.inventory,19);checkPlayer(mc.player.inventory,20);
            RuntimeChecks.require((mc.player.inventory.getItemStack()).isEmpty(),"Client cursor retained a duplicate");
            screenshot(mc,"connected-corpse-after-normal-recovery.png");
            quit(mc); next(13);
        } else if(stage==13 && mc.world==null && !mc.isIntegratedServerRunning()) {
            mc.launchIntegratedServer("build-fixture","Build fixture",null); next(14);
        } else if(stage==14 && ready(mc) && frames>30) {
            schedule(mc,() -> {
                verifyReload(mc,"connected-final","connected-player-final");
                checkPlayer(player(mc).inventory,19);checkPlayer(player(mc).inventory,20);
                mark("second_save_reload_corpse_and_player");
            }); next(15);
        } else if(stage==15 && done()) {
            if(open(mc)) next(16);
        } else if(stage==16 && gui(mc) && frames>20) {
            verifyGui(mc); checkPlayer(mc.player.inventory,19);checkPlayer(mc.player.inventory,20);
            screenshot(mc,"connected-corpse-final-reload.png");
            System.out.println("LOOTABLE_BODIES_CONNECTED_CLIENT_PASS");
            return true;
        }
        return false;
    }
    private void prepareDeath(Minecraft mc) throws Exception {
        MinecraftServer server=mc.getIntegratedServer(); EntityPlayerMP player=player(mc);
        for(int x=21;x<=27;x++) for(int z=5;z<=11;z++)
            player.world.setBlockState(new BlockPos(x,79,z),Blocks.STONE.getDefaultState());
        player.world.getGameRules().setOrCreateGameRule("keepInventory","false",null);
        player.world.getGameRules().setOrCreateGameRule("doMobSpawning","false",null);
        player.setGameType(GameType.SURVIVAL); player.inventory.clear();player.inventory.currentItem=0;
        for(int slot=0;slot<6;slot++)player.setItemStackToSlot(EntityLootableBody.EQUIPMENT_SLOTS[slot],RuntimeChecks.stack(slot));
        for(int slot=9;slot<=11;slot++)player.inventory.mainInventory.set(slot,RuntimeChecks.stack(slot+10));
        player.connection.setPlayerLocation(24.5,80,8.5,0,0);
        player.inventoryContainer.detectAndSendChanges(); player.onKillCommand();
        RuntimeChecks.require(!player.isAlive(),"Connected player did not die");
        EntityLootableBody corpse=null;
        for(Entity entity:player.world.loadedEntityList)
            if(entity instanceof EntityLootableBody && player.getGameProfile().getName().equals(((EntityLootableBody)entity).getCustomNameTag())) corpse=(EntityLootableBody)entity;
        RuntimeChecks.require(corpse!=null,"Connected death did not create a corpse");
        corpseId=corpse.getUniqueID();corpse.setNoGravity(true);corpse.motionX=0;corpse.motionY=0;corpse.motionZ=0;
        RuntimeChecks.disableVacuum(corpse);
        for(int slot=0;slot<6;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getStackInSlot(slot),RuntimeChecks.stack(slot)),"Connected death equipment slot "+slot);
        for(int slot=6;slot<=8;slot++)RuntimeChecks.require(RuntimeChecks.sameStack(corpse.getStackInSlot(slot),RuntimeChecks.stack(slot+13)),"Connected death main slot "+slot);
        RuntimeChecks.require(player.inventory.write(new NBTTagList()).size()==0,"Death retained player inventory alongside corpse");
        System.out.println("CONNECTED_CORPSE_UUID "+corpseId);
    }
    private void verifyRecovery(Minecraft mc,String before,int slot,int item,String after) throws Exception {
        EntityLootableBody corpse=corpse(mc);
        RuntimeChecks.require((corpse.getStackInSlot(slot)).isEmpty(),"Network recovery left source stack");
        checkPlayer(player(mc).inventory,item);
        NBTTagCompound expected=read(folder(mc),before),actual=new NBTTagCompound();
        NBTTagList equipment=expected.getList("Equipment",10);
        for(int i=equipment.size()-1;i>=0;i--)if(equipment.getCompound(i).getByte("Slot")==slot)equipment.removeTag(i);
        corpse.writeUnlessPassenger(actual);RuntimeChecks.compare(expected,actual);
        RuntimeChecks.saveExpected(folder(mc),after,corpse);visibleExpected=actual.copy();
    }
    private void verifyReload(Minecraft mc,String corpseName,String playerName) throws Exception {
        mc.getIntegratedServer().getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getChunk(1,0);
        EntityLootableBody corpse=corpse(mc);RuntimeChecks.require(corpse.isAlive(),"Saved corpse is no longer accessible");
        corpse.baseTick();RuntimeChecks.verifyExpected(folder(mc),corpseName,corpse);
        NBTTagCompound expected=read(folder(mc),playerName);
        RuntimeChecks.require(expected.getTag("Inventory").equals(player(mc).inventory.write(new NBTTagList())),"Recovered player inventory changed on disk reload");
        player(mc).connection.setPlayerLocation(24.5,80,7.5,0,0);
        visibleExpected=read(folder(mc),corpseName);
    }
    private void savePlayer(Minecraft mc,String name) throws Exception {
        NBTTagCompound expected=new NBTTagCompound();expected.setTag("Inventory",player(mc).inventory.write(new NBTTagList()));
        try(FileOutputStream out=new FileOutputStream(new File(folder(mc),name+".nbt"))){CompressedStreamTools.writeCompressed(expected,out);}
        mc.getIntegratedServer().getPlayerList().saveAllPlayerData();mc.getIntegratedServer().saveAllWorlds(false);
    }
    private void verifyGui(Minecraft mc) {
        RuntimeChecks.require(gui(mc) && mc.player.openContainer.inventorySlots.size()==84,"Connected GUI missing slots");
        ItemStack[] expected=new ItemStack[48];NBTTagList list=visibleExpected.getList("Equipment",10);
        for(int i=0;i<list.size();i++){NBTTagCompound tag=list.getCompound(i);expected[tag.getByte("Slot")]=ItemStack.read(tag);}
        for(int i=0;i<48;i++)RuntimeChecks.require(RuntimeChecks.sameStack(expected[i],mc.player.openContainer.getSlot(i).getStack()),"Client GUI slot differs "+i);
    }
    private static void checkPlayer(net.minecraft.entity.player.InventoryPlayer inventory,int index) {
        ItemStack expected=RuntimeChecks.stack(index);int count=0;
        for(int i=0;i<inventory.getSizeInventory();i++){
            ItemStack stack=inventory.getStackInSlot(i);
            if((stack != null && !stack.isEmpty()) && stack.hasTag() && stack.getTag().equals(expected.getTag())) {
                RuntimeChecks.require(ItemStack.areItemsEqual(stack,expected),"Recovered item type/damage differs");count+=stack.getCount();
            }
        }
        RuntimeChecks.require(count==expected.getCount(),"Recovered stack lost/duplicated: "+index+" count="+count);
    }
    private EntityPlayerMP player(Minecraft mc){return mc.getIntegratedServer().getPlayerList().getPlayerByUUID(playerId);}
    private EntityLootableBody corpse(Minecraft mc){return RuntimeChecks.find(mc.getIntegratedServer().getWorld(net.minecraft.world.dimension.DimensionType.getById(0)),corpseId);}
    private File folder(Minecraft mc){return mc.getIntegratedServer().getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getSaveHandler().getWorldDirectory();}
    private static NBTTagCompound read(File folder,String name) throws Exception {
        try(FileInputStream in=new FileInputStream(new File(folder,name+".nbt"))){return CompressedStreamTools.readCompressed(in);}
    }
    private void schedule(Minecraft mc,Action action){pending=mc.getIntegratedServer().addScheduledTask(() -> {try{action.run();}catch(Exception error){throw new RuntimeException(error);}});}
    private boolean done() throws Exception {if(!pending.isDone())return false;pending.get();return true;}
    private void next(int value){stage=value;frames=0;System.out.println("CONNECTED_CLIENT_STAGE "+stage);}
    private static boolean ready(Minecraft mc){return mc.world!=null && mc.player!=null && mc.getIntegratedServer()!=null;}
    private static boolean gui(Minecraft mc){return mc.currentScreen instanceof CorpseGUIContainer;}
    private boolean open(Minecraft mc){
        for(Entity entity:mc.world.loadedEntityList)if(entity instanceof EntityLootableBody && entity.getUniqueID().equals(corpseId)){
            mc.playerController.interactWithEntity(mc.player,entity,EnumHand.MAIN_HAND);return true;
        }
        return false;
    }
    private static void screenshot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.gameDir,name,mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));}
    private static void quit(Minecraft mc){mc.player.closeScreen();FixtureConnections.disconnect(mc);}
    private void mark(String name) throws Exception {
        try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+name+"\n");}
        System.out.println("CLIENT_CHECK_PASS "+name);
    }
}
