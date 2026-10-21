package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.play.client.CPacketHeldItemChange;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;

/** Exact issue5 sequence over a real connected client and two disk reloads. */
final class ReportedIssueClientChecks {
    private interface Action {void run() throws Exception;}
    private int stage,frames,active;
    private Future<?> pending;
    private UUID firstId,secondId;
    private File report;
    private NBTTagCompound savedBody,savedPlayer;
    static ItemStack original() {
        ItemStack s=new ItemStack(Items.DIAMOND_SHOVEL,1,17);
        s.setTagCompound(RuntimeChecks.stack(300).getTagCompound().copy());
        s.setStackDisplayName("Issue 5 original shovel");return s;
    }
    void tick(Minecraft mc) throws Exception {
        frames++;
        if(stage==0 && mc.currentScreen instanceof GuiMainMenu) {
            report=new File(mc.mcDataDir,"issue5-client-checks.txt");RuntimeChecks.require(!report.exists(),"Stale issue5 report");
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;mc.gameSettings.renderDistanceChunks=4;
            mc.launchIntegratedServer("issue-fixture","Issue fixture",new WorldSettings(4267321,GameType.SURVIVAL,false,false,WorldType.FLAT).enableCommands());next(1);
        } else if(stage==1 && ready(mc)) {
            schedule(mc,()->{
                WorldServer w=player(mc).getServerWorld();w.getGameRules().setOrCreateGameRule("keepInventory","false");
                w.getGameRules().setOrCreateGameRule("doMobSpawning","false");
                for(int x=29;x<=36;x++)for(int z=-3;z<=4;z++)w.setBlockState(new BlockPos(x,79,z),Blocks.STONE.getDefaultState());
                EntityPlayerMP p=player(mc);p.setGameType(GameType.SURVIVAL);p.inventory.clear();p.inventory.currentItem=4;
                p.inventory.mainInventory[4]=original();p.inventory.mainInventory[9]=RuntimeChecks.stack(301);
                p.connection.setPlayerLocation(32.5,80,.5,0,0);p.inventoryContainer.detectAndSendChanges();
                firstId=die(p,null).getUniqueID();
            });next(2);
        } else if(stage==2 && done() && mc.currentScreen instanceof GuiGameOver && frames>10) {
            mc.player.respawnPlayer();next(3);
        } else if(stage==3 && ready(mc) && mc.player.isEntityAlive() && !(mc.currentScreen instanceof GuiGameOver)) {
            schedule(mc,()->player(mc).connection.setPlayerLocation(32.5,80,.5,0,0));next(4);
        } else if(stage==4 && done() && open(mc,firstId)) {next(5);
        } else if(stage==5 && gui(mc) && frames>15) {
            equal(mc.player.openContainer.getSlot(4).getStack(),original(),"First corpse main hand");shot(mc,"issue5-first-main-hand.png");
            mc.playerController.windowClick(mc.player.openContainer.windowId,4,0,ClickType.SWAP,mc.player);next(6);
        } else if(stage==6 && frames>15) {
            equal(mc.player.inventory.mainInventory[0],original(),"Key1 original shovel recovery");
            for(int slot=0;slot<48;slot++)if(mc.player.openContainer.getSlot(slot).getHasStack())
                mc.playerController.windowClick(mc.player.openContainer.windowId,slot,0,ClickType.QUICK_MOVE,mc.player);
            next(7);
        } else if(stage==7 && frames>20) {
            equal(mc.player.inventory.mainInventory[0],original(),"Original shovel changed before switch");
            active=-1;for(int slot=1;slot<9;slot++)if(ItemStack.areItemStacksEqual(mc.player.inventory.mainInventory[slot],RuntimeChecks.stack(301)))active=slot;
            RuntimeChecks.require(active>0,"No different recovered hotbar item");
            mark("first_death_respawn_key1_and_shift_recovery");
            mc.player.closeScreen();mc.player.inventory.currentItem=active;
            mc.getConnection().sendPacket(new CPacketHeldItemChange(active));
            // Same ordered connection: held-item change, then /kill. No inventory open or forced resync.
            mc.player.sendChatMessage("/kill");next(8);
        } else if(stage==8 && mc.currentScreen instanceof GuiGameOver && frames>10) {
            mc.player.respawnPlayer();next(9);
        } else if(stage==9 && ready(mc) && mc.player.isEntityAlive() && !(mc.currentScreen instanceof GuiGameOver)) {
            schedule(mc,()->{
                EntityPlayerMP p=player(mc);EntityLootableBody second=null;
                for(Entity e:p.world.loadedEntityList)if(e instanceof EntityLootableBody && !e.getUniqueID().equals(firstId) && p.getName().equals(e.getCustomNameTag()))second=(EntityLootableBody)e;
                RuntimeChecks.require(second!=null,"Second actual death corpse absent");secondId=second.getUniqueID();
                verifyOriginals(second);equal(second.getStackInSlot(4),RuntimeChecks.stack(301),"Second selected main hand");
                RuntimeChecks.require(p.inventory.writeToNBT(new NBTTagList()).tagCount()==0,"Second death duplicated player inventory");
                p.connection.setPlayerLocation(32.5,80,.5,0,0);
                mark("second_death_without_inventory_resync_preserves_original_shovel_and_all_tags");
            });next(10);
        } else if(stage==10 && done() && open(mc,secondId)) {next(11);
        } else if(stage==11 && gui(mc) && frames>20) {
            verifyClientOriginals(mc);shot(mc,"issue5-second-corpse.png");
            mc.player.closeScreen();schedule(mc,()->save(mc));next(12);
        } else if(stage==12 && done()) {quit(mc);next(13);
        } else if(stage==13 && mc.world==null && !mc.isIntegratedServerRunning()) {
            mc.launchIntegratedServer("issue-fixture","Issue fixture",null);next(14);
        } else if(stage==14 && ready(mc) && frames>25) {
            schedule(mc,()->{verifyDisk(mc);verifyOriginals(body(mc));mark("first_disk_reload_complete_corpse_inventory");});next(15);
        } else if(stage==15 && done() && open(mc,secondId)) {next(16);
        } else if(stage==16 && gui(mc) && frames>20) {
            verifyClientOriginals(mc);shot(mc,"issue5-reloaded-corpse.png");
            for(int slot=0;slot<48;slot++)if(mc.player.openContainer.getSlot(slot).getHasStack())
                mc.playerController.windowClick(mc.player.openContainer.windowId,slot,0,ClickType.QUICK_MOVE,mc.player);
            next(17);
        } else if(stage==17 && frames>20) {
            mc.player.closeScreen();schedule(mc,()->{
                RuntimeChecks.require(body(mc).isEmpty(),"Final recovery left duplicate corpse items");
                Map<String,Integer> expected=originals(),actual=new TreeMap<>();
                EntityPlayerMP p=player(mc);
                for(int i=0;i<p.inventory.getSizeInventory();i++)addTagged(actual,p.inventory.getStackInSlot(i));
                RuntimeChecks.require(expected.equals(actual),"Final player recovery lost or duplicated original tags");
                save(mc);mark("second_corpse_recovery_exact_counts_damage_and_nested_nbt");
            });next(18);
        } else if(stage==18 && done()) {quit(mc);next(19);
        } else if(stage==19 && mc.world==null && !mc.isIntegratedServerRunning()) {
            mc.launchIntegratedServer("issue-fixture","Issue fixture",null);next(20);
        } else if(stage==20 && ready(mc) && frames>25) {
            schedule(mc,()->{verifyDisk(mc);mark("second_disk_reload_complete_player_and_corpse_inventory");});next(21);
        } else if(stage==21 && done()) {
            new File(mc.mcDataDir,"client.pass").createNewFile();
            System.out.println("LOOTABLE_BODIES_ISSUE5_CLIENT_PASS");quit(mc);next(22);
        } else if(stage==22 && mc.world==null && !mc.isIntegratedServerRunning()) {mc.shutdown();next(23);}
    }
    private EntityLootableBody die(EntityPlayerMP p,UUID skip) {
        Set<Entity> before=new HashSet<>(p.world.loadedEntityList);p.onKillCommand();
        for(Entity e:p.world.loadedEntityList)if(e instanceof EntityLootableBody && !before.contains(e))return (EntityLootableBody)e;
        throw new AssertionError("Actual connected death absent");
    }
    private static void addTagged(Map<String,Integer> result,ItemStack stack) {
        if(stack==null || !stack.hasTagCompound() || !stack.getTagCompound().hasKey("fixture"))return;
        NBTTagCompound n=stack.writeToNBT(new NBTTagCompound());n.removeTag("Count");result.put(n.toString(),result.getOrDefault(n.toString(),0)+stack.stackSize);
    }
    private static Map<String,Integer> originals(){Map<String,Integer> m=new TreeMap<>();addTagged(m,original());addTagged(m,RuntimeChecks.stack(301));return m;}
    private static void verifyOriginals(EntityLootableBody b) {
        Map<String,Integer> n=new TreeMap<>(ReportedIssueChecks.inventory(b));n.keySet().removeIf(k->!k.contains("fixture"));
        RuntimeChecks.require(originals().equals(n),"Second corpse lost/duplicated exact tagged inventory");
    }
    private static void verifyClientOriginals(Minecraft mc){Map<String,Integer> n=new TreeMap<>();for(int i=0;i<48;i++)addTagged(n,mc.player.openContainer.getSlot(i).getStack());RuntimeChecks.require(originals().equals(n),"Connected GUI differs from exact originals");}
    private void save(Minecraft mc) throws Exception {
        savedBody=new NBTTagCompound();body(mc).writeToNBTOptional(savedBody);savedPlayer=new NBTTagCompound();savedPlayer.setTag("Inventory",player(mc).inventory.writeToNBT(new NBTTagList()));
        File folder=player(mc).getServerWorld().getSaveHandler().getWorldDirectory();
        try(FileOutputStream out=new FileOutputStream(new File(folder,"issue5-corpse-expected.nbt"))){CompressedStreamTools.writeCompressed(savedBody,out);}
        try(FileOutputStream out=new FileOutputStream(new File(folder,"issue5-player-expected.nbt"))){CompressedStreamTools.writeCompressed(savedPlayer,out);}
        mc.getIntegratedServer().getPlayerList().saveAllPlayerData();mc.getIntegratedServer().saveAllWorlds(false);
    }
    private void verifyDisk(Minecraft mc) {
        player(mc).getServerWorld().getChunkFromChunkCoords(2,0);NBTTagCompound actual=new NBTTagCompound();body(mc).writeToNBTOptional(actual);
        for(String key:Arrays.asList("id","UUIDMost","UUIDLeast","Equipment","Aux","Name"))RuntimeChecks.require(Objects.equals(savedBody.getTag(key),actual.getTag(key)),"Disk corpse differs: "+key);
        RuntimeChecks.require(savedPlayer.getTagList("Inventory",10).equals(player(mc).inventory.writeToNBT(new NBTTagList())),"Complete player inventory differs after disk reload");
    }
    private EntityPlayerMP player(Minecraft mc){return mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID());}
    private EntityLootableBody body(Minecraft mc){return RuntimeChecks.find(player(mc).getServerWorld(),secondId);}
    private static void equal(ItemStack a,ItemStack b,String reason){RuntimeChecks.require(ItemStack.areItemStacksEqual(a,b),reason);}
    private static boolean ready(Minecraft mc){return mc.world!=null && mc.player!=null && mc.getIntegratedServer()!=null;}
    private static boolean gui(Minecraft mc){return mc.currentScreen instanceof CorpseGUIContainer;}
    private static boolean open(Minecraft mc,UUID id){for(Entity e:mc.world.loadedEntityList)if(e instanceof EntityLootableBody && e.getUniqueID().equals(id)){mc.playerController.interactWithEntity(mc.player,e,null,EnumHand.MAIN_HAND);return true;}return false;}
    private void schedule(Minecraft mc,Action a){pending=mc.getIntegratedServer().addScheduledTask(()->{try{a.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean done() throws Exception {if(!pending.isDone())return false;pending.get();return true;}
    private void next(int n){stage=n;frames=0;System.out.println("ISSUE5_CLIENT_STAGE "+n);}
    private void mark(String name) throws Exception {try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+name+"\n");}System.out.println("ISSUE5_CLIENT_CHECK_PASS "+name);}
    private static void shot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.mcDataDir,name,mc.displayWidth,mc.displayHeight,mc.getFramebuffer());}
    private static void quit(Minecraft mc){mc.player.closeScreen();mc.getIntegratedServer().initiateShutdown();}
}
