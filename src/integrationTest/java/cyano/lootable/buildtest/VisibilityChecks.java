package cyano.lootable.buildtest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;

/** Default-config real deaths and tracking, coordinated with two independent TCP clients. */
public final class VisibilityChecks {
    // Match the native flat world's respawn surface instead of an elevated platform.
    private static final int GROUND_Y=3, PLAYER_Y=4;
    private final MinecraftServer server;
    private int phase,ticks,phaseTicks;
    private long waitStart;
    private EntityLootableBody first,second;
    private Set<Entity> before;
    private boolean finished;
    private final boolean resumed=Boolean.getBoolean("lootablebodies.visibility.reload");
    public VisibilityChecks(MinecraftServer server) throws Exception {
        this.server=server;
        RuntimeChecks.require(!LootableBodies.useLocalSkin && LootableBodies.allowCorpseDecay && LootableBodies.decayOnlyWhenEmpty
            && LootableBodies.ticksPerItemDecay==6000 && LootableBodies.corpseDecayTime==72000
            && !LootableBodies.hurtByEnvironment && !LootableBodies.hurtByAttacks && !LootableBodies.hurtByMisc,"Visibility defaults changed");
        phase(0);Files.write(file("server.ready").toPath(),new byte[]{1});net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    static File file(String name){return new File(System.getProperty("lootablebodies.multiplayer.sync"),name);}
    private ServerPlayer player(String role){return server.getPlayerList().getPlayerByName("Container"+role);}
    private boolean ack(String role,int n){return file(role+".visibility-"+n).isFile();}
    private boolean both(int n){return ack("A",n)&&ack("B",n);}
    private ServerLevel world(){return server.getLevel(FixtureApi.dimensionKey(0));}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || finished)return;
        try {
            RuntimeChecks.require(++ticks<14000,"Visibility server timeout phase="+phase);phaseTicks++;
            RuntimeChecks.require(!file("abort").exists(),"Visibility runner aborted");
            ServerPlayer a=player("A"),b=player("B");
            if(phase==4 && Boolean.getBoolean("lootablebodies.visibility.offsetFirstDeath"))
                RuntimeChecks.require(first.stillValid(a) && first.stillValid(b),
                    "Offset death fixture left a viewer outside corpse reach: first="+first.blockPosition()+" A="+a.blockPosition()+" B="+b.blockPosition());
            if(phase==3 && a!=null && a.isAlive())
                RuntimeChecks.require(Math.abs(a.getX()-first.getX())<28 && Math.abs(a.getZ()-first.getZ())<28 && Math.abs(a.getY()-first.getY())<8,
                    "Visibility fixture respawned outside its corpse viewing area: "+a.blockPosition()+" corpse="+first.blockPosition());
            if(phaseTicks%100==0 && phase!=12)System.out.println("VISIBILITY_SERVER_STATE phase="+phase+" A="+(a==null?null:a.blockPosition())+" B="+(b==null?null:b.blockPosition())+" first="+(first==null?null:first.blockPosition())+" firstUsableA="+(first!=null&&first.stillValid(a))+" firstUsableB="+(first!=null&&first.stillValid(b))+" corpse="+(second==null?null:second.blockPosition())+" usableA="+(second!=null&&second.stillValid(a))+" usableB="+(second!=null&&second.stillValid(b)));
            if(phase==0 && resumed && a!=null && b!=null && both(0)) {
                // Only this disposable tracking fixture moves the world spawn.
                // The corpse chunk must be eligible to unload when both clients
                // leave; keeping it inside the spawn area would test no unload.
                world().setDefaultSpawnPos(new BlockPos(8192,PLAYER_Y,8192),0F);
                CompoundTag n;try(FileInputStream in=new FileInputStream(new File(world().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(),"visibility-state.nbt"))){n=NbtIo.readCompressed(in);}
                first=loadExpected(world(),"first");second=loadExpected(world(),"second");
                saveId("first",first);saveId("second",second);verifyInventories();
                a.connection.teleport(first.getX(),first.getY(),first.getZ()-2.5,0,25);b.connection.teleport(first.getX()+1.5,first.getY(),first.getZ()-2.5,0,25);phase(19);
            } else if(phase==0 && a!=null && b!=null && both(0)) {
                ServerLevel w=world();w.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,null);w.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(false,null);
                // Forge25's forced player spawn can fall back to world spawn.
                // Keep this disposable respawn within the intended viewing area.
                w.setDefaultSpawnPos(new BlockPos(96,PLAYER_Y,93),0F);server.getCommands().performCommand(server.createCommandSourceStack(),"gamerule spawnRadius 0");
                w.setDayTime(6000);
                for(int x=88;x<=170;x++)for(int z=89;z<=103;z++)w.setBlockAndUpdate(new BlockPos(x,GROUND_Y,z),Blocks.OBSIDIAN.defaultBlockState());
                for(ServerPlayer p:new ServerPlayer[]{a,b}) {p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();FixtureApi.spawn(p,new BlockPos(96,PLAYER_Y,93),true,false,p.level.dimension());}
                a.connection.teleport(96.5,PLAYER_Y,96.5,0,0);b.connection.teleport(98.5,PLAYER_Y,93.5,0,25);
                a.getInventory().items.set(9,RuntimeChecks.stack(410));a.containerMenu.broadcastChanges();phase(1);
            } else if(phase==1 && both(1)) {
                // A real death offset exposes assumptions about the corpse's coordinates.
                if(Boolean.getBoolean("lootablebodies.visibility.offsetFirstDeath"))
                    a.connection.teleport(96.5,PLAYER_Y,98.5,0,0);
                before=new HashSet<>(FixtureApi.entities(world()));a.kill();first=newCorpse();saveId("first",first);phase(2);
            } else if(phase==2 && both(2)) {mark("both_clients_receive_actual_death_before_respawn");phase(3);
            } else if(phase==3 && both(3) && a!=null && a.isAlive()) {
                RuntimeChecks.require(first.isAlive(),"Server lost corpse after respawn");
                a.connection.teleport(first.getX(),first.getY(),first.getZ()-2.5,0,25);
                b.connection.teleport(first.getX()+1.5,first.getY(),first.getZ()-2.5,0,25);phase(4);
            } else if(phase==4 && both(4)) {
                RuntimeChecks.require(a.containerMenu instanceof cyano.lootable.graphics.CorpseContainer && b.containerMenu instanceof cyano.lootable.graphics.CorpseContainer,"GUI ack without server access");
                mark("both_clients_render_and_open_corpse_after_respawn");phase(5);
            } else if(phase==5 && both(5)) {
                a.closeContainer();b.closeContainer();a.connection.teleport(160.5,PLAYER_Y,93.5,0,25);phase(6);
            } else if(phase==6 && both(6)) {
                RuntimeChecks.require(FixtureApi.tracked(first).contains(b) && !FixtureApi.tracked(first).contains(a),"Range assertions differ from tracker");
                mark("range_exit_removes_only_distant_client");a.connection.teleport(96.5,PLAYER_Y,93.5,0,25);phase(7);
            } else if(phase==7 && both(7)) {mark("range_return_respawns_same_uuid");phase(8);
            } else if(phase==8 && a==null) {phase(9);
            } else if(phase==9 && a!=null && both(9)) {
                mark("reconnect_owner_with_observer_still_online");phase(10);
            } else if(phase==10 && b==null) {phase(11);
            } else if(phase==11 && b!=null && both(11)) {
                mark("reconnect_observer_with_owner_still_online");waitStart=System.nanoTime();phase(12);
            } else if(phase==12 && phaseTicks>Integer.getInteger("lootablebodies.visibility.waitTicks",6200) && (System.nanoTime()-waitStart)>Long.getLong("lootablebodies.visibility.waitMillis",310000L)*1000000L && both(12)) {
                RuntimeChecks.require(first.isAlive() && has(first,410),"Default corpse lost before five-minute TNT test");
                phase(120);
            } else if(phase==120 && both(120)) {
                mark(Integer.getInteger("lootablebodies.visibility.waitTicks",6200)==6200?"default_corpse_visible_after_6200_ticks_and_310_seconds":"short_fixture_control_default_corpse_visible");
                a.connection.teleport(100.5,PLAYER_Y,96.5,0,0);b.connection.teleport(102.5,PLAYER_Y,93.5,0,25);
                a.getInventory().items.set(9,RuntimeChecks.stack(411));a.containerMenu.broadcastChanges();phase(13);
            } else if(phase==13 && both(13)) {
                before=new HashSet<>(FixtureApi.entities(world()));PrimedTnt tnt=new PrimedTnt(world(),a.getX(),a.getY(),a.getZ(),null);tnt.setFuse(0);
                RuntimeChecks.require(world().addFreshEntity(tnt),"TNT fixture spawn");phase(14);
            } else if(phase==14) {
                if(second==null && a!=null && !a.isAlive()) {second=newCorpse();saveId("second",second);}
                if(second!=null && both(14)) {RuntimeChecks.require(second.isAlive() && has(second,411),"TNT destroyed default corpse/inventory");mark("tnt_death_after_five_minutes_both_clients_receive_corpse");phase(15);}
            } else if(phase==15 && both(15) && a!=null && a.isAlive()) {
                // TNT can displace both the observer and the death position.
                a.connection.teleport(second.getX(),second.getY(),second.getZ()-2.5,0,25);
                b.connection.teleport(second.getX()+1.5,second.getY(),second.getZ()-2.5,0,25);phase(16);
            } else if(phase==16 && both(16)) {mark("both_clients_render_and_open_tnt_corpse_after_respawn");phase(17);
            } else if(phase==17 && both(17) && phaseTicks>20) {
                RuntimeChecks.require(!has(second,411) && playerHas(a,411),"TNT corpse recovery lost or duplicated tagged stack");
                RuntimeChecks.require(has(first,410),"Earlier corpse inventory changed during TNT/reconnect checks");
                a.closeContainer();b.closeContainer();File folder=world().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
                RuntimeChecks.saveExpected(folder,"visibility-first",first);RuntimeChecks.saveExpected(folder,"visibility-second",second);
                CompoundTag state=new CompoundTag();state.putUUID("First",first.getUUID());state.putUUID("Second",second.getUUID());state.putUUID("Player",a.getUUID());state.put("Inventory",a.getInventory().save(new ListTag()));
                try(FileOutputStream out=new FileOutputStream(new File(folder,"visibility-state.nbt"))){NbtIo.writeCompressed(state,out);}
                server.getPlayerList().saveAll();server.saveAllChunks(false,true,false);mark("tagged_inventory_recovered_and_complete_nbt_saved");phase(18);
            } else if(phase==18 && server.getPlayerList().getPlayerCount()==0) {
                Files.write(file("visibility.pass").toPath(),new byte[]{1});System.out.println("LOOTABLE_BODIES_VISIBILITY_PASS");finished=true;server.halt(false);
            } else if(phase==19 && both(19)) {
                mark("cold_server_restart_both_clients_render_and_open_saved_corpse");a.closeContainer();b.closeContainer();
                for(int x=397;x<=404;x++)for(int z=397;z<=404;z++)world().setBlockAndUpdate(new BlockPos(x,GROUND_Y,z),Blocks.OBSIDIAN.defaultBlockState());
                a.connection.teleport(400.5,PLAYER_Y,400.5,0,25);b.connection.teleport(402.5,PLAYER_Y,400.5,0,25);phase(20);
            } else if(phase==20 && both(20) && phaseTicks>240 && !world().getChunkSource().hasChunk(first.chunkPosition().x,first.chunkPosition().z) && !world().getChunkSource().hasChunk(second.chunkPosition().x,second.chunkPosition().z)) {
                mark("both_clients_leave_and_corpse_chunk_really_unloads");
                FixtureApi.flush(server);
                a.connection.teleport(first.getX(),first.getY(),first.getZ()-2.5,0,25);b.connection.teleport(first.getX()+1.5,first.getY(),first.getZ()-2.5,0,25);phase(21);
            } else if(phase==21 && both(21)) {
                first=RuntimeChecks.find(world(),first.getUUID());second=RuntimeChecks.find(world(),second.getUUID());verifyInventories();
                mark("chunk_reentry_both_clients_render_same_uuids_and_inventory");server.getPlayerList().saveAll();server.saveAllChunks(false,true,false);phase(18);
            }
        } catch(Throwable failure) {failure.printStackTrace();finished=true;server.halt(false);}
    }
    private EntityLootableBody newCorpse(){for(Entity e:FixtureApi.entities(world()))if(e instanceof EntityLootableBody&&!before.contains(e))return (EntityLootableBody)e;throw new AssertionError("No server corpse for real death");}
    private void saveId(String name,EntityLootableBody b) throws Exception {Files.write(file(name+".uuid").toPath(),b.getUUID().toString().getBytes(StandardCharsets.UTF_8));}
    private void phase(int n) throws Exception {
        phase=n;phaseTicks=0;File tmp=file("phase.tmp");
        Files.write(tmp.toPath(),Integer.toString(n).getBytes(StandardCharsets.UTF_8));
        // Windows readers can briefly hold this verifier IPC file without delete sharing.
        // Keep atomic publication; every other I/O failure and a persistent lock still fail.
        for(int attempt=0;;attempt++) {
            try {Files.move(tmp.toPath(),file("phase").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);break;}
            catch(AccessDeniedException busy) {if(attempt>=20)throw busy;Thread.sleep(5L);}
        }
        System.out.println("VISIBILITY_PHASE "+n);
    }
    private void mark(String n) throws Exception {try(FileWriter out=new FileWriter(file("checks.txt"),true)){out.write("PASS "+n+"\n");}System.out.println("VISIBILITY_CHECK_PASS "+n);}
    static boolean has(EntityLootableBody b,int i){for(int s=0;s<48;s++)if(RuntimeChecks.sameStack(b.getItem(s),RuntimeChecks.stack(i)))return true;return false;}
    private static boolean playerHas(ServerPlayer p,int i){int count=0;for(int s=0;s<p.getInventory().getContainerSize();s++)if(RuntimeChecks.sameStack(p.getInventory().getItem(s),RuntimeChecks.stack(i)))count++;return count==1;}
    private void verifyInventories() throws Exception {
        verifySnapshot(world(),"first",first);verifySnapshot(world(),"second",second);
    }
    static void reload(MinecraftServer server) throws Exception {
        ServerLevel w=server.getLevel(FixtureApi.dimensionKey(0));File folder=w.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();CompoundTag n;
        try(FileInputStream in=new FileInputStream(new File(folder,"visibility-state.nbt"))){n=NbtIo.readCompressed(in);}
        for(String key:new String[]{"First","Second"}) {EntityLootableBody b=loadExpected(w,key.toLowerCase(Locale.ROOT));RuntimeChecks.require(b.getUUID().equals(n.getUUID(key)),"Saved identity differs");verifySnapshot(w,key.toLowerCase(Locale.ROOT),b);}
        CompoundTag p;try(FileInputStream in=new FileInputStream(new File(folder,"playerdata/"+n.getUUID("Player")+".dat"))){p=NbtIo.readCompressed(in);}
        RuntimeChecks.require(n.get("Inventory").equals(p.get("Inventory")),"Recovered inventory changed on cold restart");
    }
    private static EntityLootableBody loadExpected(ServerLevel w,String name) throws Exception {
        CompoundTag n;try(FileInputStream in=new FileInputStream(new File(w.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(),"visibility-"+name+".nbt"))){n=NbtIo.readCompressed(in);}
        ListTag pos=n.getList("Pos",6);RuntimeChecks.awaitChunk(w,(int)Math.floor(pos.getDouble(0)/16),(int)Math.floor(pos.getDouble(2)/16));
        return RuntimeChecks.find(w,n.getUUID("UUID"));
    }
    private static void verifySnapshot(ServerLevel w,String name,EntityLootableBody body) throws Exception {
        CompoundTag expected;try(FileInputStream in=new FileInputStream(new File(w.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(),"visibility-"+name+".nbt"))){expected=NbtIo.readCompressed(in);}
        CompoundTag actual=new CompoundTag();body.saveAsPassenger(actual);
        for(String key:new String[]{"id","UUIDMost","UUIDLeast","Equipment","Aux","Name","CustomName","LootableBodiesUnresolved","LootableBodiesInvalidFields"})RuntimeChecks.require(Objects.equals(expected.get(key),actual.get(key)),"Reloaded corpse differs: "+name+" "+key);
        // These live-default fixtures keep ticking between the snapshot and logout.
        // Timer equality belongs to the separate frozen-timer persistence tests.
        for(String key:new String[]{"DeathTime","DecayTime"})RuntimeChecks.require(actual.getLong(key)>=expected.getLong(key)&&actual.getLong(key)<=w.getGameTime(),"Reloaded live timer out of range: "+key);
    }
}
