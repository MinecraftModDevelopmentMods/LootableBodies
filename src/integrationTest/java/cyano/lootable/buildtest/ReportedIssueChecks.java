package cyano.lootable.buildtest;
import net.minecraft.network.play.ServerPlayNetHandler;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraft.network.play.client.CHeldItemChangePacket;
import net.minecraft.network.play.client.CCloseWindowPacket;
import net.minecraft.network.play.client.CClickWindowPacket;
import net.minecraft.network.play.client.CClientStatusPacket;
import net.minecraft.network.play.server.SPlayerListItemPacket;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.CompoundNBT;

import com.mojang.authlib.GameProfile;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseContainer;
import java.io.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.play.client.*;
import io.netty.buffer.Unpooled;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.minecraft.world.chunk.Chunk;


/** Public issue reproductions in real Forge; excluded from production artifacts. */
final class ReportedIssueChecks {
    private interface Check { void run() throws Exception; }
    private static final List<String> results = new ArrayList<>();
    private static MinecraftServer server;
    private static ServerWorld world;
    private static int failures, sequence, transaction;
    private static File folder;

    static void run(MinecraftServer instance, ServerWorld target, File directory) throws Exception {
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
        catch(Throwable error) {failures++;results.add("FAIL "+name+": "+error);System.out.println("REPORTED_ISSUE_FAIL "+name+": "+error);error.printStackTrace();}
        finally {
            for(Entity entity:new ArrayList<Entity>(FixtureApi.entities(world))) if(!before.contains(entity)) {
                if(entity instanceof ServerPlayerEntity) server.getPlayerList().playerLoggedOut((ServerPlayerEntity)entity);
                else world.removeEntity(entity);
            }
        }
    }
    private static ItemStack shovel() {
        ItemStack stack=new ItemStack(Items.DIAMOND_SHOVEL);
        stack.setTag(RuntimeChecks.stack(300).getTag().copy());stack.setDamage(17);
        stack.setDisplayName(new net.minecraft.util.text.StringTextComponent("Issue 5 original shovel")); return stack;
    }
    static Map<String,Integer> inventory(EntityLootableBody body) {
        Map<String,Integer> total=new TreeMap<>();
        for(int i=0;i<48;i++) add(total,body.getStackInSlot(i));
        CompoundNBT n=new CompoundNBT();body.writeAdditional(n);
        ListNBT aux=n.getList("Aux",10);
        for(int i=0;i<aux.size();i++)add(total,ItemStack.read(aux.getCompound(i)));
        return total;
    }
    private static void add(Map<String,Integer> total,ItemStack stack) {
        if((stack == null || stack.isEmpty()) || stack.getCount()<=0)return;
        CompoundNBT n=stack.write(new CompoundNBT());n.remove("Count");
        total.put(n.toString(),total.getOrDefault(n.toString(),0)+stack.getCount());
    }
    private static Map<String,Integer> tagged(EntityLootableBody body) {
        Map<String,Integer> n=inventory(body); n.keySet().removeIf(k->!k.contains("fixture"));return n;
    }
    private static Map<String,Integer> tagged(ServerPlayerEntity player) {
        Map<String,Integer> n=new TreeMap<>();
        for(int i=0;i<player.inventory.getSizeInventory();i++) {
            ItemStack stack=player.inventory.getStackInSlot(i);
            if((stack != null && !stack.isEmpty()) && stack.hasTag() && stack.getTag().contains("fixture"))add(n,stack);
        }
        return n;
    }
    private static EntityLootableBody death(ServerPlayerEntity player) {
        Set<Entity> before=new HashSet<>(FixtureApi.entities(world));player.onKillCommand();
        for(Entity e:FixtureApi.entities(world))if(e instanceof EntityLootableBody && !before.contains(e))return (EntityLootableBody)e;
        throw new AssertionError("Real player death did not create corpse");
    }
    private static ServerPlayerEntity respawn(ServerPlayerEntity player) {
        ServerPlayNetHandler connection=player.connection;
        connection.processClientStatus(new CClientStatusPacket(CClientStatusPacket.State.PERFORM_RESPAWN));
        ServerPlayerEntity replacement=connection.player;
        RuntimeChecks.require(replacement!=player && replacement.isAlive(),"Actual player recreation did not occur");
        replacement.setPosition(32.5,80,0.5);return replacement;
    }
    private static void click(ServerPlayerEntity p,int slot,int button,ClickType type) throws IOException {
        // 1.12 QUICK_MOVE returns EMPTY after completely draining its source slot.
        ItemStack predicted=ItemStack.EMPTY;
        PacketBuffer wire=new PacketBuffer(Unpooled.buffer());
        try {
            wire.writeByte(p.openContainer.windowId);wire.writeShort(slot);wire.writeByte(button);wire.writeShort(++transaction);
            wire.writeEnumValue(type);wire.writeItemStack(predicted == null ? ItemStack.EMPTY : predicted);
            CClickWindowPacket packet=new CClickWindowPacket();packet.readPacketData(wire);p.connection.processClickWindow(packet);
            RuntimeChecks.require(p.openContainer.getCanCraft(p),"Packet prediction rejected the click transaction");
        } finally {wire.release();}
    }
    private static void deathSequence() throws Exception {
        world.getGameRules().get(net.minecraft.world.GameRules.KEEP_INVENTORY).set(false,null);RuntimeChecks.awaitChunk(world,2,0);
        ServerPlayerEntity p=new ServerPlayerEntity(server,world,new GameProfile(UUID.randomUUID(),"IssueFive"),new PlayerInteractionManager(world));
        p.connection=new QuietConnection(p);p.setGameType(GameType.SURVIVAL);p.setPosition(32.5,80,0.5);
        // Vanilla clients need player-list information before a tracked player
        // spawn, including the brief spawn-point visit during real respawn.
        server.getPlayerList().sendPacketToAllPlayers(new net.minecraft.network.play.server.SPlayerListItemPacket(
                net.minecraft.network.play.server.SPlayerListItemPacket.Action.ADD_PLAYER,p));
        RuntimeChecks.require(world.addEntity(p),"Fixture player spawn");server.getPlayerList().addPlayer(p);
        p.inventory.currentItem=4;p.inventory.mainInventory.set(4,shovel());p.inventory.mainInventory.set(9,RuntimeChecks.stack(301));
        Map<String,Integer> expected=tagged(p);
        EntityLootableBody first=death(p);RuntimeChecks.require(expected.equals(tagged(first)),"First death lost inventory");
        p=respawn(p);CorpseContainer gui=new CorpseContainer(17,p.inventory,first);p.openContainer=gui;gui.addListener(p);
        click(p,4,0,ClickType.SWAP);
        RuntimeChecks.require(RuntimeChecks.sameStack(p.inventory.mainInventory.get(0),shovel()),"Key1 did not recover original main hand");
        for(int slot=0;slot<48;slot++)if(gui.getSlot(slot).getHasStack())click(p,slot,0,ClickType.QUICK_MOVE);
        RuntimeChecks.require(first.isEmpty() && expected.equals(tagged(p)),"First recovery lost or duplicated tagged inventory: remaining="+inventory(first)+" expected="+expected+" player="+tagged(p)+" canCraft="+gui.getCanCraft(p));
        PacketBuffer close=new PacketBuffer(Unpooled.buffer());
        try {close.writeByte(gui.windowId);CCloseWindowPacket packet=new CCloseWindowPacket();packet.readPacketData(close);p.connection.processCloseWindow(packet);}
        finally {close.release();}
        int active=-1;for(int slot=1;slot<9;slot++)if(!(p.inventory.mainInventory.get(slot)).isEmpty() && p.inventory.mainInventory.get(slot).hasTag() && p.inventory.mainInventory.get(slot).getTag().contains("fixture"))active=slot;
        RuntimeChecks.require(active>0,"Shift-click did not leave a different occupied hotbar slot");
        PacketBuffer held=new PacketBuffer(Unpooled.buffer());
        try {held.writeShort(active);CHeldItemChangePacket packet=new CHeldItemChangePacket();packet.readPacketData(held);p.connection.processHeldItemChange(packet);}
        finally {held.release();}
        // Deliberately do not tick/sync container or reopen inventory here.
        EntityLootableBody second=death(p);
        RuntimeChecks.require(expected.equals(tagged(second)),"Second death lost original key1 shovel or another exact stack");
        RuntimeChecks.require(RuntimeChecks.sameStack(second.getStackInSlot(4),RuntimeChecks.stack(301)),"Second death active hand differs");
        System.out.println("ISSUE5_REAL_RESPAWN_AND_HELD_ITEM_PACKET_PASS");
        p=respawn(p);server.getPlayerList().playerLoggedOut(p);
        CompoundNBT n=new CompoundNBT();second.writeUnlessPassenger(n);
        EntityLootableBody loaded=new EntityLootableBody(world);loaded.read(n);
        RuntimeChecks.require(expected.equals(tagged(loaded)),"Second corpse NBT reload lost recovery");
    }
    private static EntityLootableBody fullBody(int cx,int cz,double offset) {
        RuntimeChecks.awaitChunk(world,cx,cz);
        EntityLootableBody body=new EntityLootableBody(world);body.setUserName("OfflineIssue17");
        body.setPosition(cx*16+offset,80,cz*16+8.5);
        for(int i=0;i<48;i++)body.setInventorySlotContents(i,RuntimeChecks.stack(i));
        body.addItem(RuntimeChecks.stack(70));body.addItem(RuntimeChecks.stack(71));RuntimeChecks.disableVacuum(body);
        RuntimeChecks.require(!body.hasNoGravity(),"Issue17 fixture must use real gravity");
        RuntimeChecks.require(world.addEntity(body),"Physics fixture spawn");return body;
    }
    private static void loadedPlatform() {
        int c=96;for(int x=c-2;x<=c+2;x++)for(int z=c-2;z<=c+2;z++)RuntimeChecks.awaitChunk(world,x,z);
        world.setBlockState(new BlockPos(c*16+8,79,c*16+8),Blocks.STONE.getDefaultState());
        EntityLootableBody b=fullBody(c,c,8.5);Map<String,Integer> expected=inventory(b);
        FixtureApi.awaitTicking(b);
        for(int i=0;i<40;i++)world.updateEntity(b);
        RuntimeChecks.require(b.isOnGround() && b.getPosY()==80 && b.isAlive() && expected.equals(inventory(b)),"Loaded platform/normal gravity lost body or inventory");
    }
    private static void normalTickGate() {
        EntityLootableBody b=fullBody(112,112,8.5);int ticks=b.ticksExisted;
        for(int i=0;i<40;i++)world.updateEntity(b);
        RuntimeChecks.require(b.ticksExisted==ticks && b.getPosY()==80 && !world.getChunkProvider().chunkExists(113,112),"Vanilla unloaded-neighbour tick guard did not hold");
    }
    private static void unloadReload() throws Exception {
        EntityLootableBody b=fullBody(128,128,8.5);UUID uuid=b.getUniqueID();Map<String,Integer> expected=inventory(b);
        unloadChunk(128,128);world.tick(()->true);
        RuntimeChecks.require(!world.getChunkProvider().chunkExists(128,128),"Fixture chunk did not actually unload");
        RuntimeChecks.require(!FixtureApi.entities(world).contains(b),"Unloaded corpse still receives normal world ticks");
        RuntimeChecks.awaitChunk(world,128,128);EntityLootableBody loaded=RuntimeChecks.find(world,uuid);
        RuntimeChecks.require(loaded.isAlive() && expected.equals(inventory(loaded)),"Normal chunk unload/load lost inventory");
    }
    private static final class Support implements AutoCloseable {
        EntityLootableBody body; int cx,cz,nx,nz; BlockPos platform;
        boolean forced;
        Map<String,Integer> expected;
        void unload(){world.forceChunk(cx,cz,false);net.minecraft.util.math.ChunkPos pos=new net.minecraft.util.math.ChunkPos(cx,cz);world.getChunkProvider().registerTicket(net.minecraft.world.server.TicketType.FORCED,pos,0,pos);unloadChunk(nx,nz);RuntimeChecks.require(!world.getChunkProvider().chunkExists(nx,nz),"Support chunk did not unload");}
        void frozen(){for(int i=0;i<100;i++)body.tick();System.out.println("ISSUE17_UNLOADED_SUPPORT y="+body.getPosY()+" alive="+body.isAlive()+" inventory="+inventory(body).size()+" supportLoaded="+world.getChunkProvider().chunkExists(nx,nz));RuntimeChecks.require(body.isAlive() && body.getPosY()==80 && expected.equals(inventory(body)),"Corpse fell through unloaded neighbour support and lost recoverable inventory");}
        void resume(){RuntimeChecks.awaitChunk(world,nx,nz);FixtureApi.awaitTicking(body);for(int i=0;i<10;i++)world.updateEntity(body);RuntimeChecks.require(body.isOnGround() && body.getPosY()==80 && body.addedToChunk && expected.equals(inventory(body)),"Corpse failed to resume safely after support terrain loaded");}
        @Override public void close(){if(forced)world.forceChunk(cx,cz,false);net.minecraft.util.math.ChunkPos pos=new net.minecraft.util.math.ChunkPos(cx,cz);world.getChunkProvider().releaseTicket(net.minecraft.world.server.TicketType.FORCED,pos,0,pos);}
    }
    private static Support support(int axis,int direction) {
        Support f=new Support();f.cx=144+(sequence++)*8;f.cz=f.cx;
        f.nx=f.cx+(axis==0?direction:0);f.nz=f.cz+(axis==1?direction:0);
        RuntimeChecks.awaitChunk(world,f.cx,f.cz);RuntimeChecks.awaitChunk(world,f.nx,f.nz);
        double x=f.cx*16+(axis==0?(direction>0?15.6:.4):8.5);
        double z=f.cz*16+(axis==1?(direction>0?15.6:.4):8.5);
        int floorX=MathHelper.floor(x),floorZ=MathHelper.floor(z);
        f.platform=new BlockPos(floorX+(axis==0?direction:0),79,floorZ+(axis==1?direction:0));
        // A body can straddle a chunk boundary while its centre remains in the ticketed chunk.
        // Remove only the two fixture columns' ground, leaving one thin supporting platform.
        for(int y=0;y<5;y++){world.removeBlock(new BlockPos(floorX,y,floorZ),false);world.removeBlock(new BlockPos(f.platform.getX(),y,f.platform.getZ()),false);}
        world.setBlockState(f.platform,Blocks.STONE.getDefaultState());
        f.body=fullBody(f.cx,f.cz,8.5);f.body.setPosition(x,80,z);f.expected=inventory(f.body);
        f.forced=world.forceChunk(f.cx,f.cz,true);
        RuntimeChecks.require(f.forced && world.getChunkProvider().chunkExists(f.cx,f.cz),"Native forced chunk unavailable");
        FixtureApi.awaitTicking(f.body);
        for(int i=0;i<10;i++)world.updateEntity(f.body);
        RuntimeChecks.require(f.body.isOnGround() && f.body.getPosY()==80 && f.body.isAlive(),"Loaded neighbour platform control failed");
        return f;
    }
    private static void forcedSupport(int axis,int direction) {
        try(Support f=support(axis,direction)) {
            CompoundNBT before=new CompoundNBT();f.body.writeUnlessPassenger(before);
            f.unload();f.frozen();
            CompoundNBT after=new CompoundNBT();f.body.writeUnlessPassenger(after);
            RuntimeChecks.require(before.equals(after),"Waiting for terrain changed healthy saved NBT");
            f.resume();
        }
    }
    private static void realVoid() {
        try(Support f=support(0,1)) {
            world.removeBlock(f.platform,false);
            // 1.11 applies ordinary 4-HP void hits with hurt-resistance intervals.
            // Allow that native sequence to finish; retain the same conservation gate.
            for(int i=0;i<250 && f.body.isAlive();i++)world.updateEntity(f.body);
            for(int i=0;i<15;i++)f.body.baseTick();
            System.out.println("VOID_CONTROL y="+f.body.getPosY()+" alive="+f.body.isAlive()+" items="+inventory(f.body).size()+" motion="+f.body.getMotion().y);
            RuntimeChecks.require(!f.body.isAlive() && inventory(f.body).isEmpty(),"Loaded real void behavior changed into a rescue feature");
        }
    }
    private static void unloadChunk(int x,int z) {
        FixtureApi.unload(world,x,z);
    }
    private static void flushChunks() { FixtureApi.flush(server); }

    static void seedPaused() throws Exception {
        try(Support f=support(0,1)) {
            f.body.setUniqueId(UUID.fromString("00000000-0000-4000-8000-000000000117"));
            f.unload();f.frozen();RuntimeChecks.saveExpected(folder,"issue17-paused",f.body);
            try(PrintWriter out=new PrintWriter(new File(folder,"issue17-chunks.txt"),"UTF-8")){out.println(f.cx+" "+f.cz+" "+f.nx+" "+f.nz);}
        }
    }
    static void coldReload(MinecraftServer instance,ServerWorld target,File directory) throws Exception {
        server=instance;world=target;folder=directory;
        String[] coords=new String(java.nio.file.Files.readAllBytes(new File(folder,"issue17-chunks.txt").toPath()),java.nio.charset.StandardCharsets.UTF_8).trim().split(" ");
        int cx=Integer.parseInt(coords[0]),cz=Integer.parseInt(coords[1]),nx=Integer.parseInt(coords[2]),nz=Integer.parseInt(coords[3]);
        RuntimeChecks.awaitChunk(world,cx,cz);
        RuntimeChecks.require(!world.getChunkProvider().chunkExists(nx,nz),"Deferred support chunk opened too early");
        EntityLootableBody b=RuntimeChecks.find(world,UUID.fromString("00000000-0000-4000-8000-000000000117"));
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        for(int i=0;i<100;i++)world.updateEntity(b);
        RuntimeChecks.require(b.isAlive() && b.getPosY()==80,"Cold-loaded paused corpse fell before support loaded");
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++)RuntimeChecks.awaitChunk(world,x,z);
        FixtureApi.awaitTicking(b);
        for(int i=0;i<20;i++)world.updateEntity(b);
        RuntimeChecks.require(b.isOnGround() && b.getPosY()==80 && b.isAlive(),"Cold-loaded corpse failed to resume on restored platform");
        RuntimeChecks.verifyExpected(folder,"issue17-paused",b);
        world.forceChunk(cx,cz,false);
        System.out.println("ISSUE17_COLD_RELOAD_DEFERRED_SUPPORT_AND_COMPLETE_NBT_PASS");
    }
    private static final class QuietConnection extends ServerPlayNetHandler {
        QuietConnection(ServerPlayerEntity p){super(server,new NetworkManager(PacketDirection.SERVERBOUND),p);}
        @Override public void sendPacket(IPacket<?> packet) { }
    }
}
