package cyano.lootable.buildtest;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;


/** Public issue reproductions in real Forge; excluded from production artifacts. */
final class ReportedIssueChecks {
    private interface Check { void run() throws Exception; }
    private static final List<String> results = new ArrayList<>();
    private static MinecraftServer server;
    private static ServerLevel world;
    private static int failures, sequence;
    private static File folder;

    static void run(MinecraftServer instance, ServerLevel target, File directory) throws Exception {
        server=instance; world=target; folder=directory; results.clear(); failures=0;
        check("issue5_exact_two_deaths_hotbar_one_then_switch_without_inventory_sync", ReportedIssueChecks::deathSequence);
        check("issue17_loaded_platform_keeps_gravity_and_inventory", ReportedIssueChecks::loadedPlatform);
        check("issue17_normal_missing_neighbours_do_not_tick_or_load", ReportedIssueChecks::normalTickGate);
        check("issue17_normal_chunk_unload_reload_preserves_complete_inventory", ReportedIssueChecks::unloadReload);
        check("issue17_unloaded_east_support_preserves_inventory", ()->forcedSupport(0,1));
        check("issue17_unloaded_west_support_preserves_inventory", ()->forcedSupport(0,-1));
        check("issue17_unloaded_south_support_preserves_inventory", ()->forcedSupport(1,1));
        check("issue17_unloaded_north_support_preserves_inventory", ()->forcedSupport(1,-1));
        check("issue17_loaded_real_void_keeps_existing_fatal_behavior", ReportedIssueChecks::realVoid);
        try(PrintWriter out=new PrintWriter(new File(folder,"reported-issue-checks.txt"),"UTF-8")) {
            for(String line:results) out.println(line);
            out.println("checks="+results.size()+" failures="+failures);
        }
        RuntimeChecks.require(failures==0,"Reported issue regressions: "+failures+" / "+results.size());
        System.out.println("LOOTABLE_BODIES_REPORTED_ISSUES_PASS checks="+results.size());
    }
    private static void check(String name, Check action) {
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));
        try {action.run(); results.add("PASS "+name); System.out.println("REPORTED_ISSUE_PASS "+name);}
        catch(Throwable error) {failures++;results.add("FAIL "+name+": "+error);System.out.println("REPORTED_ISSUE_FAIL "+name+": "+error);error.printStackTrace();
            if(Boolean.getBoolean("lootablebodies.diagnosticFailFast"))throw new IllegalStateException("Fail-fast diagnostic issue check: "+name,error);}
        finally {
            for(Entity entity:new ArrayList<Entity>(FixtureApi.entities(world))) if(!before.contains(entity)) {
                if(entity instanceof ServerPlayer) server.getPlayerList().remove((ServerPlayer)entity);
                else world.removeEntity(entity);
            }
        }
    }
    private static ItemStack shovel() {
        ItemStack stack=new ItemStack(Items.DIAMOND_SHOVEL);
        stack.setTag(RuntimeChecks.stack(300).getTag().copy());stack.setDamageValue(17);
        stack.setHoverName(new net.minecraft.network.chat.TextComponent("Issue 5 original shovel")); return stack;
    }
    static Map<String,Integer> inventory(EntityLootableBody body) {
        Map<String,Integer> total=new TreeMap<>();
        for(int i=0;i<48;i++) add(total,body.getItem(i));
        CompoundTag n=new CompoundTag();body.addAdditionalSaveData(n);
        ListTag aux=n.getList("Aux",10);
        for(int i=0;i<aux.size();i++)add(total,ItemStack.of(aux.getCompound(i)));
        return total;
    }
    private static void add(Map<String,Integer> total,ItemStack stack) {
        if((stack == null || stack.isEmpty()) || stack.getCount()<=0)return;
        CompoundTag n=stack.save(new CompoundTag());n.remove("Count");
        total.put(n.toString(),total.getOrDefault(n.toString(),0)+stack.getCount());
    }
    private static Map<String,Integer> tagged(EntityLootableBody body) {
        Map<String,Integer> n=inventory(body); n.keySet().removeIf(k->!k.contains("fixture"));return n;
    }
    private static Map<String,Integer> tagged(ServerPlayer player) {
        Map<String,Integer> n=new TreeMap<>();
        for(int i=0;i<player.getInventory().getContainerSize();i++) {
            ItemStack stack=player.getInventory().getItem(i);
            if((stack != null && !stack.isEmpty()) && stack.hasTag() && stack.getTag().contains("fixture"))add(n,stack);
        }
        return n;
    }
    private static EntityLootableBody death(ServerPlayer player) {
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));player.kill();
        for(Entity e:FixtureApi.entities(world))if(e instanceof EntityLootableBody && !before.contains(e))return (EntityLootableBody)e;
        throw new AssertionError("Real player death did not create corpse");
    }
    private static ServerPlayer respawn(ServerPlayer player) {
        ServerGamePacketListenerImpl connection=player.connection;
        connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer replacement=connection.player;
        RuntimeChecks.require(replacement!=player && replacement.isAlive(),"Actual player recreation did not occur");
        replacement.setPos(32.5,80,0.5);return replacement;
    }
    private static void click(ServerPlayer p,int slot,int button,ClickType type) throws IOException {
        QuietConnection observed=(QuietConnection)p.connection;int before=observed.menuEvents;
        // Server-side conservation fixture: omit predicted slots and require
        // native authoritative corrections. Real clients test normal prediction.
        ServerboundContainerClickPacket outgoing=new ServerboundContainerClickPacket(p.containerMenu.containerId,
                p.containerMenu.getStateId(),slot,button,type,p.containerMenu.getCarried().copy(),
                new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack>());
        FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            outgoing.write(wire);p.connection.handleContainerClick(new ServerboundContainerClickPacket(wire));
            RuntimeChecks.require(!wire.isReadable(),"Native issue click left undecoded fields");
            RuntimeChecks.require(observed.menuEvents>before && observed.menuState==p.containerMenu.getStateId()
                    && RuntimeChecks.sameStack(observed.carried,p.containerMenu.getCarried()),"Native menu correction did not match its authoritative state/cursor");
        } finally {wire.release();}
    }
    private static void deathSequence() throws Exception {
        world.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);RuntimeChecks.awaitChunk(world,2,0);
        ServerPlayer p=new ServerPlayer(server,world,new GameProfile(UUID.randomUUID(),"IssueFive"));
        p.connection=new QuietConnection(p);p.setGameMode(GameType.SURVIVAL);p.setPos(32.5,80,0.5);
        // Vanilla clients need player-list information before a tracked player
        // spawn, including the brief spawn-point visit during real respawn.
        server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket(
                net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket.Action.ADD_PLAYER,p));
        RuntimeChecks.require(world.addFreshEntity(p),"Fixture player spawn");server.getPlayerList().addPlayer(p);
        p.getInventory().selected=4;p.getInventory().items.set(4,shovel());p.getInventory().items.set(9,RuntimeChecks.stack(301));
        Map<String,Integer> expected=tagged(p);
        EntityLootableBody first=death(p);RuntimeChecks.require(expected.equals(tagged(first)),"First death lost inventory");
        p=respawn(p);CorpseContainer gui=new CorpseContainer(17,p.getInventory(),first);p.containerMenu=gui;p.initMenu(gui);
        click(p,4,0,ClickType.SWAP);
        RuntimeChecks.require(RuntimeChecks.sameStack(p.getInventory().items.get(0),shovel()),"Key1 did not recover original main hand");
        for(int slot=0;slot<48;slot++)if(gui.getSlot(slot).hasItem())click(p,slot,0,ClickType.QUICK_MOVE);
        RuntimeChecks.require(first.isEmpty() && expected.equals(tagged(p)),"First recovery lost or duplicated tagged inventory: remaining="+inventory(first)+" expected="+expected+" player="+tagged(p)+" state="+gui.getStateId());
        FriendlyByteBuf close=new FriendlyByteBuf(Unpooled.buffer());
        try {new ServerboundContainerClosePacket(gui.containerId).write(close);p.connection.handleContainerClose(new ServerboundContainerClosePacket(close));RuntimeChecks.require(!close.isReadable(),"Native issue close left undecoded fields");}
        finally {close.release();}
        int active=-1;for(int slot=1;slot<9;slot++)if(!(p.getInventory().items.get(slot)).isEmpty() && p.getInventory().items.get(slot).hasTag() && p.getInventory().items.get(slot).getTag().contains("fixture"))active=slot;
        RuntimeChecks.require(active>0,"Shift-click did not leave a different occupied hotbar slot");
        FriendlyByteBuf held=new FriendlyByteBuf(Unpooled.buffer());
        try {new ServerboundSetCarriedItemPacket(active).write(held);p.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(held));RuntimeChecks.require(!held.isReadable(),"Native held-item packet left undecoded fields");}
        finally {held.release();}
        // Deliberately do not tick/sync container or reopen inventory here.
        EntityLootableBody second=death(p);
        RuntimeChecks.require(expected.equals(tagged(second)),"Second death lost original key1 shovel or another exact stack");
        RuntimeChecks.require(RuntimeChecks.sameStack(second.getItem(4),RuntimeChecks.stack(301)),"Second death active hand differs");
        System.out.println("ISSUE5_REAL_RESPAWN_AND_HELD_ITEM_PACKET_PASS");
        p=respawn(p);server.getPlayerList().remove(p);
        CompoundTag n=new CompoundTag();second.saveAsPassenger(n);
        EntityLootableBody loaded=new EntityLootableBody(world);loaded.load(n);
        RuntimeChecks.require(expected.equals(tagged(loaded)),"Second corpse NBT reload lost recovery");
    }
    private static EntityLootableBody fullBody(int cx,int cz,double offset) {return fullBody(cx,cz,offset,null);}
    private static EntityLootableBody fullBody(int cx,int cz,double offset,UUID savedId) {
        RuntimeChecks.awaitChunk(world,cx,cz);
        EntityLootableBody body=new EntityLootableBody(world);body.setUserName("OfflineIssue17");
        if(savedId!=null)body.setUUID(savedId);
        body.setPos(cx*16+offset,80,cz*16+8.5);
        for(int i=0;i<48;i++)body.setItem(i,RuntimeChecks.stack(i));
        body.addItem(RuntimeChecks.stack(70));body.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(body);
        RuntimeChecks.require(!body.isNoGravity(),"Issue17 fixture must use real gravity");
        RuntimeChecks.require(world.addFreshEntity(body),"Physics fixture spawn");return body;
    }
    private static void loadedPlatform() {
        int c=96;for(int x=c-2;x<=c+2;x++)for(int z=c-2;z<=c+2;z++)RuntimeChecks.awaitChunk(world,x,z);
        world.setBlockAndUpdate(new BlockPos(c*16+8,79,c*16+8),Blocks.STONE.defaultBlockState());
        EntityLootableBody b=fullBody(c,c,8.5);Map<String,Integer> expected=inventory(b);
        FixtureApi.awaitTicking(b);
        for(int i=0;i<40;i++)world.tickNonPassenger(b);
        RuntimeChecks.require(b.isOnGround() && b.getY()==80 && b.isAlive() && expected.equals(inventory(b)),"Loaded platform/normal gravity lost body or inventory");
    }
    private static void normalTickGate() {
        EntityLootableBody b=fullBody(112,112,8.5);int ticks=b.tickCount;
        // Native 1.17 gates dispatch through its entity-section tick list.
        // tickNonPassenger alone would bypass that real world dispatcher.
        for(int i=0;i<40;i++)world.tick(()->true);
        RuntimeChecks.require(b.tickCount==ticks && b.getY()==80 && !world.getChunkSource().hasChunk(113,112),"Vanilla unloaded-neighbour tick guard did not hold");
    }
    private static void unloadReload() throws Exception {
        EntityLootableBody b=fullBody(128,128,8.5);UUID uuid=b.getUUID();Map<String,Integer> expected=inventory(b);
        unloadChunk(128,128);world.tick(()->true);
        RuntimeChecks.require(!world.getChunkSource().hasChunk(128,128),"Fixture chunk did not actually unload");
        RuntimeChecks.require(!FixtureApi.entities(world).contains(b),"Unloaded corpse still receives normal world ticks");
        RuntimeChecks.awaitChunk(world,128,128);EntityLootableBody loaded=RuntimeChecks.find(world,uuid);
        RuntimeChecks.require(loaded.isAlive() && expected.equals(inventory(loaded)),"Normal chunk unload/load lost inventory");
    }
    private static final class Support implements AutoCloseable {
        EntityLootableBody body; int cx,cz,nx,nz; BlockPos platform;
        boolean forced;
        Map<String,Integer> expected;
        void unload(){world.setChunkForced(cx,cz,false);net.minecraft.world.level.ChunkPos pos=new net.minecraft.world.level.ChunkPos(cx,cz);world.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED,pos,0,pos);unloadChunk(nx,nz);RuntimeChecks.require(!world.getChunkSource().hasChunk(nx,nz),"Support chunk did not unload");}
        void frozen(){for(int i=0;i<100;i++)body.tick();System.out.println("ISSUE17_UNLOADED_SUPPORT y="+body.getY()+" alive="+body.isAlive()+" inventory="+inventory(body).size()+" supportLoaded="+world.getChunkSource().hasChunk(nx,nz));RuntimeChecks.require(body.isAlive() && body.getY()==80 && expected.equals(inventory(body)),"Corpse fell through unloaded neighbour support and lost recoverable inventory");}
        void resume(){RuntimeChecks.awaitChunk(world,nx,nz);FixtureApi.awaitTicking(body);for(int i=0;i<10;i++)world.tickNonPassenger(body);RuntimeChecks.require(body.isOnGround() && body.getY()==80 && FixtureApi.sectionContains(body,cx,cz) && expected.equals(inventory(body)),"Corpse failed to resume safely after support terrain loaded");}
        @Override public void close(){if(forced)world.setChunkForced(cx,cz,false);net.minecraft.world.level.ChunkPos pos=new net.minecraft.world.level.ChunkPos(cx,cz);world.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED,pos,0,pos);}
    }
    private static Support support(int axis,int direction) {return support(axis,direction,null);}
    private static Support support(int axis,int direction,UUID savedId) {
        Support f=new Support();f.cx=144+(sequence++)*8;f.cz=f.cx;
        f.nx=f.cx+(axis==0?direction:0);f.nz=f.cz+(axis==1?direction:0);
        RuntimeChecks.awaitChunk(world,f.cx,f.cz);RuntimeChecks.awaitChunk(world,f.nx,f.nz);
        double x=f.cx*16+(axis==0?(direction>0?15.6:.4):8.5);
        double z=f.cz*16+(axis==1?(direction>0?15.6:.4):8.5);
        int floorX=Mth.floor(x),floorZ=Mth.floor(z);
        f.platform=new BlockPos(floorX+(axis==0?direction:0),79,floorZ+(axis==1?direction:0));
        // A body can straddle a chunk boundary while its centre remains in the ticketed chunk.
        // Remove only the two fixture columns' ground, leaving one thin supporting platform.
        for(int y=0;y<5;y++){world.removeBlock(new BlockPos(floorX,y,floorZ),false);world.removeBlock(new BlockPos(f.platform.getX(),y,f.platform.getZ()),false);}
        world.setBlockAndUpdate(f.platform,Blocks.STONE.defaultBlockState());
        f.body=fullBody(f.cx,f.cz,8.5,savedId);f.body.setPos(x,80,z);f.expected=inventory(f.body);
        f.forced=world.setChunkForced(f.cx,f.cz,true);
        RuntimeChecks.require(f.forced && world.getChunkSource().hasChunk(f.cx,f.cz),"Native forced chunk unavailable");
        FixtureApi.awaitTicking(f.body);
        for(int i=0;i<10;i++)world.tickNonPassenger(f.body);
        RuntimeChecks.require(f.body.isOnGround() && f.body.getY()==80 && f.body.isAlive(),"Loaded neighbour platform control failed");
        return f;
    }
    private static void forcedSupport(int axis,int direction) {
        try(Support f=support(axis,direction)) {
            CompoundTag before=new CompoundTag();f.body.saveAsPassenger(before);
            f.unload();f.frozen();
            CompoundTag after=new CompoundTag();f.body.saveAsPassenger(after);
            RuntimeChecks.require(before.equals(after),"Waiting for terrain changed healthy saved NBT");
            f.resume();
        }
    }
    private static void realVoid() {
        try(Support f=support(0,1)) {
            world.removeBlock(f.platform,false);
            // 1.11 applies ordinary 4-HP void hits with hurt-resistance intervals.
            // Allow that native sequence to finish; retain the same conservation gate.
            for(int i=0;i<250 && f.body.isAlive();i++)world.tickNonPassenger(f.body);
            for(int i=0;i<15;i++)f.body.baseTick();
            System.out.println("VOID_CONTROL y="+f.body.getY()+" alive="+f.body.isAlive()+" items="+inventory(f.body).size()+" motion="+f.body.getDeltaMovement().y);
            RuntimeChecks.require(!f.body.isAlive() && inventory(f.body).isEmpty(),"Loaded real void behavior changed into a rescue feature");
        }
    }
    private static void unloadChunk(int x,int z) {
        FixtureApi.unload(world,x,z);
    }
    private static void flushChunks() { FixtureApi.flush(server); }

    static void seedPaused() throws Exception {
        try(Support f=support(0,1,UUID.fromString("00000000-0000-4000-8000-000000000117"))) {
            f.unload();f.frozen();RuntimeChecks.saveExpected(folder,"issue17-paused",f.body);
            try(PrintWriter out=new PrintWriter(new File(folder,"issue17-chunks.txt"),"UTF-8")){out.println(f.cx+" "+f.cz+" "+f.nx+" "+f.nz);}
        }
    }
    static void coldReload(MinecraftServer instance,ServerLevel target,File directory) throws Exception {
        server=instance;world=target;folder=directory;
        String[] coords=new String(java.nio.file.Files.readAllBytes(new File(folder,"issue17-chunks.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8).trim().split(" ");
        int cx=Integer.parseInt(coords[0]),cz=Integer.parseInt(coords[1]),nx=Integer.parseInt(coords[2]),nz=Integer.parseInt(coords[3]);
        RuntimeChecks.awaitChunk(world,cx,cz);
        RuntimeChecks.require(!world.getChunkSource().hasChunk(nx,nz),"Deferred support chunk opened too early");
        EntityLootableBody b=RuntimeChecks.find(world,UUID.fromString("00000000-0000-4000-8000-000000000117"));
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        for(int i=0;i<100;i++)world.tickNonPassenger(b);
        RuntimeChecks.require(b.isAlive() && b.getY()==80,"Cold-loaded paused corpse fell before support loaded");
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++)RuntimeChecks.awaitChunk(world,x,z);
        FixtureApi.awaitTicking(b);
        for(int i=0;i<20;i++)world.tickNonPassenger(b);
        RuntimeChecks.require(b.isOnGround() && b.getY()==80 && b.isAlive(),"Cold-loaded corpse failed to resume on restored platform");
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        world.setChunkForced(cx,cz,false);
        System.out.println("ISSUE17_COLD_RELOAD_DEFERRED_SUPPORT_AND_COMPLETE_NBT_PASS");
    }
    private static final class QuietConnection extends ServerGamePacketListenerImpl {
        int menuEvents,menuState=-1;ItemStack carried=ItemStack.EMPTY;
        QuietConnection(ServerPlayer p){super(server,new Connection(PacketFlow.SERVERBOUND),p);}
        @Override public void send(Packet<?> packet) {
            NativeMenuObservation observed=NativeMenuObservation.decode(packet);if(observed==null)return;
            if(observed.containerId==player.containerMenu.containerId){menuEvents++;menuState=observed.stateId;if(observed.content)carried=observed.carried;}
            else if(observed.containerId==-1)carried=observed.item;
        }
    }
}
