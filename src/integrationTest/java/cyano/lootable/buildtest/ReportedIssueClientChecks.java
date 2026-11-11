package cyano.lootable.buildtest;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.SimpleContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseGUIContainer;
import java.io.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;

/** Exact issue5 sequence over a real connected client and two disk reloads. */
final class ReportedIssueClientChecks {
    private interface Action {void run() throws Exception;}
    private int stage,frames,active;
    private boolean distantViewerObserved,approachPending;
    private Future<?> pending;
    private Future<Boolean> nativeLoaded;
    private UUID firstId,secondId;
    private BlockPos firstOrigin, secondOrigin;
    private File report;
    private CompoundTag savedBody,savedPlayer;
    static ItemStack original() {
        ItemStack s=new ItemStack(Items.DIAMOND_SHOVEL);
        FixtureStackData.setCustom(s,FixtureStackData.custom(RuntimeChecks.stack(300)));s.setDamageValue(17);
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Issue 5 original shovel"));return s;
    }
    void tick(Minecraft mc) throws Exception {
        frames++;
        if(stage==0 && mc.screen instanceof TitleScreen) {
            report=new File(mc.gameDirectory,"issue5-client-checks.txt");RuntimeChecks.require(!report.exists(),"Stale issue5 report");
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.options.renderDistance().set(4);
            FixtureApi.ClientWorlds.create(mc,"issue-fixture","Issue fixture",net.minecraft.world.level.GameType.SURVIVAL,true);next(1);
        } else if(stage==1 && ready(mc)) {
            schedule(mc,()->{
                ServerLevel w=player(mc).level();
                RuntimeChecks.awaitChunk(w, 2, 0);
                w.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY,false,null);
                w.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,null);
                for(int x=29;x<=36;x++)for(int z=-3;z<=4;z++)w.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                ServerPlayer p=player(mc);p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getInventory().setSelectedSlot(4);
                p.getInventory().getNonEquipmentItems().set(4,original());p.getInventory().getNonEquipmentItems().set(9,RuntimeChecks.stack(301));
                p.connection.teleport(32.5,80,.5,0,0);p.containerMenu.broadcastChanges();
                EntityLootableBody first = die(p, null);
                firstId = first.getUUID();
                firstOrigin = first.blockPosition();
            });next(2);
        } else if(stage==2 && done() && mc.screen instanceof DeathScreen && frames>10) {
            mc.player.respawn();next(3);
        } else if(stage==3 && ready(mc) && mc.player.isAlive() && !(mc.screen instanceof DeathScreen)) {
            schedule(mc,()->player(mc).connection.teleport(32.5,80,.5,0,0));next(4);
        } else if(stage==4 && done() && open(mc,firstId)) {next(5);
        } else if(stage==5 && gui(mc) && frames>15) {
            equal(mc.player.containerMenu.getSlot(4).getItem(),original(),"First corpse main hand");shot(mc,"issue5-first-main-hand.png");
            mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,4,0,ContainerInput.SWAP,mc.player);next(6);
        } else if(stage==6 && frames>15) {
            equal(mc.player.getInventory().getNonEquipmentItems().get(0),original(),"Key1 original shovel recovery");
            for(int slot=0;slot<48;slot++)if(mc.player.containerMenu.getSlot(slot).hasItem())
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,0,ContainerInput.QUICK_MOVE,mc.player);
            next(7);
        } else if(stage==7 && frames>20) {
            equal(mc.player.getInventory().getNonEquipmentItems().get(0),original(),"Original shovel changed before switch");
            active=-1;for(int slot=1;slot<9;slot++)if(RuntimeChecks.sameStack(mc.player.getInventory().getNonEquipmentItems().get(slot),RuntimeChecks.stack(301)))active=slot;
            RuntimeChecks.require(active>0,"No different recovered hotbar item");
            mark("first_death_respawn_key1_and_shift_recovery");
            mc.player.closeContainer();mc.player.getInventory().setSelectedSlot(active);
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(active));
            // Same ordered connection: held-item change, then a build-only death signal.
            // This uses the vanilla death path without native command permissions.
            MultiplayerChecks.CHANNEL.send(new MultiplayerChecks.FixtureDeath(active),net.minecraftforge.network.PacketDistributor.SERVER.noArg());next(8);
        } else if(stage==8 && mc.screen instanceof DeathScreen && frames>10) {
            mc.player.respawn();next(9);
        } else if(stage==9 && ready(mc) && mc.player.isAlive() && !(mc.screen instanceof DeathScreen)) {
            schedule(mc,()->{
                ServerPlayer p=player(mc);EntityLootableBody second=null;
                for(Entity e:FixtureApi.entities(p.level()))if(e instanceof EntityLootableBody && !e.getUUID().equals(firstId) && p.getGameProfile().name().equals(((EntityLootableBody)e).getCustomNameTag()))second=(EntityLootableBody)e;
                RuntimeChecks.require(second!=null,"Second actual death corpse absent");
                secondId = second.getUUID();
                secondOrigin = second.blockPosition();
                verifyOriginals(second);equal(second.getItem(4),RuntimeChecks.stack(301),"Second selected main hand");
                RuntimeChecks.require(FixtureApi.saveInventory(p).size()==0,"Second death duplicated player inventory");
                if (Boolean.getBoolean("lootablebodies.issueClient.offsetViewer")) {
                    RuntimeChecks.awaitChunk(p.level(), 4, 4);
                    for (int x = 63; x <= 65; x++) for (int z = 63; z <= 65; z++)
                        p.level().setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState());
                    p.connection.teleport(64.5, 80, 64.5, 0, 0);
                } else p.connection.teleport(32.5,80,.5,0,0);
                mark("second_death_without_inventory_resync_preserves_original_shovel_and_all_tags");
            });next(10);
        } else if(stage==10 && done()) {
            if(Boolean.getBoolean("lootablebodies.issueClient.offsetViewer") && !distantViewerObserved) {
                if(frames<40)return;
                boolean present=false;
                for(Entity e:FixtureApi.entities(mc.level))if(e.getUUID().equals(secondId))present=true;
                if(present) {
                    RuntimeChecks.require(frames<200,"Distant viewer did not leave corpse tracking range");
                    return;
                }
                distantViewerObserved=true;
                System.out.println("ISSUE5_DISTANT_VIEWER_CONFIRMED corpse="+secondId);
            }
            if(open(mc,secondId))next(11);
        } else if(stage==11 && gui(mc) && frames>20) {
            verifyClientOriginals(mc);shot(mc,"issue5-second-corpse.png");
            mc.player.closeContainer();schedule(mc,()->save(mc));next(12);
        } else if(stage==12 && done()) {quit(mc);next(13);
        } else if(stage==13 && FixtureConnections.stopped(mc)) {
            mc.createWorldOpenFlows().openWorld("issue-fixture",()->mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));next(14);
        } else if(stage==14 && ready(mc) && frames>25) {
            schedule(mc,()->{verifyDisk(mc);verifyOriginals(body(mc));mark("first_disk_reload_complete_corpse_inventory");});next(15);
        } else if(stage==15 && done() && open(mc,secondId)) {next(16);
        } else if(stage==16 && gui(mc) && frames>20) {
            verifyClientOriginals(mc);shot(mc,"issue5-reloaded-corpse.png");
            for(int slot=0;slot<48;slot++)if(mc.player.containerMenu.getSlot(slot).hasItem())
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,0,ContainerInput.QUICK_MOVE,mc.player);
            next(17);
        } else if(stage==17 && frames>20) {
            mc.player.closeContainer();schedule(mc,()->{
                RuntimeChecks.require(body(mc).isEmpty(),"Final recovery left duplicate corpse items");
                Map<String,Integer> expected=originals(),actual=new TreeMap<>();
                ServerPlayer p=player(mc);
                for(int i=0;i<p.getInventory().getContainerSize();i++)addTagged(actual,p.getInventory().getItem(i));
                RuntimeChecks.require(expected.equals(actual),"Final player recovery lost or duplicated original tags");
                save(mc);mark("second_corpse_recovery_exact_counts_damage_and_nested_nbt");
            });next(18);
        } else if(stage==18 && done()) {quit(mc);next(19);
        } else if(stage==19 && FixtureConnections.stopped(mc)) {
            mc.createWorldOpenFlows().openWorld("issue-fixture",()->mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));next(20);
        } else if(stage==20 && ready(mc) && frames>25) {
            schedule(mc,()->{verifyDisk(mc);mark("second_disk_reload_complete_player_and_corpse_inventory");});next(21);
        } else if(stage==21 && done()) {
            new File(mc.gameDirectory,"client.pass").createNewFile();
            System.out.println("LOOTABLE_BODIES_ISSUE5_CLIENT_PASS");quit(mc);next(22);
        } else if(stage==22 && FixtureConnections.stopped(mc)) {mc.stop();next(23);}
    }
    private EntityLootableBody die(ServerPlayer p,UUID skip) {
        Set<Entity> before=new HashSet<>(FixtureApi.entities(p.level()));p.kill(p.level());
        for(Entity e:FixtureApi.entities(p.level()))if(e instanceof EntityLootableBody && !before.contains(e))return (EntityLootableBody)e;
        throw new AssertionError("Actual connected death absent");
    }
    private static void addTagged(Map<String,Integer> result,ItemStack stack) {
        if((stack == null || stack.isEmpty()) || !FixtureStackData.hasCustom(stack) || !FixtureStackData.custom(stack).contains("fixture"))return;
        CompoundTag n=FixtureStackData.save(stack);n.remove("count");result.put(n.toString(),result.getOrDefault(n.toString(),0)+stack.getCount());
    }
    private static Map<String,Integer> originals(){Map<String,Integer> m=new TreeMap<>();addTagged(m,original());addTagged(m,RuntimeChecks.stack(301));return m;}
    private static void verifyOriginals(EntityLootableBody b) {
        Map<String,Integer> n=new TreeMap<>(ReportedIssueChecks.inventory(b));n.keySet().removeIf(k->!k.contains("fixture"));
        RuntimeChecks.require(originals().equals(n),"Second corpse lost/duplicated exact tagged inventory");
    }
    private static void verifyClientOriginals(Minecraft mc){Map<String,Integer> n=new TreeMap<>();for(int i=0;i<48;i++)addTagged(n,mc.player.containerMenu.getSlot(i).getItem());RuntimeChecks.require(originals().equals(n),"Connected GUI differs from exact originals");}
    private void save(Minecraft mc) throws Exception {
        savedBody=new CompoundTag();FixtureApi.saveEntity(body(mc),savedBody);savedPlayer=new CompoundTag();savedPlayer.put("Inventory",FixtureApi.saveInventory(player(mc)));
        File folder=player(mc).level().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
        try(FileOutputStream out=new FileOutputStream(new File(folder,"issue5-corpse-expected.nbt"))){NbtIo.writeCompressed(savedBody,out);}
        try(FileOutputStream out=new FileOutputStream(new File(folder,"issue5-player-expected.nbt"))){NbtIo.writeCompressed(savedPlayer,out);}
        mc.getSingleplayerServer().getPlayerList().saveAll();mc.getSingleplayerServer().saveAllChunks(false,true,false);
    }
    private void verifyDisk(Minecraft mc) {
        RuntimeChecks.awaitChunk(player(mc).level(),2,0);CompoundTag actual=new CompoundTag();FixtureApi.saveEntity(body(mc),actual);
        for(String key:Arrays.asList("id","UUID","UUIDMost","UUIDLeast","Equipment","Aux","Name"))RuntimeChecks.require(Objects.equals(savedBody.get(key),actual.get(key)),"Disk corpse differs: "+key);
        RuntimeChecks.require(typedList(savedPlayer, "Inventory", 10).equals(FixtureApi.saveInventory(player(mc))),"Complete player inventory differs after disk reload");
    }
    private ServerPlayer player(Minecraft mc){return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());}
    private EntityLootableBody body(Minecraft mc){return RuntimeChecks.find(player(mc).level(),secondId);}
    private static void equal(ItemStack a,ItemStack b,String reason){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),reason);}
    private boolean ready(Minecraft mc) throws Exception {
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null
                || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) {
            nativeLoaded = null;
            return false;
        }
        if (nativeLoaded == null) {
            UUID id = mc.player.getUUID();
            var server = mc.getSingleplayerServer();
            nativeLoaded = server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                return player != null && player.connection.hasClientLoaded();
            });
            return false;
        }
        if (!nativeLoaded.isDone()) return false;
        boolean loaded = nativeLoaded.get();
        nativeLoaded = null;
        return loaded;
    }
    private static boolean gui(Minecraft mc){return mc.screen instanceof CorpseGUIContainer;}
    private boolean open(Minecraft mc,UUID id) throws Exception {
        // Sending an interaction does not mean the server has opened its GUI.
        if(gui(mc)){approachPending=false;return true;}
        if(frames%10!=0)return false;
        if(!approachPending) {
            // A distant client legitimately has no entity to approach yet.
            // Resolve the fixture's UUID on the server, move the viewer back
            // into range, then wait for normal tracking and interaction.
            schedule(mc,()->{
                ServerPlayer p=player(mc);
                BlockPos origin = id.equals(firstId) ? firstOrigin : secondOrigin;
                RuntimeChecks.require(origin != null, "Missing observed corpse position");
                if (p.distanceToSqr(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5) > 9) {
                    double x = origin.getX() + 0.5, z = origin.getZ() - 1.5;
                    int y = origin.getY();
                    // A respawn can leave the viewer far from the original
                    // platform. Give this disposable fixture a stable place
                    // beside the actual corpse before sending normal clicks.
                    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
                        p.level().setBlockAndUpdate(BlockPos.containing(x+dx,y-1,z+dz),Blocks.STONE.defaultBlockState());
                    p.setDeltaMovement(0,0,0);
                    p.connection.teleport(x,y,z,0,0);
                    System.out.println("ISSUE5_VIEWER_APPROACH corpse="+id);
                }
            });
            approachPending=true;
            return false;
        }
        if(!done())return false;
        approachPending=false;
        for(Entity e:FixtureApi.entities(mc.level))if(e instanceof EntityLootableBody && e.getUUID().equals(id) && mc.player.distanceToSqr(e)<=9){
            System.out.println("ISSUE5_GUI_OPEN_REQUEST corpse="+id+" distance_sq="+mc.player.distanceToSqr(e));
            mc.gameMode.interact(mc.player, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);
            break;
        }
        return false;
    }
    private void schedule(Minecraft mc,Action a){pending=mc.getSingleplayerServer().submitAsync(()->{try{a.run();}catch(Exception e){throw new RuntimeException(e);}});}
    private boolean done() throws Exception {if(!pending.isDone())return false;pending.get();return true;}
    private void next(int n){stage=n;frames=0;System.out.println("ISSUE5_CLIENT_STAGE "+n);}
    private void mark(String name) throws Exception {try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+name+"\n");}System.out.println("ISSUE5_CLIENT_CHECK_PASS "+name);}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,message->System.out.println(message.getString()));}
    private static void quit(Minecraft mc){mc.player.closeContainer();FixtureConnections.disconnect(mc);}
}
