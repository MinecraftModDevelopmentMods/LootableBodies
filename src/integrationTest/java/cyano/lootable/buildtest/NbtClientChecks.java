package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameType;

/** Real GUI recovery after loading malformed NBT, including unresolved data across another save. */
final class NbtClientChecks {
    private interface Action {void run() throws Exception;}
    private final UUID bodyId=UUID.fromString("00000000-0000-4000-8000-000000000121");
    private UUID playerId;
    private int stage,frames;
    private Future<?> pending;
    private NBTTagCompound visible;
    private Map<String,Integer> before;
    private NBTTagList expectedPlayer;
    void start(Minecraft mc){playerId=mc.player.getUniqueID();}
    boolean tick(Minecraft mc) throws Exception {
        frames++;RuntimeChecks.require(frames<300,"NBT graphical stage timeout "+stage);
        if(stage==0){schedule(mc,()->{
            EntityPlayerMP p=player(mc);p.inventory.clear();p.inventory.setItemStack(ItemStack.EMPTY);p.setGameType(GameType.SURVIVAL);
            for(int x=157;x<=171;x++)for(int z=157;z<=163;z++)p.world.setBlockState(new BlockPos(x,79,z),Blocks.STONE.getDefaultState());
            NbtWorldChecks.seed(p.getServerWorld(),bodyId,160,folder(mc),"graphical");
        });next(1);}
        else if(stage==1 && done() && frames>20){quit(mc);next(2);}
        else if(stage==2 && mc.world==null && !mc.isIntegratedServerRunning()){mc.launchIntegratedServer("build-fixture","Build fixture",null);next(3);}
        else if(stage==3 && ready(mc) && frames>20){schedule(mc,()->player(mc).connection.setPlayerLocation(168.5,80,159.5,0,0));next(30);}
        else if(stage==30 && done() && frames>20){schedule(mc,()->{
            EntityLootableBody b=body(mc);for(int i=0;i<8;i++)b.onEntityUpdate();NbtWorldChecks.verify(b,false);
            before=NbtRecoveryChecks.items(b);visible=full(b);RuntimeChecks.saveExpected(folder(mc),"nbt-client-before",b);
        });next(4);}
        else if(stage==4 && done() && frames>20){if(open(mc))next(5);}
        else if(stage==5 && gui(mc) && frames>20){verifyGui(mc);equal(mc.player.openContainer.getSlot(6).getStack(),NbtRecoveryChecks.item(120,17),"Malformed GUI recovery source differs");screenshot(mc,"nbt-recovered-corpse.png");mark(mc,"malformed_disk_load_gui_exact_inventory");mc.playerController.windowClick(mc.player.openContainer.windowId,6,0,ClickType.QUICK_MOVE,mc.player);next(6);}
        else if(stage==6 && frames>25){schedule(mc,()->{
            Map<String,Integer> after=NbtRecoveryChecks.items(body(mc));for(int i=0;i<player(mc).inventory.getSizeInventory();i++)add(after,player(mc).inventory.getStackInSlot(i));RuntimeChecks.require(before.equals(after),"Network recovery lost/duplicated malformed corpse contents");
            checkPlayer(player(mc).inventory);RuntimeChecks.require(Objects.equals(visible.getTag(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(body(mc)).getTag(NbtRecoveryChecks.RECOVERY)),"Network extraction changed unresolved raw NBT");
            NbtWorldChecks.verifyMetadata(body(mc));
            for(int i=0;i<8;i++)body(mc).onEntityUpdate();visible=full(body(mc));expectedPlayer=player(mc).inventory.writeToNBT(new NBTTagList());RuntimeChecks.saveExpected(folder(mc),"nbt-client-after",body(mc));
            NBTTagCompound n=new NBTTagCompound();n.setTag("Inventory",expectedPlayer.copy());try(FileOutputStream out=new FileOutputStream(new File(folder(mc),"nbt-client-player.nbt"))){CompressedStreamTools.writeCompressed(n,out);}
            mc.getIntegratedServer().getPlayerList().saveAllPlayerData();mc.getIntegratedServer().saveAllWorlds(false);mark(mc,"malformed_network_recovery_preserves_raw_records");
        });next(7);}
        else if(stage==7 && done()){checkPlayer(mc.player.inventory);quit(mc);next(8);}
        else if(stage==8 && mc.world==null && !mc.isIntegratedServerRunning()){mc.launchIntegratedServer("build-fixture","Build fixture",null);next(9);}
        else if(stage==9 && ready(mc) && frames>20){schedule(mc,()->player(mc).connection.setPlayerLocation(168.5,80,159.5,0,0));next(90);}
        else if(stage==90 && done() && frames>20){schedule(mc,()->{
            body(mc).onEntityUpdate();RuntimeChecks.verifyExpected(folder(mc),"nbt-client-after",body(mc));
            RuntimeChecks.require(Objects.equals(visible.getTag(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(body(mc)).getTag(NbtRecoveryChecks.RECOVERY)),"Unresolved raw data changed on graphical disk reload");
            NbtWorldChecks.verifyMetadata(body(mc));RuntimeChecks.require(NameLifetimeChecks.same(visible.getTag(NameLifetimeChecks.RECOVERY),full(body(mc)).getTag(NameLifetimeChecks.RECOVERY)),"Raw scalar evidence changed on graphical reload");
            RuntimeChecks.require(expectedPlayer.equals(player(mc).inventory.writeToNBT(new NBTTagList())),"Recovered player NBT changed on graphical disk reload");checkPlayer(player(mc).inventory);mark(mc,"malformed_corpse_and_player_disk_reload");
        });next(10);}
        else if(stage==10 && done() && frames>20){if(open(mc))next(11);}
        else if(stage==11 && gui(mc) && frames>20){verifyGui(mc);checkPlayer(mc.player.inventory);screenshot(mc,"nbt-after-recovery-reload.png");System.out.println("LOOTABLE_BODIES_NBT_CLIENT_PASS");return true;}
        if((stage==4 || stage==5 || stage==10 || stage==11) && !gui(mc) && frames%10==0) {
            open(mc);
            if(frames==60) {
                System.out.println("NBT_GUI_CLIENT_STATE position="+mc.player.getPosition()+" container="+mc.player.openContainer.getClass().getSimpleName());
                for(Entity entity:mc.world.loadedEntityList)if(entity instanceof EntityLootableBody)System.out.println("NBT_GUI_CLIENT_BODY uuid="+entity.getUniqueID()+" position="+entity.getPosition()+" id="+entity.getEntityId());
                schedule(mc,()->System.out.println("NBT_GUI_SERVER_STATE player="+player(mc).getPosition()+" body="+body(mc).getPosition()+" usable="+body(mc).isUsableByPlayer(player(mc))+" alive="+body(mc).isEntityAlive()+" health="+body(mc).getHealth()+" container="+player(mc).openContainer.getClass().getSimpleName()));
            }
        }
        return false;
    }
    private void verifyGui(Minecraft mc){ItemStack[] expected=new ItemStack[48];NBTTagList list=visible.getTagList("Equipment",10);for(int i=0;i<list.tagCount();i++){NBTTagCompound n=list.getCompoundTagAt(i);expected[n.getByte("Slot")]=new ItemStack(n);}for(int i=0;i<48;i++)equal(mc.player.openContainer.getSlot(i).getStack(),expected[i],"Malformed corpse GUI slot "+i);}
    private static void checkPlayer(net.minecraft.entity.player.InventoryPlayer p){Map<String,Integer> actual=new TreeMap<>();for(int i=0;i<p.getSizeInventory();i++)add(actual,p.getStackInSlot(i));Map<String,Integer> expected=new TreeMap<>();add(expected,NbtRecoveryChecks.item(120,17));RuntimeChecks.require(actual.equals(expected),"Malformed GUI recovered player stack changed");}
    private static void add(Map<String,Integer> map,ItemStack s){if((s == null || s.isEmpty()) || s.getCount()<=0)return;NBTTagCompound n=s.writeToNBT(new NBTTagCompound());n.removeTag("Count");String key=n.toString();map.put(key,map.getOrDefault(key,0)+s.getCount());}
    private static void equal(ItemStack a,ItemStack b,String why){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),why);}
    private static NBTTagCompound full(EntityLootableBody b){NBTTagCompound n=new NBTTagCompound();b.writeToNBTOptional(n);return n;}
    private EntityPlayerMP player(Minecraft mc){return mc.getIntegratedServer().getPlayerList().getPlayerByUUID(playerId);}
    private EntityLootableBody body(Minecraft mc){return RuntimeChecks.find(mc.getIntegratedServer().getWorld(0),bodyId);}
    private File folder(Minecraft mc){return player(mc).world.getSaveHandler().getWorldDirectory();}
    private static boolean ready(Minecraft mc){return mc.world!=null && mc.player!=null && mc.getIntegratedServer()!=null;}
    private static boolean gui(Minecraft mc){return mc.currentScreen instanceof CorpseGUIContainer;}
    private boolean open(Minecraft mc){for(Entity e:mc.world.loadedEntityList)if(e.getUniqueID().equals(bodyId)){RuntimeChecks.require(e.getCustomNameTag().equals("NbtFixture"),"Malformed name reached tracked client entity");RuntimeChecks.require(e instanceof EntityLootableBody && Float.isFinite(((EntityLootableBody)e).getHealth()) && ((EntityLootableBody)e).getHealth()>0,"Malformed health reached tracked client entity");RuntimeChecks.require(((EntityLootableBody)e).getActivePotionEffects().isEmpty() && ((EntityLootableBody)e).hurtTime==0,"Malformed effect/hurt state reached tracked client entity");PositionMotionChecks.finite((EntityLootableBody)e);RotationFallChecks.finite((EntityLootableBody)e);mc.playerController.interactWithEntity(mc.player,e,EnumHand.MAIN_HAND);return true;}return false;}
    private void schedule(Minecraft mc,Action action){pending=mc.getIntegratedServer().addScheduledTask(()->{try{action.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean done() throws Exception{if(!pending.isDone())return false;pending.get();return true;}
    private void next(int value){stage=value;frames=0;System.out.println("NBT_CLIENT_STAGE "+stage);}
    private static void screenshot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.gameDir,name,mc.displayWidth,mc.displayHeight,mc.getFramebuffer());}
    private static void quit(Minecraft mc){mc.player.closeScreen();mc.getIntegratedServer().initiateShutdown();}
    private static void mark(Minecraft mc,String name) throws Exception{try(FileWriter out=new FileWriter(new File(mc.gameDir,"nbt-client-checks.txt"),true)){out.write("PASS "+name+"\n");}System.out.println("NBT_CLIENT_CHECK_PASS "+name);}
}
