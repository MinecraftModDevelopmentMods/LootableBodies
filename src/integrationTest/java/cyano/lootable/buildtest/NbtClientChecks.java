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
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;

/** Real GUI recovery after loading malformed NBT, including unresolved data across another save. */
final class NbtClientChecks {
    private interface Action {void run() throws Exception;}
    private final UUID bodyId=UUID.fromString("00000000-0000-4000-8000-000000000121");
    private UUID playerId;
    private int stage,frames;
    private Future<?> pending;
    private CompoundTag visible;
    private Map<String,Integer> before;
    private ListTag expectedPlayer;
    void start(Minecraft mc){playerId=mc.player.getUUID();}
    boolean tick(Minecraft mc) throws Exception {
        frames++;RuntimeChecks.require(frames<300,"NBT graphical stage timeout "+stage);
        if(stage==0){schedule(mc,()->{
            ServerPlayer p=player(mc);p.getInventory().clearContent();p.containerMenu.setCarried(ItemStack.EMPTY);p.setGameMode(GameType.SURVIVAL);
            for(int x=157;x<=171;x++)for(int z=157;z<=163;z++)p.level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
            NbtWorldChecks.seed(p.getLevel(),bodyId,160,folder(mc),"graphical");
        });next(1);}
        else if(stage==1 && done() && frames>20){quit(mc);next(2);}
        else if(stage==2 && FixtureConnections.stopped(mc)){mc.loadLevel("build-fixture");next(3);}
        else if(stage==3 && ready(mc) && frames>20){schedule(mc,()->player(mc).connection.teleport(168.5,80,159.5,0,0));next(30);}
        else if(stage==30 && done() && frames>20){schedule(mc,()->{
            EntityLootableBody b=body(mc);for(int i=0;i<8;i++)b.baseTick();NbtWorldChecks.verify(b,false);
            before=NbtRecoveryChecks.items(b);visible=full(b);RuntimeChecks.saveExpected(folder(mc),"nbt-client-before",b);
        });next(4);}
        else if(stage==4 && done() && frames>20){if(open(mc))next(5);}
        else if(stage==5 && gui(mc) && frames>20){verifyGui(mc);equal(mc.player.containerMenu.getSlot(6).getItem(),NbtRecoveryChecks.item(120,17),"Malformed GUI recovery source differs");screenshot(mc,"nbt-recovered-corpse.png");mark(mc,"malformed_disk_load_gui_exact_inventory");mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,6,0,ClickType.QUICK_MOVE,mc.player);next(6);}
        else if(stage==6 && frames>25){schedule(mc,()->{
            Map<String,Integer> after=NbtRecoveryChecks.items(body(mc));for(int i=0;i<player(mc).getInventory().getContainerSize();i++)add(after,player(mc).getInventory().getItem(i));RuntimeChecks.require(before.equals(after),"Network recovery lost/duplicated malformed corpse contents");
            checkPlayer(player(mc).getInventory());RuntimeChecks.require(Objects.equals(visible.get(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(body(mc)).get(NbtRecoveryChecks.RECOVERY)),"Network extraction changed unresolved raw NBT");
            NbtWorldChecks.verifyMetadata(body(mc));
            for(int i=0;i<8;i++)body(mc).baseTick();visible=full(body(mc));expectedPlayer=player(mc).getInventory().save(new ListTag());RuntimeChecks.saveExpected(folder(mc),"nbt-client-after",body(mc));
            CompoundTag n=new CompoundTag();n.put("Inventory",expectedPlayer.copy());try(FileOutputStream out=new FileOutputStream(new File(folder(mc),"nbt-client-player.nbt"))){NbtIo.writeCompressed(n,out);}
            mc.getSingleplayerServer().getPlayerList().saveAll();mc.getSingleplayerServer().saveAllChunks(false,true,false);mark(mc,"malformed_network_recovery_preserves_raw_records");
        });next(7);}
        else if(stage==7 && done()){checkPlayer(mc.player.getInventory());quit(mc);next(8);}
        else if(stage==8 && FixtureConnections.stopped(mc)){mc.loadLevel("build-fixture");next(9);}
        else if(stage==9 && ready(mc) && frames>20){schedule(mc,()->player(mc).connection.teleport(168.5,80,159.5,0,0));next(90);}
        else if(stage==90 && done() && frames>20){schedule(mc,()->{
            body(mc).baseTick();RuntimeChecks.verifyExpected(folder(mc),"nbt-client-after",body(mc));
            RuntimeChecks.require(Objects.equals(visible.get(NbtRecoveryChecks.RECOVERY),NbtRecoveryChecks.saved(body(mc)).get(NbtRecoveryChecks.RECOVERY)),"Unresolved raw data changed on graphical disk reload");
            NbtWorldChecks.verifyMetadata(body(mc));RuntimeChecks.require(NameLifetimeChecks.same(visible.get(NameLifetimeChecks.RECOVERY),full(body(mc)).get(NameLifetimeChecks.RECOVERY)),"Raw scalar evidence changed on graphical reload");
            RuntimeChecks.require(expectedPlayer.equals(player(mc).getInventory().save(new ListTag())),"Recovered player NBT changed on graphical disk reload");checkPlayer(player(mc).getInventory());mark(mc,"malformed_corpse_and_player_disk_reload");
        });next(10);}
        else if(stage==10 && done() && frames>20){if(open(mc))next(11);}
        else if(stage==11 && gui(mc) && frames>20){verifyGui(mc);checkPlayer(mc.player.getInventory());screenshot(mc,"nbt-after-recovery-reload.png");System.out.println("LOOTABLE_BODIES_NBT_CLIENT_PASS");return true;}
        if((stage==4 || stage==5 || stage==10 || stage==11) && !gui(mc) && frames%10==0) {
            open(mc);
            if(frames==60) {
                System.out.println("NBT_GUI_CLIENT_STATE position="+mc.player.blockPosition()+" container="+mc.player.containerMenu.getClass().getSimpleName());
                for(Entity entity:FixtureApi.entities(mc.level))if(entity instanceof EntityLootableBody)System.out.println("NBT_GUI_CLIENT_BODY uuid="+entity.getUUID()+" position="+entity.blockPosition()+" id="+entity.getId());
                schedule(mc,()->System.out.println("NBT_GUI_SERVER_STATE player="+player(mc).blockPosition()+" body="+body(mc).blockPosition()+" usable="+body(mc).stillValid(player(mc))+" alive="+body(mc).isAlive()+" health="+body(mc).getHealth()+" container="+player(mc).containerMenu.getClass().getSimpleName()));
            }
        }
        return false;
    }
    private void verifyGui(Minecraft mc){ItemStack[] expected=new ItemStack[48];ListTag list=visible.getList("Equipment",10);for(int i=0;i<list.size();i++){CompoundTag n=list.getCompound(i);expected[n.getByte("Slot")]=ItemStack.of(n);}for(int i=0;i<48;i++)equal(mc.player.containerMenu.getSlot(i).getItem(),expected[i],"Malformed corpse GUI slot "+i);}
    private static void checkPlayer(net.minecraft.world.entity.player.Inventory p){Map<String,Integer> actual=new TreeMap<>();for(int i=0;i<p.getContainerSize();i++)add(actual,p.getItem(i));Map<String,Integer> expected=new TreeMap<>();add(expected,NbtRecoveryChecks.item(120,17));RuntimeChecks.require(actual.equals(expected),"Malformed GUI recovered player stack changed");}
    private static void add(Map<String,Integer> map,ItemStack s){if((s == null || s.isEmpty()) || s.getCount()<=0)return;CompoundTag n=s.save(new CompoundTag());n.remove("Count");String key=n.toString();map.put(key,map.getOrDefault(key,0)+s.getCount());}
    private static void equal(ItemStack a,ItemStack b,String why){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),why);}
    private static CompoundTag full(EntityLootableBody b){CompoundTag n=new CompoundTag();b.saveAsPassenger(n);return n;}
    private ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);}
    private EntityLootableBody body(Minecraft mc){return RuntimeChecks.find(mc.getSingleplayerServer().getLevel(FixtureApi.dimensionKey(0)),bodyId);}
    private File folder(Minecraft mc){return player(mc).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();}
    private static boolean ready(Minecraft mc){return mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null;}
    private static boolean gui(Minecraft mc){return mc.screen instanceof CorpseGUIContainer;}
    private boolean open(Minecraft mc){for(Entity e:FixtureApi.entities(mc.level))if(e.getUUID().equals(bodyId)){RuntimeChecks.require(((EntityLootableBody)e).getCustomNameTag().equals("NbtFixture"),"Malformed name reached tracked client entity");RuntimeChecks.require(e instanceof EntityLootableBody && Float.isFinite(((EntityLootableBody)e).getHealth()) && ((EntityLootableBody)e).getHealth()>0,"Malformed health reached tracked client entity");RuntimeChecks.require(((EntityLootableBody)e).getActiveEffects().isEmpty() && ((EntityLootableBody)e).hurtTime==0,"Malformed effect/hurt state reached tracked client entity");PositionMotionChecks.finite((EntityLootableBody)e);RotationFallChecks.finite((EntityLootableBody)e);mc.gameMode.interact(mc.player,e,InteractionHand.MAIN_HAND);return true;}return false;}
    private void schedule(Minecraft mc,Action action){pending=mc.getSingleplayerServer().submitAsync(()->{try{action.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean done() throws Exception{if(!pending.isDone())return false;pending.get();return true;}
    private void next(int value){stage=value;frames=0;System.out.println("NBT_CLIENT_STAGE "+stage);}
    private static void screenshot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->System.out.println(message.getString()));}
    private static void quit(Minecraft mc){mc.player.closeContainer();FixtureConnections.disconnect(mc);}
    private static void mark(Minecraft mc,String name) throws Exception{try(FileWriter out=new FileWriter(new File(mc.gameDirectory,"nbt-client-checks.txt"),true)){out.write("PASS "+name+"\n");}System.out.println("NBT_CLIENT_CHECK_PASS "+name);}
}
