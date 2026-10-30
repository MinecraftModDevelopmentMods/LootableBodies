package cyano.lootable.buildtest;
import net.minecraft.world.inventory.Slot;
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
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraftforge.common.MinecraftForge;

/** Actual connected client predictions, authoritative corrections and disk reload. */
final class DropClientChecks {
    private interface Action {void run() throws Exception;}
    private final UUID bodyId=UUID.fromString("00000000-0000-4000-8000-000000000109");
    private final ContainerInteractionChecks.RejectedDrops reject=new ContainerInteractionChecks.RejectedDrops();
    private int stage,frames;
    private Future<?> pending;
    private UUID playerId;
    private CompoundTag savedBody;
    private ListTag savedPlayer;
    private final Set<UUID> preexistingDrops=new HashSet<>();
    void start(Minecraft mc){playerId=mc.player.getUUID();}
    boolean tick(Minecraft mc) throws Exception {
        frames++;
        if(stage==0){schedule(mc,()->prepare(mc));next(1);}
        else if(stage==1 && done()){if(open(mc))next(2);}
        else if(stage==2 && gui(mc) && frames>20){
            equal(slot(mc,6),item(120,17),"Initial client source");click(mc,6,0,ClickType.THROW);next(3);
        } else if(stage==3 && frames>20){
            equal(slot(mc,6),item(120,16),"Accepted client single throw");
            schedule(mc,()->{equal(body(mc).getItem(6),item(120,16),"Accepted server single throw");drop(mc,item(120,1));mark(mc,"network_accepted_throw");reject.toss=true;MinecraftForge.EVENT_BUS.register(reject);});next(4);
        } else if(stage==4 && done()) {click(mc,6,1,ClickType.THROW);next(5);}
        else if(stage==5 && frames>20){
            equal(slot(mc,6),item(120,16),"Cancelled throw did not correct client source");screenshot(mc,"drop-cancelled-source-resync.png");
            schedule(mc,()->{equal(body(mc).getItem(6),item(120,16),"Cancelled server throw");mark(mc,"network_cancelled_throw_source_resync");reject.toss=false;reject.join=true;});next(6);
        } else if(stage==6 && done()){click(mc,6,0,ClickType.PICKUP);next(7);}
        else if(stage==7 && frames>20){equal(mc.player.containerMenu.getCarried(),item(120,16),"Pickup cursor");click(mc,-999,1,ClickType.PICKUP);next(8);}
        else if(stage==8 && frames>20){
            equal(mc.player.containerMenu.getCarried(),item(120,16),"Rejected outside drop did not correct client cursor");equal(slot(mc,6),null,"Outside source reappeared");screenshot(mc,"drop-rejected-cursor-resync.png");
            schedule(mc,()->{equal(player(mc).containerMenu.getCarried(),item(120,16),"Rejected server outside cursor");mark(mc,"network_rejected_outside_cursor_resync");reject.join=false;});next(9);
        } else if(stage==9 && done()){click(mc,-999,0,ClickType.PICKUP);next(10);}
        else if(stage==10 && frames>20){
            equal(mc.player.containerMenu.getCarried(),null,"Accepted outside client cursor");
            schedule(mc,()->{drop(mc,item(120,16));equal(player(mc).containerMenu.getCarried(),null,"Accepted outside server cursor");mark(mc,"network_accepted_outside_drop");
                body(mc).setItem(6,item(120,17));player(mc).getInventory().items.set(9,item(120,5));refresh(mc);});next(11);
        } else if(stage==11 && done() && frames>20){click(mc,48,0,ClickType.PICKUP);click(mc,10,0,ClickType.PICKUP_ALL);next(12);}
        else if(stage==12 && frames>20){
            equal(mc.player.containerMenu.getCarried(),item(120,42),"Double-click client cursor");equal(slot(mc,8),item(121,10),"Double-click changed unlike NBT");
            schedule(mc,()->{equal(player(mc).containerMenu.getCarried(),item(120,42),"Double-click server cursor");equal(body(mc).getItem(6),null,"Double-click source 6");equal(body(mc).getItem(7),null,"Double-click source 7");mark(mc,"network_double_click_exact_nbt");});next(13);
        } else if(stage==13 && done()){click(mc,48,0,ClickType.PICKUP);next(14);}
        else if(stage==14 && frames>20){schedule(mc,()->{player(mc).setGameMode(GameType.CREATIVE);body(mc).setItem(6,item(120,17));refresh(mc);});next(15);}
        else if(stage==15 && done() && frames>20){RuntimeChecks.require(mc.player.getAbilities().instabuild,"Client not creative");click(mc,6,2,ClickType.CLONE);next(16);}
        else if(stage==16 && frames>20){
            equal(mc.player.containerMenu.getCarried(),item(120,64),"Creative clone client NBT");equal(slot(mc,6),item(120,17),"Creative clone changed source");
            click(mc,-999,8,ClickType.QUICK_CRAFT);click(mc,7,9,ClickType.QUICK_CRAFT);click(mc,9,9,ClickType.QUICK_CRAFT);click(mc,-999,10,ClickType.QUICK_CRAFT);next(17);
        } else if(stage==17 && frames>20){
            equal(slot(mc,7),item(120,64),"Creative client fill first");equal(slot(mc,9),item(120,64),"Creative client fill second");equal(mc.player.containerMenu.getCarried(),null,"Creative fill vanilla cursor");screenshot(mc,"drop-creative-filled.png");
            schedule(mc,()->{equal(body(mc).getItem(7),item(120,64),"Creative server fill first");equal(body(mc).getItem(9),item(120,64),"Creative server fill second");equal(player(mc).getInventory().items.get(9),item(120,42),"Creative operation changed recovered inventory");mark(mc,"network_creative_clone_and_fill");
                for(int i=0;i<48;i++)body(mc).setItem(i,item(i,i<6?1:64));body(mc).addItem(item(70,31));
                for(int i=0;i<36;i++)player(mc).getInventory().items.set(i,item(100+i,64));player(mc).containerMenu.setCarried(item(122,17));reject.toss=true;refresh(mc);});next(18);
        } else if(stage==18 && done() && frames>20){equal(mc.player.containerMenu.getCarried(),item(122,17),"Creative close cursor fixture");mc.player.closeContainer();next(19);}
        else if(stage==19 && frames>20){
            equal(mc.player.containerMenu.getCarried(),null,"Closed creative client cursor");
            schedule(mc,()->{equal(player(mc).containerMenu.getCarried(),null,"Closed creative server cursor");CompoundTag data=new CompoundTag();body(mc).addAdditionalSaveData(data);ListTag aux=data.getList("Aux",10);int count=0;
                for(int i=0;i<aux.size();i++)if(RuntimeChecks.sameStack(ItemStack.of(aux.getCompound(i)),item(122,17)))count++;
                RuntimeChecks.require(count==1,"Cancelled creative close lost/duplicated full-inventory cursor");MinecraftForge.EVENT_BUS.unregister(reject);
                RuntimeChecks.saveExpected(folder(mc),"drop-creative-final",body(mc));savedBody=new CompoundTag();body(mc).saveAsPassenger(savedBody);savedPlayer=player(mc).getInventory().save(new ListTag());
                CompoundTag expected=new CompoundTag();expected.put("Inventory",savedPlayer.copy());try(FileOutputStream out=new FileOutputStream(new File(folder(mc),"drop-creative-player.nbt"))){NbtIo.writeCompressed(expected,out);}
                mc.getSingleplayerServer().getPlayerList().saveAll();mc.getSingleplayerServer().saveAllChunks(false,true,false);});next(20);
        } else if(stage==20 && done()){quit(mc);next(21);}
        else if(stage==21 && FixtureConnections.stopped(mc)){mc.loadLevel("build-fixture");next(22);}
        else if(stage==22 && ready(mc) && frames>20){schedule(mc,()->{
            RuntimeChecks.awaitChunk(player(mc).getLevel(),2,0);body(mc).baseTick();RuntimeChecks.verifyExpected(folder(mc),"drop-creative-final",body(mc));
            RuntimeChecks.require(savedPlayer.equals(player(mc).getInventory().save(new ListTag())),"Creative player inventory changed on disk reload");player(mc).connection.teleport(40.5,80,7.5,0,0);
            mark(mc,"creative_cancelled_close_full_inventory_disk_reload");});next(23);
        } else if(stage==23 && done()){if(open(mc))next(24);}
        else if(stage==24 && gui(mc) && frames>20){
            ItemStack[] expected=new ItemStack[48];ListTag list=savedBody.getList("Equipment",10);for(int i=0;i<list.size();i++){CompoundTag tag=list.getCompound(i);expected[tag.getByte("Slot")]=ItemStack.of(tag);}
            for(int i=0;i<48;i++)equal(slot(mc,i),expected[i],"Reloaded creative client slot "+i);screenshot(mc,"drop-creative-final-reload.png");System.out.println("LOOTABLE_BODIES_DROP_CLIENT_PASS");return true;
        }
        return false;
    }
    private void prepare(Minecraft mc){
        for(Entity entity:FixtureApi.entities(player(mc).getLevel()))
            if(entity instanceof ItemEntity && !entity.isRemoved())preexistingDrops.add(entity.getUUID());
        System.out.println("DROP_PREEXISTING_ITEMS_RETAINED count="+preexistingDrops.size());
        ServerPlayer p=player(mc);for(int x=37;x<=43;x++)for(int z=5;z<=11;z++)p.level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
        p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.containerMenu.setCarried(ItemStack.EMPTY);p.connection.teleport(40.5,80,7.5,0,0);
        EntityLootableBody b=new EntityLootableBody(p.level);b.setUUID(bodyId);b.setPos(40.5,80,8.5);b.setNoGravity(true);RuntimeChecks.disableVacuum(b);
        b.setItem(6,item(120,17));b.setItem(7,item(120,20));b.setItem(8,item(121,10));RuntimeChecks.require(p.getLevel().addFreshEntity(b),"Graphical drop corpse spawn");refresh(mc);
    }
    private static ItemStack item(int id,int count){ItemStack s=RuntimeChecks.stack(id);s.setCount(count);return s;}
    private static void equal(ItemStack a,ItemStack b,String message){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),message+" expected="+b+" actual="+a);}
    private void drop(Minecraft mc,ItemStack expected){int count=0;for(Entity e:new ArrayList<Entity>(FixtureApi.entities(player(mc).getLevel())))if(e instanceof ItemEntity && !e.isRemoved() && !preexistingDrops.contains(e.getUUID())){equal(((ItemEntity)e).getItem(),expected,"Accepted new world drop NBT/count");count++;e.discard();}RuntimeChecks.require(count==1,"Expected exactly one new accepted drop, actual="+count);}
    private static ItemStack slot(Minecraft mc,int index){return mc.player.containerMenu.getSlot(index).getItem();}
    private static void click(Minecraft mc,int slot,int button,ClickType type){mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId,slot,button,type,mc.player);}
    private static boolean gui(Minecraft mc){return mc.screen instanceof CorpseGUIContainer;}
    private static boolean ready(Minecraft mc){return mc.level!=null && mc.player!=null && mc.getSingleplayerServer()!=null;}
    private boolean open(Minecraft mc){for(Entity e:FixtureApi.entities(mc.level))if(e.getUUID().equals(bodyId)){mc.gameMode.interact(mc.player,e,InteractionHand.MAIN_HAND);return true;}return false;}
    private ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(playerId);}
    private EntityLootableBody body(Minecraft mc){return RuntimeChecks.find(mc.getSingleplayerServer().getLevel(FixtureApi.dimensionKey(0)),bodyId);}
    private File folder(Minecraft mc){return player(mc).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();}
    private void refresh(Minecraft mc){player(mc).containerMenu.broadcastChanges();player(mc).containerMenu.broadcastFullState();}
    private void schedule(Minecraft mc,Action action){pending=mc.getSingleplayerServer().submitAsync(()->{try{action.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean done() throws Exception {if(!pending.isDone())return false;pending.get();return true;}
    private void next(int value){stage=value;frames=0;System.out.println("DROP_CLIENT_STAGE "+stage);}
    private static void screenshot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->System.out.println(message.getString()));}
    private void mark(Minecraft mc,String name) throws Exception {try(FileWriter out=new FileWriter(new File(mc.gameDirectory,"drop-client-checks.txt"),true)){out.write("PASS "+name+"\n");}System.out.println("DROP_CLIENT_CHECK_PASS "+name);}
    private static void quit(Minecraft mc){mc.player.closeContainer();FixtureConnections.disconnect(mc);}
}
