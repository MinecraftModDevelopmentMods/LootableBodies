package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.LootableBodies;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.*;

/** Death/respawn timing matrix, with an independently connected observer. */
public final class RespawnPacketChecks {
    private final MinecraftServer server;
    private int index,phase,ticks,phaseTicks,startupTicks;
    private EntityPlayerMP dead;
    private EntityPlayerMP observerDead;
    private EntityLootableBody body;
    private EntityLootableBody observerBody;
    private final boolean simultaneous=Boolean.getBoolean("lootablebodies.respawn.simultaneous");
    private final int count=Integer.getInteger("lootablebodies.respawn.cases",16);
    private final Set<String> traced=new HashSet<>();
    private boolean finished;
    RespawnPacketChecks(MinecraftServer server) throws Exception {
        RuntimeChecks.require(!LootableBodies.useLocalSkin&&LootableBodies.allowCorpseDecay&&LootableBodies.decayOnlyWhenEmpty
            &&LootableBodies.ticksPerItemDecay==6000&&LootableBodies.corpseDecayTime==72000&&LootableBodies.corpseHP==20
            &&!LootableBodies.hurtByEnvironment&&!LootableBodies.hurtByAttacks&&!LootableBodies.hurtByMisc,"Respawn matrix requires production defaults");
        this.server=server;state(0);Files.write(file("server.ready").toPath(),new byte[]{1});net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    static void verifySlowClientStartup(MinecraftServer server) throws Exception {
        String previous=System.getProperty("lootablebodies.multiplayer.sync");
        File folder=new File(server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getSaveHandler().getWorldDirectory(),"respawn-startup-regression");
        Files.createDirectories(folder.toPath());
        RespawnPacketChecks fixture=null;
        try {
            System.setProperty("lootablebodies.multiplayer.sync",folder.getAbsolutePath());
            fixture=new RespawnPacketChecks(server);
            // Cold software-rendered clients may need more than 500 server ticks
            // to reach their first login. Exercise the actual fixture tick path.
            for(int tick=0;tick<1000;tick++)fixture.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            RuntimeChecks.require(!fixture.finished&&fixture.index==0&&fixture.phase==0&&fixture.phaseTicks==0,
                "Client startup consumed the death/respawn case deadline");
            System.out.println("RESPAWN_STARTUP_CLOCK_PASS waiting_ticks=1000 case_ticks=0");
        } finally {
            if(fixture!=null)net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(fixture);
            if(previous==null)System.clearProperty("lootablebodies.multiplayer.sync");
            else System.setProperty("lootablebodies.multiplayer.sync",previous);
        }
    }
    static File file(String name){return new File(System.getProperty("lootablebodies.multiplayer.sync"),name);}
    private EntityPlayerMP player(String role){return server.getPlayerList().getPlayerByUsername("Container"+role);}
    private boolean ack(String role,int p){return file(role+".case-"+index+"-"+p).isFile();}
    private void trace(EntityPlayerMP p){if(p!=null&&traced.add(p.getGameProfile().getName()))RespawnPacketTrace.install(p.connection.netManager,file(p.getGameProfile().getName()+"-server-packets.txt"),null);}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END||finished)return;
        try {
            RuntimeChecks.require(++ticks<20000,"Respawn matrix total timeout");
            RuntimeChecks.require(!file("abort").exists(),"Respawn matrix aborted");
            EntityPlayerMP a=player("A"),b=player("B");trace(a);trace(b);
            if(index==0&&phase==0&&(a==null||b==null||!ack("A",0)||!ack("B",0))) {
                RuntimeChecks.require(++startupTicks<2400,"Clients did not finish startup before the respawn matrix");
                return;
            }
            phaseTicks++;
            if(phaseTicks%100==0)System.out.println("RESPAWN_MATRIX_STATE case="+index+" phase="+phase+" A="+(a==null?null:a.getPosition())+" body="+(body==null?null:body.getPosition())+" tracked="+(body==null?null:body.world.getServer().getWorld(body.dimension).getEntityTracker().getTrackingPlayers(body)));
            RuntimeChecks.require(phaseTicks<500,"Corpse missing after accepted respawn case="+index+" phase="+phase);
            if(phase==0 && a!=null&&b!=null&&ack("A",0)&&ack("B",0)) {
                int kind=index%8;net.minecraft.world.dimension.DimensionType dim=kind==7?net.minecraft.world.dimension.DimensionType.NETHER:net.minecraft.world.dimension.DimensionType.OVERWORLD;WorldServer w=server.getWorld(dim);
                if(a.dimension!=dim)transfer(a,dim);
                if(b.dimension!=dim)transfer(b,dim);
                double x=kind==6?-.4:96.5,z=kind==6?-.4:96.5;
                for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++) {
                    BlockPos floor=new BlockPos(x+dx,79,z+dz);w.setBlockState(floor,Blocks.OBSIDIAN.getDefaultState());
                    for(int y=80;y<=84;y++)w.removeBlock(new BlockPos(x+dx,y,z+dz));
                }
                w.getGameRules().setOrCreateGameRule("doMobSpawning","false",null);w.getGameRules().setOrCreateGameRule("keepInventory","false",null);
                a.setGameType(GameType.SURVIVAL);b.setGameType(GameType.SURVIVAL);a.inventory.clear();b.inventory.clear();
                a.setSpawnPoint(new BlockPos(kind==5?160:(int)Math.floor(x),80,(int)Math.floor(z)-3),true);
                b.setSpawnPoint(new BlockPos((int)Math.floor(x),80,(int)Math.floor(z)-3),true);
                if(kind==7)a.setSpawnPoint(new BlockPos(96,80,93),true);
                for(int dx=157;dx<=164;dx++)for(int dz=90;dz<=98;dz++)w.setBlockState(new BlockPos(dx,79,dz),Blocks.OBSIDIAN.getDefaultState());
                a.inventory.mainInventory.set(9,RuntimeChecks.stack(520+index));a.inventoryContainer.detectAndSendChanges();
                if(simultaneous){b.inventory.mainInventory.set(9,RuntimeChecks.stack(620+index));b.inventoryContainer.detectAndSendChanges();}
                a.connection.setPlayerLocation(x,80,z,0,0);b.connection.setPlayerLocation(kind==4?160.5:x+2,80,z-2.5,0,25);
                state(1);
            } else if(phase==1&&ack("A",1)&&ack("B",1)&&phaseTicks>10) {
                dead=a;Set<Entity> before=new HashSet<>(a.world.loadedEntityList);a.onKillCommand();body=null;
                for(Entity ent:a.world.loadedEntityList)if(ent instanceof EntityLootableBody&&!before.contains(ent))body=(EntityLootableBody)ent;
                RuntimeChecks.require(body!=null,"No server death corpse");state(2);
                if(simultaneous) {
                    observerDead=b;before=new HashSet<>(b.world.loadedEntityList);b.onKillCommand();observerBody=null;
                    for(Entity ent:b.world.loadedEntityList)if(ent instanceof EntityLootableBody&&!before.contains(ent))observerBody=(EntityLootableBody)ent;
                    RuntimeChecks.require(observerBody!=null,"No observer death corpse");
                }
            } else if(phase==2&&a!=null&&a!=dead&&a.isAlive()&&(!simultaneous||(b!=null&&b!=observerDead&&b.isAlive()))) {
                RuntimeChecks.require(body.isAlive()&&VisibilityChecks.has(body,520+index),"Server corpse/inventory lost during respawn");
                if(a.dimension!=body.dimension)transfer(a,body.dimension);
                a.connection.setPlayerLocation(body.posX,body.posY,body.posZ-2.5,0,25);
                if(b.dimension!=body.dimension)transfer(b,body.dimension);
                b.connection.setPlayerLocation(body.posX+1.5,body.posY,body.posZ-2.5,0,25);
                // With no players left, Forge may unload/reload the Nether between
                // death and return. Saved UUIDs survive; runtime entity IDs do not.
                body=live(body);if(simultaneous)observerBody=live(observerBody);state(3);
            } else if(phase==3) {
                if(phaseTicks==10){writeTracked(a,"A");writeTracked(b,"B");}
                if(!ack("A",3)||!ack("B",3))return;
                RuntimeChecks.require(a.openContainer instanceof cyano.lootable.graphics.CorpseContainer,"Owner GUI not backed by server container");
                RuntimeChecks.require(body.isAlive()&&VisibilityChecks.has(body,520+index),"Corpse lost exact tagged items");
                if(simultaneous)RuntimeChecks.require(observerBody.isAlive()&&VisibilityChecks.has(observerBody,620+index),"Observer corpse lost exact tagged items");
                try(FileWriter out=new FileWriter(file("checks.txt"),true)){out.write("PASS case="+index+" kind="+(index%8)+" owner_and_observer_after_respawn\n");}
                a.closeScreen();b.closeScreen();index++;
                if(index==count){state(9);}else state(0);
            } else if(phase==9&&server.getPlayerList().getCurrentPlayerCount()==0) {
                Files.write(file("respawn.pass").toPath(),new byte[]{1});System.out.println("LOOTABLE_BODIES_RESPAWN_MATRIX_PASS");finished=true;server.initiateShutdown();
            }
        } catch(Throwable failure){failure.printStackTrace();try{Files.write(file("server.failure").toPath(),failure.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}finished=true;server.initiateShutdown();}
    }
    private void state(int value) throws Exception {
        phase=value;phaseTicks=0;String line=index+" "+phase+" "+(body==null?"none":body.getUniqueID())+" "+(body==null?-1:body.getEntityId());
        File tmp=file("state.tmp");Files.write(tmp.toPath(),line.getBytes(StandardCharsets.UTF_8));
        // Windows readers briefly deny replacement while their handle is open.
        for(int retry=0;;retry++)try{Files.move(tmp.toPath(),file("state").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);break;}
        catch(java.nio.file.AccessDeniedException busy){if(retry==50)throw busy;Thread.sleep(1);}
        System.out.println("RESPAWN_MATRIX_PHASE "+line);
    }
    private void transfer(EntityPlayerMP player,net.minecraft.world.dimension.DimensionType dimension) {
        WorldServer target=server.getWorld(dimension);
        player.changeDimension(dimension,new Teleporter(target){
            @Override public void placeInPortal(Entity entity,float yaw){entity.setLocationAndAngles(96.5,80,96.5,yaw,0);}
        });
    }
    private void writeTracked(EntityPlayerMP player,String role) throws Exception {
        List<String> expected=new ArrayList<>();WorldServer w=player.getServerWorld();
        for(Entity e:w.loadedEntityList)if(e instanceof EntityLootableBody&&e.isAlive()&&w.getEntityTracker().getTrackingPlayers(e).contains(player))expected.add(e.getEntityId()+" "+e.getUniqueID());
        Collections.sort(expected);Files.write(file("expected-"+index+"-"+role+".txt").toPath(),expected,StandardCharsets.UTF_8);
    }
    private EntityLootableBody live(EntityLootableBody previous) {
        WorldServer w=server.getWorld(previous.dimension);
        w.getChunk((int)Math.floor(previous.posX/16),(int)Math.floor(previous.posZ/16));
        return RuntimeChecks.find(w,previous.getUniqueID());
    }
}
