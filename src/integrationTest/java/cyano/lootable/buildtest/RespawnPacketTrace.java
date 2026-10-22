package cyano.lootable.buildtest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.CPacketClientStatus;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

/** Observes decoded packets without dropping, delaying, mutating or reordering them. */
final class RespawnPacketTrace extends ChannelDuplexHandler {
    interface After {void received(String packet);}
    private final File log;
    private final After after;
    private long sequence;
    private RespawnPacketTrace(File log,After after){this.log=log;this.after=after;}
    static void install(NetworkManager manager,File log,After after) {
        Channel channel=manager.channel();
        channel.eventLoop().submit(()->channel.pipeline().addBefore("packet_handler","corpse_packet_trace",new RespawnPacketTrace(log,after))).syncUninterruptibly();
    }
    @Override public void channelRead(ChannelHandlerContext context,Object packet) throws Exception {
        String description=describe(packet);if(description!=null)record("IN "+description);
        if(Boolean.getBoolean("lootablebodies.respawn.dropSpawn")&&log.getName().equals("client-packets.txt")
            &&description!=null&&description.startsWith("MOD_SPAWN ")&&description.contains("mod=lootablebodies ")) {
            record("FAULT_DROPPED "+description);((FMLProxyPacket)packet).payload().release();return;
        }
        super.channelRead(context,packet);
        if(description!=null&&after!=null)after.received(description);
    }
    @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise) throws Exception {
        String description=describe(packet);if(description!=null)record("OUT "+description);
        super.write(context,packet,promise);
    }
    private synchronized void record(String line) throws IOException {try(FileWriter out=new FileWriter(log,true)){out.write((++sequence)+" "+System.nanoTime()+" "+line+"\n");}}
    static String describe(Object packet) {
        if(packet instanceof FMLProxyPacket) {
            FMLProxyPacket f=(FMLProxyPacket)packet;if(!f.channel().equals("FML"))return null;
            ByteBuf b=f.payload().duplicate();if(b.readableBytes()<6||b.readUnsignedByte()!=2)return null;
            int id=b.readInt();String mod=ByteBufUtils.readUTF8String(b);int type=b.readInt();
            return "MOD_SPAWN id="+id+" mod="+mod+" type="+type+" uuid="+new UUID(b.readLong(),b.readLong());
        }
        if(!(packet instanceof SPacketRespawn||packet instanceof SPacketDestroyEntities||packet instanceof SPacketUnloadChunk
            ||packet instanceof SPacketChunkData||packet instanceof SPacketCombatEvent||packet instanceof SPacketEntityMetadata||packet instanceof CPacketClientStatus))return null;
        // Vanilla strips receiving-side accessors from a dedicated distribution.
        // Read an independent serialization, never its live reader index/fields.
        PacketBuffer b=new PacketBuffer(Unpooled.buffer());
        try {
            ((Packet<?>)packet).writePacketData(b);
            if(packet instanceof SPacketRespawn)return "RESPAWN dimension="+b.readInt();
            if(packet instanceof SPacketDestroyEntities){int[] ids=new int[b.readVarInt()];for(int i=0;i<ids.length;i++)ids[i]=b.readVarInt();return "DESTROY ids="+Arrays.toString(ids);}
            if(packet instanceof SPacketUnloadChunk)return "UNLOAD chunk="+b.readInt()+","+b.readInt();
            if(packet instanceof SPacketChunkData)return "CHUNK chunk="+b.readInt()+","+b.readInt()+" full="+b.readBoolean();
            if(packet instanceof SPacketCombatEvent){SPacketCombatEvent.Event type=b.readEnumValue(SPacketCombatEvent.Event.class);return "COMBAT event="+type+(type==SPacketCombatEvent.Event.ENTITY_DIED?" player="+b.readVarInt():"");}
            if(packet instanceof SPacketEntityMetadata)return "METADATA id="+b.readVarInt();
            if(packet instanceof CPacketClientStatus)return "CLIENT_STATUS action="+b.readEnumValue(CPacketClientStatus.State.class);
        } catch(IOException invalid){throw new AssertionError("Fixture packet serialization",invalid);}finally{b.release();}
        return null;
    }
}
