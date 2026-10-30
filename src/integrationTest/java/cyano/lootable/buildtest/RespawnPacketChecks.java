package cyano.lootable.buildtest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.server.level.ServerLevel;

import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.LootableBodies;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

/** Death/respawn timing matrix, with an independently connected observer. */
public final class RespawnPacketChecks {
    private final MinecraftServer server;
    private int index,phase,ticks,phaseTicks,startupTicks;
    private ServerPlayer dead;
    private ServerPlayer observerDead;
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
        File folder=new File(server.getLevel(FixtureApi.dimensionKey(0)).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(),"respawn-startup-regression");
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
    private ServerPlayer player(String role){return server.getPlayerList().getPlayerByName("Container"+role);}
    private boolean ack(String role,int p){return file(role+".case-"+index+"-"+p).isFile();}
    private void trace(ServerPlayer p){if(p!=null&&traced.add(p.getGameProfile().getName()))RespawnPacketTrace.install(p.connection.getConnection(),file(p.getGameProfile().getName()+"-server-packets.txt"),null);}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END||finished)return;
        try {
            RuntimeChecks.require(++ticks<20000,"Respawn matrix total timeout");
            RuntimeChecks.require(!file("abort").exists(),"Respawn matrix aborted");
            ServerPlayer a=player("A"),b=player("B");trace(a);trace(b);
            if(index==0&&phase==0&&(a==null||b==null||!ack("A",0)||!ack("B",0))) {
                RuntimeChecks.require(++startupTicks<2400,"Clients did not finish startup before the respawn matrix");
                return;
            }
            phaseTicks++;
            if(phaseTicks%100==0)System.out.println("RESPAWN_MATRIX_STATE case="+index+" phase="+phase+" A="+(a==null?null:a.blockPosition())+" body="+(body==null?null:body.blockPosition())+" tracked="+(body==null?null:FixtureApi.tracked(body)));
            RuntimeChecks.require(phaseTicks<500,"Corpse missing after accepted respawn case="+index+" phase="+phase);
            if(phase==0 && a!=null&&b!=null&&ack("A",0)&&ack("B",0)) {
                int kind=index%8;net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim=kind==7?net.minecraft.world.level.Level.NETHER:net.minecraft.world.level.Level.OVERWORLD;ServerLevel w=server.getLevel(dim);
                if(a.level.dimension()!=dim)transfer(a,dim);
                if(b.level.dimension()!=dim)transfer(b,dim);
                double x=kind==6?-.4:96.5,z=kind==6?-.4:96.5;
                for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++) {
                    BlockPos floor=new BlockPos(x+dx,79,z+dz);w.setBlockAndUpdate(floor,Blocks.OBSIDIAN.defaultBlockState());
                    for(int y=80;y<=84;y++)w.removeBlock(new BlockPos(x+dx,y,z+dz),false);
                }
                w.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,null);w.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
                a.setGameMode(GameType.SURVIVAL);b.setGameMode(GameType.SURVIVAL);a.getInventory().clearContent();b.getInventory().clearContent();
                FixtureApi.spawn(a,new BlockPos(kind==5?160:(int)Math.floor(x),80,(int)Math.floor(z)-3),true,false,a.level.dimension());
                FixtureApi.spawn(b,new BlockPos((int)Math.floor(x),80,(int)Math.floor(z)-3),true,false,b.level.dimension());
                if(kind==7)FixtureApi.spawn(a,new BlockPos(96,80,93),true,false,a.level.dimension());
                for(int dx=157;dx<=164;dx++)for(int dz=90;dz<=98;dz++)w.setBlockAndUpdate(new BlockPos(dx,79,dz),Blocks.OBSIDIAN.defaultBlockState());
                a.getInventory().items.set(9,RuntimeChecks.stack(520+index));a.containerMenu.broadcastChanges();
                if(simultaneous){b.getInventory().items.set(9,RuntimeChecks.stack(620+index));b.containerMenu.broadcastChanges();}
                a.connection.teleport(x,80,z,0,0);b.connection.teleport(kind==4?160.5:x+2,80,z-2.5,0,25);
                state(1);
            } else if(phase==1&&ack("A",1)&&ack("B",1)&&phaseTicks>10) {
                dead=a;Set<Entity> before=new HashSet<>(FixtureApi.entities(a.level));a.kill();body=null;
                for(Entity ent:FixtureApi.entities(a.level))if(ent instanceof EntityLootableBody&&!before.contains(ent))body=(EntityLootableBody)ent;
                RuntimeChecks.require(body!=null,"No server death corpse");state(2);
                if(simultaneous) {
                    observerDead=b;before=new HashSet<>(FixtureApi.entities(b.level));b.kill();observerBody=null;
                    for(Entity ent:FixtureApi.entities(b.level))if(ent instanceof EntityLootableBody&&!before.contains(ent))observerBody=(EntityLootableBody)ent;
                    RuntimeChecks.require(observerBody!=null,"No observer death corpse");
                }
            } else if(phase==2&&a!=null&&a!=dead&&a.isAlive()&&(!simultaneous||(b!=null&&b!=observerDead&&b.isAlive()))) {
                RuntimeChecks.require(body.isAlive()&&VisibilityChecks.has(body,520+index),"Server corpse/inventory lost during respawn");
                if(a.level.dimension()!=body.level.dimension())transfer(a,body.level.dimension());
                a.connection.teleport(body.getX(),body.getY(),body.getZ()-2.5,0,25);
                if(b.level.dimension()!=body.level.dimension())transfer(b,body.level.dimension());
                b.connection.teleport(body.getX()+1.5,body.getY(),body.getZ()-2.5,0,25);
                // With no players left, Forge may unload/reload the Nether between
                // death and return. Saved UUIDs survive; runtime entity IDs do not.
                body=live(body);if(simultaneous)observerBody=live(observerBody);state(3);
            } else if(phase==3) {
                // In a cold restart, many ordinary saved bodies can collide and
                // move the new corpse beyond the player's interaction reach.
                // Follow it with the viewer; never freeze production physics.
                if(a!=null&&a.distanceToSqr(body)>9)a.connection.teleport(body.getX(),body.getY(),body.getZ()-2.5,0,25);
                if(phaseTicks==10){writeTracked(a,"A");writeTracked(b,"B");}
                if(!ack("A",3)||!ack("B",3))return;
                RuntimeChecks.require(a.containerMenu instanceof cyano.lootable.graphics.CorpseContainer,"Owner GUI not backed by server container");
                RuntimeChecks.require(body.isAlive()&&VisibilityChecks.has(body,520+index),"Corpse lost exact tagged items");
                if(simultaneous)RuntimeChecks.require(observerBody.isAlive()&&VisibilityChecks.has(observerBody,620+index),"Observer corpse lost exact tagged items");
                try(FileWriter out=new FileWriter(file("checks.txt"),true)){out.write("PASS case="+index+" kind="+(index%8)+" owner_and_observer_after_respawn\n");}
                a.closeContainer();b.closeContainer();index++;
                if(index==count){state(9);}else state(0);
            } else if(phase==9&&server.getPlayerList().getPlayerCount()==0) {
                Files.write(file("respawn.pass").toPath(),new byte[]{1});System.out.println("LOOTABLE_BODIES_RESPAWN_MATRIX_PASS");finished=true;server.halt(false);
            }
        } catch(Throwable failure){failure.printStackTrace();try{Files.write(file("server.failure").toPath(),failure.toString().getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}finished=true;server.halt(false);}
    }
    private void state(int value) throws Exception {
        phase=value;phaseTicks=0;String line=index+" "+phase+" "+(body==null?"none":body.getUUID())+" "+(body==null?-1:body.getId());
        File tmp=file("state.tmp");Files.write(tmp.toPath(),line.getBytes(StandardCharsets.UTF_8));
        // Windows readers briefly deny replacement while their handle is open.
        for(int retry=0;;retry++)try{Files.move(tmp.toPath(),file("state").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);break;}
        catch(java.nio.file.AccessDeniedException busy){if(retry==50)throw busy;Thread.sleep(1);}
        System.out.println("RESPAWN_MATRIX_PHASE "+line);
    }
    private void transfer(ServerPlayer player,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        ServerLevel target=server.getLevel(dimension);
        // This is an absolute fixture move, not a portal-generation check.
        // Use the native16 command path, including its real respawn packet.
        player.teleportTo(target,96.5,80,96.5,player.getYRot(),0);

    }
    private void writeTracked(ServerPlayer player,String role) throws Exception {
        List<String> expected=new ArrayList<>();ServerLevel w=player.getLevel();
        for(Entity e:FixtureApi.entities(w))if(e instanceof EntityLootableBody&&e.isAlive()&&FixtureApi.tracked(e).contains(player))expected.add(e.getId()+" "+e.getUUID());
        Collections.sort(expected);Files.write(file("expected-"+index+"-"+role+".txt").toPath(),expected,StandardCharsets.UTF_8);
    }
    private EntityLootableBody live(EntityLootableBody previous) {
        ServerLevel w=server.getLevel(previous.level.dimension());
        w.getChunk((int)Math.floor(previous.getX()/16),(int)Math.floor(previous.getZ()/16));
        return RuntimeChecks.find(w,previous.getUUID());
    }
}
