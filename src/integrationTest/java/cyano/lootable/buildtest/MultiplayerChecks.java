package cyano.lootable.buildtest;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.CompoundTag;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import io.netty.buffer.ByteBuf;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
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
import net.minecraftforge.fmllegacy.network.NetworkRegistry;
import net.minecraftforge.fmllegacy.network.simple.*;
import net.minecraftforge.api.distmarker.Dist;

/** Disposable two-client coordinator. Step messages acknowledge prior TCP clicks on the server thread. */
public final class MultiplayerChecks {
    public static final UUID BODY=UUID.fromString("00000000-0000-4000-8000-000000000108");
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new net.minecraft.resources.ResourceLocation("lootablebodiesbuildtest:steps"),()->"1","1"::equals,"1"::equals);
    private final MinecraftServer server;
    private final File sync;
    private EntityLootableBody body;
    private int phase,ticks;
    private boolean finishing;
    public static void registerChannel(){CHANNEL.registerMessage(0,Step.class,(message,buffer)->buffer.writeInt(message.number),buffer->new Step(buffer.readInt()),(message,supplier)->{net.minecraftforge.fmllegacy.network.NetworkEvent.Context context=supplier.get();context.enqueueWork(()->acknowledge(message,context.getSender()));context.setPacketHandled(true);});CHANNEL.registerMessage(1,FixtureDeath.class,(message,buffer)->{},buffer->new FixtureDeath(),(message,supplier)->{net.minecraftforge.fmllegacy.network.NetworkEvent.Context context=supplier.get();context.enqueueWork(()->{RuntimeChecks.require(Boolean.getBoolean("lootablebodies.issueClientCheck") && context.getSender()!=null,"Unexpected fixture death signal");context.getSender().kill();});context.setPacketHandled(true);});}
    public static final class FixtureDeath { }
    public MultiplayerChecks(MinecraftServer server) throws Exception {
        this.server=server;sync=sync();
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        setPhase(0);Files.write(new File(sync,"server.ready").toPath(),"ready\n".getBytes(StandardCharsets.UTF_8));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    public static File sync(){return new File(System.getProperty("lootablebodies.multiplayer.sync"));}
    private ServerPlayer player(String role){return server.getPlayerList().getPlayerByName("Container"+role);}
    private boolean ack(String role,int step){return new File(sync,"Container"+role+".step-"+step).isFile();}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e){
        if(e.phase!=TickEvent.Phase.END || finishing)return;
        try{
            RuntimeChecks.require(++ticks<2400,"Two-client timeout phase="+phase);
            if(new File(sync,"abort").exists())throw new AssertionError("Runner aborted multiplayer fixture");
            ServerPlayer a=player("A"),b=player("B");
            if(phase==0 && a!=null && b!=null){
                ServerLevel w=server.getLevel(FixtureApi.dimensionKey(0));
                for(int x=93;x<=100;x++)for(int z=93;z<=100;z++)w.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                w.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,null);
                body=new EntityLootableBody(w);body.setUUID(BODY);body.setPos(96.5,80.5,96.5);body.setNoGravity(true);body.setUserName("SharedFixture");RuntimeChecks.disableVacuum(body);
                body.setItem(6,stack(19,23));RuntimeChecks.require(w.addFreshEntity(body),"Shared corpse spawn");
                a.setGameMode(GameType.SURVIVAL);b.setGameMode(GameType.SURVIVAL);a.getInventory().clearContent();b.getInventory().clearContent();
                a.connection.teleport(96.5,80,94.5,0,0);b.connection.teleport(98.5,80,96.5,0,0);
                setPhase(1);
            }else if(phase==1 && ack("A",1) && ack("B",1))setPhase(2);
            else if(phase==2 && ack("A",2) && ack("B",2)){
                RuntimeChecks.require((body.getItem(6)).isEmpty() && count(a,19)+count(b,19)==23,"Two TCP clients duplicated/lost contested stack");
                RuntimeChecks.require(count(a,19)==0 || count(b,19)==0,"Both clients recovered the same stack");
                mark("two_tcp_clients_contested_shift_click");
                a.getInventory().clearContent();b.getInventory().clearContent();body.clearContent();
                a.containerMenu.setCarried(stack(19,10));b.getInventory().items.set(0,stack(20,7));refresh(a);refresh(b);setPhase(3);
            }else if(phase==3 && ack("A",3))setPhase(4);
            else if(phase==4 && ack("B",4)){
                equal(body.getItem(6),stack(20,7),"TCP hotbar swap failed");setPhase(5);
            }else if(phase==5 && ack("A",5)){
                equal(body.getItem(6),stack(20,7),"TCP drag overwrote other player's NBT");equal(body.getItem(7),stack(19,5),"TCP drag allocation");equal(a.containerMenu.getCarried(),stack(19,5),"TCP cursor remainder");
                mark("two_tcp_clients_hotbar_interleaved_with_drag");setPhase(6);
            }else if(phase==6 && ack("A",6)){
                equal(a.containerMenu.getCarried(),null,"TCP deposit cursor not empty");RuntimeChecks.require(count(a,19)==5,"TCP drag remainder deposit");setPhase(7);
            }else if(phase==7 && ack("A",7) && ack("B",7)){
                File folder=server.getLevel(FixtureApi.dimensionKey(0)).getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();
                RuntimeChecks.saveExpected(folder,"multiplayer-body",body);savePlayer(folder,a,"A");savePlayer(folder,b,"B");
                server.getPlayerList().saveAll();server.saveAllChunks(false,true,false);mark("two_tcp_clients_final_gui_and_saved_nbt");
                setPhase(8);
            }else if(phase==8 && server.getPlayerList().getPlayerCount()==0){
                Files.write(new File(sync,"multiplayer.pass").toPath(),"two TCP clients passed\n".getBytes(StandardCharsets.UTF_8));
                System.out.println("LOOTABLE_BODIES_MULTIPLAYER_PASS");finishing=true;server.halt(false);
            }
        }catch(Throwable error){error.printStackTrace();finishing=true;server.halt(false);}
    }
    private void setPhase(int value) throws Exception {
        phase=value;File temporary=new File(sync,"phase.tmp");Files.write(temporary.toPath(),Integer.toString(value).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(),new File(sync,"phase").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        System.out.println("MULTIPLAYER_PHASE "+value);
    }
    private void mark(String name) throws Exception{try(FileWriter out=new FileWriter(new File(sync,"checks.txt"),true)){out.write("PASS "+name+"\n");}System.out.println("MULTIPLAYER_CHECK_PASS "+name);}
    public static ItemStack stack(int i,int n){ItemStack s=RuntimeChecks.stack(i);s.setCount(n);return s;}
    public static void equal(ItemStack a,ItemStack b,String reason){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),reason);}
    private static int count(ServerPlayer p,int index){int n=0;ItemStack expected=RuntimeChecks.stack(index);for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack s=p.getInventory().getItem(i);if((s != null && !s.isEmpty()) && ItemStack.isSame(s,expected) && ItemStack.tagMatches(s,expected))n+=s.getCount();}return n;}
    private static void refresh(ServerPlayer p){p.containerMenu.broadcastChanges();p.containerMenu.broadcastFullState();}
    private static void savePlayer(File folder,ServerPlayer p,String role) throws Exception {
        CompoundTag n=new CompoundTag();n.putString("UUID",p.getUUID().toString());n.put("Inventory",p.getInventory().save(new ListTag()));
        try(FileOutputStream out=new FileOutputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){NbtIo.writeCompressed(n,out);}
    }
    static void verifyReload(MinecraftServer server) throws Exception {
        ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));RuntimeChecks.awaitChunk(world,6,6);EntityLootableBody body=RuntimeChecks.find(world,BODY);body.baseTick();
        File folder=world.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();RuntimeChecks.verifyExpected(folder,"multiplayer-body",body);
        for(String role:new String[]{"A","B"}){
            CompoundTag expected;try(FileInputStream in=new FileInputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){expected=NbtIo.readCompressed(in);}
            CompoundTag actual;try(FileInputStream in=new FileInputStream(new File(folder,"playerdata/"+expected.getString("UUID")+".dat"))){actual=NbtIo.readCompressed(in);}
            RuntimeChecks.require(expected.get("Inventory").equals(actual.get("Inventory")),"Real player "+role+" inventory changed on disk");
        }
    }
    public static final class Step {private final int number;public Step(int number){this.number=number;}}
    private static void acknowledge(Step message,ServerPlayer player) {
        try {RuntimeChecks.require(player!=null && player.getGameProfile().getName().matches("Container[AB]"),"Unexpected fixture player");Files.write(new File(sync(),player.getGameProfile().getName()+".step-"+message.number).toPath(),"ack\n".getBytes(StandardCharsets.UTF_8));}
        catch(Exception error){throw new RuntimeException(error);}
    }
}
