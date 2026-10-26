package cyano.lootable.buildtest;
import net.minecraft.inventory.container.Container;
import net.minecraft.entity.boss.dragon.phase.Phase;
import net.minecraft.inventory.Inventory;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.CompoundNBT;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import io.netty.buffer.ByteBuf;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.*;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.*;
import net.minecraftforge.api.distmarker.Dist;

/** Disposable two-client coordinator. Step messages acknowledge prior TCP clicks on the server thread. */
public final class MultiplayerChecks {
    public static final UUID BODY=UUID.fromString("00000000-0000-4000-8000-000000000108");
    public static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new net.minecraft.util.ResourceLocation("lootablebodiesbuildtest:steps"),()->"1","1"::equals,"1"::equals);
    private final MinecraftServer server;
    private final File sync;
    private EntityLootableBody body;
    private int phase,ticks;
    private boolean finishing;
    public static void registerChannel(){CHANNEL.registerMessage(0,Step.class,(message,buffer)->buffer.writeInt(message.number),buffer->new Step(buffer.readInt()),(message,supplier)->{net.minecraftforge.fml.network.NetworkEvent.Context context=supplier.get();context.enqueueWork(()->acknowledge(message,context.getSender()));context.setPacketHandled(true);});CHANNEL.registerMessage(1,FixtureDeath.class,(message,buffer)->{},buffer->new FixtureDeath(),(message,supplier)->{net.minecraftforge.fml.network.NetworkEvent.Context context=supplier.get();context.enqueueWork(()->{RuntimeChecks.require(Boolean.getBoolean("lootablebodies.issueClientCheck") && context.getSender()!=null,"Unexpected fixture death signal");context.getSender().onKillCommand();});context.setPacketHandled(true);});}
    public static final class FixtureDeath { }
    public MultiplayerChecks(MinecraftServer server) throws Exception {
        this.server=server;sync=sync();
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        setPhase(0);Files.write(new File(sync,"server.ready").toPath(),"ready\n".getBytes(StandardCharsets.UTF_8));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
    }
    public static File sync(){return new File(System.getProperty("lootablebodies.multiplayer.sync"));}
    private ServerPlayerEntity player(String role){return server.getPlayerList().getPlayerByUsername("Container"+role);}
    private boolean ack(String role,int step){return new File(sync,"Container"+role+".step-"+step).isFile();}
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e){
        if(e.phase!=TickEvent.Phase.END || finishing)return;
        try{
            RuntimeChecks.require(++ticks<2400,"Two-client timeout phase="+phase);
            if(new File(sync,"abort").exists())throw new AssertionError("Runner aborted multiplayer fixture");
            ServerPlayerEntity a=player("A"),b=player("B");
            if(phase==0 && a!=null && b!=null){
                ServerWorld w=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));
                for(int x=93;x<=100;x++)for(int z=93;z<=100;z++)w.setBlockState(new BlockPos(x,79,z),Blocks.STONE.getDefaultState());
                w.getGameRules().get(net.minecraft.world.GameRules.DO_MOB_SPAWNING).set(false,null);
                body=new EntityLootableBody(w);body.setUniqueId(BODY);body.setPosition(96.5,80.5,96.5);body.setNoGravity(true);body.setUserName("SharedFixture");RuntimeChecks.disableVacuum(body);
                body.setInventorySlotContents(6,stack(19,23));RuntimeChecks.require(w.addEntity(body),"Shared corpse spawn");
                a.setGameType(GameType.SURVIVAL);b.setGameType(GameType.SURVIVAL);a.inventory.clear();b.inventory.clear();
                a.connection.setPlayerLocation(96.5,80,94.5,0,0);b.connection.setPlayerLocation(98.5,80,96.5,0,0);
                setPhase(1);
            }else if(phase==1 && ack("A",1) && ack("B",1))setPhase(2);
            else if(phase==2 && ack("A",2) && ack("B",2)){
                RuntimeChecks.require((body.getStackInSlot(6)).isEmpty() && count(a,19)+count(b,19)==23,"Two TCP clients duplicated/lost contested stack");
                RuntimeChecks.require(count(a,19)==0 || count(b,19)==0,"Both clients recovered the same stack");
                mark("two_tcp_clients_contested_shift_click");
                a.inventory.clear();b.inventory.clear();body.clear();
                a.inventory.setItemStack(stack(19,10));b.inventory.mainInventory.set(0,stack(20,7));refresh(a);refresh(b);setPhase(3);
            }else if(phase==3 && ack("A",3))setPhase(4);
            else if(phase==4 && ack("B",4)){
                equal(body.getStackInSlot(6),stack(20,7),"TCP hotbar swap failed");setPhase(5);
            }else if(phase==5 && ack("A",5)){
                equal(body.getStackInSlot(6),stack(20,7),"TCP drag overwrote other player's NBT");equal(body.getStackInSlot(7),stack(19,5),"TCP drag allocation");equal(a.inventory.getItemStack(),stack(19,5),"TCP cursor remainder");
                mark("two_tcp_clients_hotbar_interleaved_with_drag");setPhase(6);
            }else if(phase==6 && ack("A",6)){
                equal(a.inventory.getItemStack(),null,"TCP deposit cursor not empty");RuntimeChecks.require(count(a,19)==5,"TCP drag remainder deposit");setPhase(7);
            }else if(phase==7 && ack("A",7) && ack("B",7)){
                File folder=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0)).getSaveHandler().getWorldDirectory();
                RuntimeChecks.saveExpected(folder,"multiplayer-body",body);savePlayer(folder,a,"A");savePlayer(folder,b,"B");
                server.getPlayerList().saveAllPlayerData();server.save(false,true,false);mark("two_tcp_clients_final_gui_and_saved_nbt");
                setPhase(8);
            }else if(phase==8 && server.getPlayerList().getCurrentPlayerCount()==0){
                Files.write(new File(sync,"multiplayer.pass").toPath(),"two TCP clients passed\n".getBytes(StandardCharsets.UTF_8));
                System.out.println("LOOTABLE_BODIES_MULTIPLAYER_PASS");finishing=true;server.initiateShutdown(false);
            }
        }catch(Throwable error){error.printStackTrace();finishing=true;server.initiateShutdown(false);}
    }
    private void setPhase(int value) throws Exception {
        phase=value;File temporary=new File(sync,"phase.tmp");Files.write(temporary.toPath(),Integer.toString(value).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(),new File(sync,"phase").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        System.out.println("MULTIPLAYER_PHASE "+value);
    }
    private void mark(String name) throws Exception{try(FileWriter out=new FileWriter(new File(sync,"checks.txt"),true)){out.write("PASS "+name+"\n");}System.out.println("MULTIPLAYER_CHECK_PASS "+name);}
    public static ItemStack stack(int i,int n){ItemStack s=RuntimeChecks.stack(i);s.setCount(n);return s;}
    public static void equal(ItemStack a,ItemStack b,String reason){RuntimeChecks.require(RuntimeChecks.sameStack(a == null ? ItemStack.EMPTY : a,b == null ? ItemStack.EMPTY : b),reason);}
    private static int count(ServerPlayerEntity p,int index){int n=0;ItemStack expected=RuntimeChecks.stack(index);for(int i=0;i<p.inventory.getSizeInventory();i++){ItemStack s=p.inventory.getStackInSlot(i);if((s != null && !s.isEmpty()) && ItemStack.areItemsEqual(s,expected) && ItemStack.areItemStackTagsEqual(s,expected))n+=s.getCount();}return n;}
    private static void refresh(ServerPlayerEntity p){p.openContainer.detectAndSendChanges();p.sendContainerToPlayer(p.openContainer);p.updateHeldItem();}
    private static void savePlayer(File folder,ServerPlayerEntity p,String role) throws Exception {
        CompoundNBT n=new CompoundNBT();n.putString("UUID",p.getUniqueID().toString());n.put("Inventory",p.inventory.write(new ListNBT()));
        try(FileOutputStream out=new FileOutputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){CompressedStreamTools.writeCompressed(n,out);}
    }
    static void verifyReload(MinecraftServer server) throws Exception {
        ServerWorld world=server.getWorld(net.minecraft.world.dimension.DimensionType.getById(0));world.getChunk(6,6);EntityLootableBody body=RuntimeChecks.find(world,BODY);body.baseTick();
        File folder=world.getSaveHandler().getWorldDirectory();RuntimeChecks.verifyExpected(folder,"multiplayer-body",body);
        for(String role:new String[]{"A","B"}){
            CompoundNBT expected;try(FileInputStream in=new FileInputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){expected=CompressedStreamTools.readCompressed(in);}
            CompoundNBT actual;try(FileInputStream in=new FileInputStream(new File(folder,"playerdata/"+expected.getString("UUID")+".dat"))){actual=CompressedStreamTools.readCompressed(in);}
            RuntimeChecks.require(expected.get("Inventory").equals(actual.get("Inventory")),"Real player "+role+" inventory changed on disk");
        }
    }
    public static final class Step {private final int number;public Step(int number){this.number=number;}}
    private static void acknowledge(Step message,ServerPlayerEntity player) {
        try {RuntimeChecks.require(player!=null && player.getGameProfile().getName().matches("Container[AB]"),"Unexpected fixture player");Files.write(new File(sync(),player.getGameProfile().getName()+".step-"+message.number).toPath(),"ack\n".getBytes(StandardCharsets.UTF_8));}
        catch(Exception error){throw new RuntimeException(error);}
    }
}
