package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.entity.*;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Default-config real deaths and tracking, coordinated with two independent TCP clients. */
public final class VisibilityChecks {
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
        phase(0);Files.write(file("server.ready").toPath(),new byte[]{1});FMLCommonHandler.instance().bus().register(this);
    }
    static File file(String name){return new File(System.getProperty("lootablebodies.multiplayer.sync"),name);}
    private EntityPlayerMP player(String role){return server.getPlayerList().getPlayerByUsername("Container"+role);}
    private boolean ack(String role,int n){return file(role+".visibility-"+n).isFile();}
    private boolean both(int n){return ack("A",n)&&ack("B",n);}
    private WorldServer world(){return server.getWorld(0);}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if(e.phase!=TickEvent.Phase.END || finished)return;
        try {
            RuntimeChecks.require(++ticks<14000,"Visibility server timeout phase="+phase);phaseTicks++;
            RuntimeChecks.require(!file("abort").exists(),"Visibility runner aborted");
            EntityPlayerMP a=player("A"),b=player("B");
            if(phaseTicks%100==0 && phase!=12)System.out.println("VISIBILITY_SERVER_STATE phase="+phase+" A="+(a==null?null:a.getPosition())+" B="+(b==null?null:b.getPosition())+" corpse="+(second==null?null:second.getPosition())+" usableA="+(second!=null&&second.isUsableByPlayer(a))+" usableB="+(second!=null&&second.isUsableByPlayer(b)));
            if(phase==0 && resumed && a!=null && b!=null && both(0)) {
                // Only this disposable tracking fixture moves the world spawn.
                // The corpse chunk must be eligible to unload when both clients
                // leave; keeping it inside the spawn area would test no unload.
                world().setSpawnPoint(new BlockPos(8192,80,8192));
                NBTTagCompound n;try(FileInputStream in=new FileInputStream(new File(world().getSaveHandler().getWorldDirectory(),"visibility-state.nbt"))){n=CompressedStreamTools.readCompressed(in);}
                first=loadExpected(world(),"first");second=loadExpected(world(),"second");
                saveId("first",first);saveId("second",second);verifyInventories();
                a.connection.setPlayerLocation(first.posX,first.posY,first.posZ-2.5,0,25);b.connection.setPlayerLocation(first.posX+1.5,first.posY,first.posZ-2.5,0,25);phase(19);
            } else if(phase==0 && a!=null && b!=null && both(0)) {
                WorldServer w=world();w.getGameRules().setOrCreateGameRule("doMobSpawning","false");w.getGameRules().setOrCreateGameRule("keepInventory","false");
                w.setWorldTime(6000);
                for(int x=88;x<=170;x++)for(int z=89;z<=103;z++)w.setBlockState(new BlockPos(x,79,z),Blocks.OBSIDIAN.getDefaultState());
                for(EntityPlayerMP p:new EntityPlayerMP[]{a,b}) {p.setGameType(GameType.SURVIVAL);p.inventory.clear();p.setSpawnPoint(new BlockPos(96,80,93),true);}
                a.connection.setPlayerLocation(96.5,80,96.5,0,0);b.connection.setPlayerLocation(98.5,80,93.5,0,25);
                a.inventory.mainInventory.set(9,RuntimeChecks.stack(410));a.inventoryContainer.detectAndSendChanges();phase(1);
            } else if(phase==1 && both(1)) {
                before=new HashSet<>(world().loadedEntityList);a.onKillCommand();first=newCorpse();saveId("first",first);phase(2);
            } else if(phase==2 && both(2)) {mark("both_clients_receive_actual_death_before_respawn");phase(3);
            } else if(phase==3 && both(3) && a!=null && a.isEntityAlive()) {
                RuntimeChecks.require(first.isEntityAlive(),"Server lost corpse after respawn");
                a.connection.setPlayerLocation(96.5,80,93.5,0,25);phase(4);
            } else if(phase==4 && both(4)) {
                RuntimeChecks.require(a.openContainer instanceof cyano.lootable.graphics.CorpseContainer && b.openContainer instanceof cyano.lootable.graphics.CorpseContainer,"GUI ack without server access");
                mark("both_clients_render_and_open_corpse_after_respawn");phase(5);
            } else if(phase==5 && both(5)) {
                a.closeScreen();b.closeScreen();a.connection.setPlayerLocation(160.5,80,93.5,0,25);phase(6);
            } else if(phase==6 && both(6)) {
                RuntimeChecks.require(world().getEntityTracker().getTrackingPlayers(first).contains(b) && !world().getEntityTracker().getTrackingPlayers(first).contains(a),"Range assertions differ from tracker");
                mark("range_exit_removes_only_distant_client");a.connection.setPlayerLocation(96.5,80,93.5,0,25);phase(7);
            } else if(phase==7 && both(7)) {mark("range_return_respawns_same_uuid");phase(8);
            } else if(phase==8 && a==null) {phase(9);
            } else if(phase==9 && a!=null && both(9)) {
                mark("reconnect_owner_with_observer_still_online");phase(10);
            } else if(phase==10 && b==null) {phase(11);
            } else if(phase==11 && b!=null && both(11)) {
                mark("reconnect_observer_with_owner_still_online");waitStart=System.nanoTime();phase(12);
            } else if(phase==12 && phaseTicks>Integer.getInteger("lootablebodies.visibility.waitTicks",6200) && (System.nanoTime()-waitStart)>Long.getLong("lootablebodies.visibility.waitMillis",310000L)*1000000L && both(12)) {
                RuntimeChecks.require(first.isEntityAlive() && has(first,410),"Default corpse lost before five-minute TNT test");
                phase(120);
            } else if(phase==120 && both(120)) {
                mark(Integer.getInteger("lootablebodies.visibility.waitTicks",6200)==6200?"default_corpse_visible_after_6200_ticks_and_310_seconds":"short_fixture_control_default_corpse_visible");
                a.connection.setPlayerLocation(100.5,80,96.5,0,0);b.connection.setPlayerLocation(102.5,80,93.5,0,25);
                a.inventory.mainInventory.set(9,RuntimeChecks.stack(411));a.inventoryContainer.detectAndSendChanges();phase(13);
            } else if(phase==13 && both(13)) {
                before=new HashSet<>(world().loadedEntityList);EntityTNTPrimed tnt=new EntityTNTPrimed(world(),a.posX,a.posY,a.posZ,null);tnt.setFuse(0);
                RuntimeChecks.require(world().spawnEntity(tnt),"TNT fixture spawn");phase(14);
            } else if(phase==14) {
                if(second==null && a!=null && !a.isEntityAlive()) {second=newCorpse();saveId("second",second);}
                if(second!=null && both(14)) {RuntimeChecks.require(second.isEntityAlive() && has(second,411),"TNT destroyed default corpse/inventory");mark("tnt_death_after_five_minutes_both_clients_receive_corpse");phase(15);}
            } else if(phase==15 && both(15) && a!=null && a.isEntityAlive()) {
                // TNT can displace both the observer and the death position.
                a.connection.setPlayerLocation(second.posX,second.posY,second.posZ-2.5,0,25);
                b.connection.setPlayerLocation(second.posX+1.5,second.posY,second.posZ-2.5,0,25);phase(16);
            } else if(phase==16 && both(16)) {mark("both_clients_render_and_open_tnt_corpse_after_respawn");phase(17);
            } else if(phase==17 && both(17) && phaseTicks>20) {
                RuntimeChecks.require(!has(second,411) && playerHas(a,411),"TNT corpse recovery lost or duplicated tagged stack");
                RuntimeChecks.require(has(first,410),"Earlier corpse inventory changed during TNT/reconnect checks");
                a.closeScreen();b.closeScreen();File folder=world().getSaveHandler().getWorldDirectory();
                RuntimeChecks.saveExpected(folder,"visibility-first",first);RuntimeChecks.saveExpected(folder,"visibility-second",second);
                NBTTagCompound state=new NBTTagCompound();state.setUniqueId("First",first.getUniqueID());state.setUniqueId("Second",second.getUniqueID());state.setUniqueId("Player",a.getUniqueID());state.setTag("Inventory",a.inventory.writeToNBT(new NBTTagList()));
                try(FileOutputStream out=new FileOutputStream(new File(folder,"visibility-state.nbt"))){CompressedStreamTools.writeCompressed(state,out);}
                server.getPlayerList().saveAllPlayerData();server.saveAllWorlds(false);mark("tagged_inventory_recovered_and_complete_nbt_saved");phase(18);
            } else if(phase==18 && server.getPlayerList().getCurrentPlayerCount()==0) {
                Files.write(file("visibility.pass").toPath(),new byte[]{1});System.out.println("LOOTABLE_BODIES_VISIBILITY_PASS");finished=true;server.initiateShutdown();
            } else if(phase==19 && both(19)) {
                mark("cold_server_restart_both_clients_render_and_open_saved_corpse");a.closeScreen();b.closeScreen();
                for(int x=397;x<=404;x++)for(int z=397;z<=404;z++)world().setBlockState(new BlockPos(x,79,z),Blocks.OBSIDIAN.getDefaultState());
                a.connection.setPlayerLocation(400.5,80,400.5,0,25);b.connection.setPlayerLocation(402.5,80,400.5,0,25);phase(20);
            } else if(phase==20 && both(20) && phaseTicks>240 && !world().getChunkProvider().chunkExists(first.chunkCoordX,first.chunkCoordZ) && !world().getChunkProvider().chunkExists(second.chunkCoordX,second.chunkCoordZ)) {
                mark("both_clients_leave_and_corpse_chunk_really_unloads");
                net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
                a.connection.setPlayerLocation(first.posX,first.posY,first.posZ-2.5,0,25);b.connection.setPlayerLocation(first.posX+1.5,first.posY,first.posZ-2.5,0,25);phase(21);
            } else if(phase==21 && both(21)) {
                first=RuntimeChecks.find(world(),first.getUniqueID());second=RuntimeChecks.find(world(),second.getUniqueID());verifyInventories();
                mark("chunk_reentry_both_clients_render_same_uuids_and_inventory");server.getPlayerList().saveAllPlayerData();server.saveAllWorlds(false);phase(18);
            }
        } catch(Throwable failure) {failure.printStackTrace();finished=true;server.initiateShutdown();}
    }
    private EntityLootableBody newCorpse(){for(Entity e:world().loadedEntityList)if(e instanceof EntityLootableBody&&!before.contains(e))return (EntityLootableBody)e;throw new AssertionError("No server corpse for real death");}
    private void saveId(String name,EntityLootableBody b) throws Exception {Files.write(file(name+".uuid").toPath(),b.getUniqueID().toString().getBytes(StandardCharsets.UTF_8));}
    private void phase(int n) throws Exception {phase=n;phaseTicks=0;File tmp=file("phase.tmp");Files.write(tmp.toPath(),Integer.toString(n).getBytes(StandardCharsets.UTF_8));Files.move(tmp.toPath(),file("phase").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);System.out.println("VISIBILITY_PHASE "+n);}
    private void mark(String n) throws Exception {try(FileWriter out=new FileWriter(file("checks.txt"),true)){out.write("PASS "+n+"\n");}System.out.println("VISIBILITY_CHECK_PASS "+n);}
    static boolean has(EntityLootableBody b,int i){for(int s=0;s<48;s++)if(RuntimeChecks.sameStack(b.getStackInSlot(s),RuntimeChecks.stack(i)))return true;return false;}
    private static boolean playerHas(EntityPlayerMP p,int i){int count=0;for(int s=0;s<p.inventory.getSizeInventory();s++)if(RuntimeChecks.sameStack(p.inventory.getStackInSlot(s),RuntimeChecks.stack(i)))count++;return count==1;}
    private void verifyInventories() throws Exception {
        verifySnapshot(world(),"first",first);verifySnapshot(world(),"second",second);
    }
    static void reload(MinecraftServer server) throws Exception {
        WorldServer w=server.getWorld(0);File folder=w.getSaveHandler().getWorldDirectory();NBTTagCompound n;
        try(FileInputStream in=new FileInputStream(new File(folder,"visibility-state.nbt"))){n=CompressedStreamTools.readCompressed(in);}
        for(String key:new String[]{"First","Second"}) {EntityLootableBody b=loadExpected(w,key.toLowerCase(Locale.ROOT));RuntimeChecks.require(b.getUniqueID().equals(n.getUniqueId(key)),"Saved identity differs");verifySnapshot(w,key.toLowerCase(Locale.ROOT),b);}
        NBTTagCompound p;try(FileInputStream in=new FileInputStream(new File(folder,"playerdata/"+n.getUniqueId("Player")+".dat"))){p=CompressedStreamTools.readCompressed(in);}
        RuntimeChecks.require(n.getTag("Inventory").equals(p.getTag("Inventory")),"Recovered inventory changed on cold restart");
    }
    private static EntityLootableBody loadExpected(WorldServer w,String name) throws Exception {
        NBTTagCompound n;try(FileInputStream in=new FileInputStream(new File(w.getSaveHandler().getWorldDirectory(),"visibility-"+name+".nbt"))){n=CompressedStreamTools.readCompressed(in);}
        NBTTagList pos=n.getTagList("Pos",6);w.getChunk((int)Math.floor(pos.getDoubleAt(0)/16),(int)Math.floor(pos.getDoubleAt(2)/16));
        return RuntimeChecks.find(w,n.getUniqueId("UUID"));
    }
    private static void verifySnapshot(WorldServer w,String name,EntityLootableBody body) throws Exception {
        NBTTagCompound expected;try(FileInputStream in=new FileInputStream(new File(w.getSaveHandler().getWorldDirectory(),"visibility-"+name+".nbt"))){expected=CompressedStreamTools.readCompressed(in);}
        NBTTagCompound actual=new NBTTagCompound();body.writeToNBTOptional(actual);
        for(String key:new String[]{"id","UUIDMost","UUIDLeast","Equipment","Aux","Name","CustomName","LootableBodiesUnresolved","LootableBodiesInvalidFields"})RuntimeChecks.require(Objects.equals(expected.getTag(key),actual.getTag(key)),"Reloaded corpse differs: "+name+" "+key);
        // These live-default fixtures keep ticking between the snapshot and logout.
        // Timer equality belongs to the separate frozen-timer persistence tests.
        for(String key:new String[]{"DeathTime","DecayTime"})RuntimeChecks.require(actual.getLong(key)>=expected.getLong(key)&&actual.getLong(key)<=w.getTotalWorldTime(),"Reloaded live timer out of range: "+key);
    }
}
