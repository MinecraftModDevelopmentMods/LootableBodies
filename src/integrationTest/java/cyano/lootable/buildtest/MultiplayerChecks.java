package cyano.lootable.buildtest;
import net.minecraft.world.inventory.AbstractContainerMenu;
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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.event.network.CustomPayloadEvent;
import java.util.function.Consumer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.SimpleChannel;
import net.minecraftforge.api.distmarker.Dist;

/** Disposable two-client coordinator. Step messages acknowledge prior TCP clicks on the server thread. */
public final class MultiplayerChecks {
    public static final UUID BODY=UUID.fromString("00000000-0000-4000-8000-000000000108");
    public static final SimpleChannel CHANNEL=ChannelBuilder.named("lootablebodiesbuildtest:steps").networkProtocolVersion(1).simpleChannel();
    private final MinecraftServer server;
    private final File sync;
    private EntityLootableBody body;
    private int phase,ticks;
    private boolean finishing;
    public static void registerChannel() {
        CHANNEL.messageBuilder(Step.class,0,NetworkDirection.PLAY_TO_SERVER)
                .encoder((message,buffer)->buffer.writeInt(message.number))
                .decoder(buffer->new Step(buffer.readInt()))
                .consumer((message, context) -> enqueuePlayAction(context,
                        player -> acknowledge(message, player))).add();
        CHANNEL.messageBuilder(FixtureDeath.class,1,NetworkDirection.PLAY_TO_SERVER)
                .encoder((message,buffer) -> buffer.writeInt(message.selectedSlot()))
                .decoder(buffer -> new FixtureDeath(buffer.readInt()))
                .consumer((message, context) -> enqueuePlayAction(context, player -> {
                    RuntimeChecks.require(Boolean.getBoolean("lootablebodies.issueClientCheck"),
                            "Unexpected fixture death signal");
                    RuntimeChecks.require(player.getInventory().getSelectedSlot() == message.selectedSlot(),
                            "Native carried-item packet did not precede the fixture death");
                    player.kill(player.level());
                    RuntimeChecks.require(!player.isAlive(), "Ordered native fixture death did not take effect");
                    System.out.println("NATIVE_ORDERED_HELD_ITEM_DEATH_PASS slot=" + message.selectedSlot());
                })).add();
        CHANNEL.build();
    }

    private static void enqueuePlayAction(CustomPayloadEvent.Context context, Consumer<ServerPlayer> action) {
        var listener = context.getConnection().getPacketListener();
        RuntimeChecks.require(listener instanceof ServerGamePacketListenerImpl, "Missing native play listener");
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        RuntimeChecks.require(server != null, "Missing native packet processor");
        server.packetProcessor().scheduleIfPossible((ServerCommonPacketListener) listener, new NativePlayAction(action));
        context.setPacketHandled(true);
    }

    private record NativePlayAction(Consumer<ServerPlayer> action) implements Packet<ServerCommonPacketListener> {
        @Override public PacketType<? extends Packet<ServerCommonPacketListener>> type() {
            return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;
        }

        @Override public void handle(ServerCommonPacketListener listener) {
            action.accept(((ServerGamePacketListenerImpl) listener).player);
        }
    }

    public record FixtureDeath(int selectedSlot) { }
    public MultiplayerChecks(MinecraftServer server) throws Exception {
        this.server=server;sync=sync();
        LootableBodies.allowCorpseDecay=false;LootableBodies.ticksPerItemDecay=-1;
        setPhase(0);Files.write(new File(sync,"server.ready").toPath(),"ready\n".getBytes(StandardCharsets.UTF_8));
        TickEvent.ServerTickEvent.Post.BUS.addListener(this::tick);
    }
    public static File sync(){return new File(System.getProperty("lootablebodies.multiplayer.sync"));}
    private ServerPlayer player(String role){return server.getPlayerList().getPlayerByName("Container"+role);}
    private boolean ack(String role,int step){return new File(sync,"Container"+role+".step-"+step).isFile();}
    public void tick(TickEvent.ServerTickEvent.Post e){
        if(finishing)return;
        try{
            RuntimeChecks.require(++ticks<2400,"Two-client timeout phase="+phase);
            if(new File(sync,"abort").exists())throw new AssertionError("Runner aborted multiplayer fixture");
            ServerPlayer a=player("A"),b=player("B");
            ServerLevel w=server.getLevel(FixtureApi.dimensionKey(0));
            if(phase>=1 && phase<=7 && w.getEntity(BODY) instanceof EntityLootableBody active && active!=body){
                System.out.println("TCP_CORPSE_REINDEXED old="+body.getId()+" reason="+body.getRemovalReason()+" active="+active.getId());
                body=active;
            }
            if(phase==0 && a!=null && b!=null){
                for(int x=93;x<=100;x++)for(int z=93;z<=100;z++)w.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                w.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS,false,null);
                body=new EntityLootableBody(w);body.setUUID(BODY);body.setPos(96.5,80.5,96.5);body.setNoGravity(true);body.setUserName("SharedFixture");RuntimeChecks.disableVacuum(body);
                body.setItem(6,stack(19,23));RuntimeChecks.require(w.addFreshEntity(body),"Shared corpse spawn");
                a.setGameMode(GameType.SURVIVAL);b.setGameMode(GameType.SURVIVAL);a.getInventory().clearContent();b.getInventory().clearContent();
                a.connection.teleport(96.5,80,94.5,0,0);b.connection.teleport(98.5,80,96.5,0,0);
                setPhase(1);
            }else if(phase==1 && ack("A",1) && ack("B",1))setPhase(2);
            else if(phase==2){
                RuntimeChecks.require(w.getEntity(BODY)==body && a.containerMenu.getSlot(6).container==body
                        && b.containerMenu.getSlot(6).container==body,"Two TCP viewers must reference the active corpse");
                ItemStack remaining=body.getItem(6);
                int inBody=remaining.getCount(), inA=count(a,19), inB=count(b,19);
                RuntimeChecks.require((remaining.isEmpty() || RuntimeChecks.sameStack(remaining,stack(19,23)))
                        && inBody+inA+inB==23,"Two TCP clients duplicated/lost contested stack: "+inBody+"/"+inA+"/"+inB);
                RuntimeChecks.require(inA==0 || inB==0,"Both clients recovered the same stack");
                // A received test acknowledgement does not by itself prove
                // that Minecraft has applied a queued container click.
                if(inBody!=0 || !ack("A",2) || !ack("B",2))return;
                mark("two_tcp_clients_contested_shift_click");
                a.getInventory().clearContent();b.getInventory().clearContent();body.clearContent();
                a.containerMenu.setCarried(stack(19,10));b.getInventory().getNonEquipmentItems().set(0,stack(20,7));refresh(a);refresh(b);setPhase(3);
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
    private static int count(ServerPlayer p,int index){int n=0;ItemStack expected=RuntimeChecks.stack(index);for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack s=p.getInventory().getItem(i);if((s != null && !s.isEmpty()) && cyano.lootable.ItemStacks.canMerge(s,expected))n+=s.getCount();}return n;}
    private static void refresh(ServerPlayer p){p.containerMenu.broadcastChanges();p.containerMenu.broadcastFullState();}
    private static void savePlayer(File folder,ServerPlayer p,String role) throws Exception {
        CompoundTag n=new CompoundTag();n.putString("UUID",p.getUUID().toString());n.put("Inventory",FixtureApi.saveInventory(p));
        try(FileOutputStream out=new FileOutputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){NbtIo.writeCompressed(n,out);}
    }
    static void verifyReload(MinecraftServer server) throws Exception {
        ServerLevel world=server.getLevel(FixtureApi.dimensionKey(0));RuntimeChecks.awaitChunk(world,6,6);EntityLootableBody body=RuntimeChecks.find(world,BODY);body.baseTick();
        File folder=world.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile();RuntimeChecks.verifyExpected(folder,"multiplayer-body",body);
        for(String role:new String[]{"A","B"}){
            CompoundTag expected;try(FileInputStream in=new FileInputStream(new File(folder,"multiplayer-player-"+role+".nbt"))){expected=NbtIo.readCompressed(in,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));}
            CompoundTag actual;try(FileInputStream in=new FileInputStream(new File(folder,net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id()+"/"+expected.getStringOr("UUID", "")+".dat"))){actual=NbtIo.readCompressed(in,net.minecraft.nbt.NbtAccounter.create(64L*1024*1024));}
            RuntimeChecks.require(expected.get("Inventory").equals(actual.get("Inventory")),"Real player "+role+" inventory changed on disk");
        }
    }
    public static final class Step {private final int number;public Step(int number){this.number=number;}}
    private static void acknowledge(Step message,ServerPlayer player) {
        try {RuntimeChecks.require(player!=null && player.getGameProfile().name().matches("Container[AB]"),"Unexpected fixture player");Files.write(new File(sync(),player.getGameProfile().name()+".step-"+message.number).toPath(),"ack\n".getBytes(StandardCharsets.UTF_8));}
        catch(Exception error){throw new RuntimeException(error);}
    }
}
