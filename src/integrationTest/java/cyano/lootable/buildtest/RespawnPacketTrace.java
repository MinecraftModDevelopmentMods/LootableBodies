package cyano.lootable.buildtest;
import net.minecraft.network.play.server.SEntityMetadataPacket;
import net.minecraft.network.play.server.SRespawnPacket;
import net.minecraft.network.play.server.SDestroyEntitiesPacket;
import net.minecraft.network.play.server.SCombatPacket;
import net.minecraft.network.play.server.SChunkDataPacket;
import net.minecraft.network.play.server.SUnloadChunkPacket;
import net.minecraft.util.registry.Registry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.*;
import net.minecraft.network.play.client.CClientStatusPacket;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.network.ICustomPacket;

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
            record("FAULT_DROPPED "+description);if(packet instanceof ICustomPacket)((ICustomPacket<?>)packet).getInternalData().release();return;
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
        if(packet instanceof ICustomPacket) {
            ICustomPacket<?> f=(ICustomPacket<?>)packet;if(!f.getName().toString().equals("fml:play"))return null;
            PacketBuffer b=new PacketBuffer(f.getInternalData().duplicate());if(b.readableBytes()<6 || b.readVarInt()!=0)return null;
            int type=b.readVarInt(),id=b.readInt();net.minecraft.entity.EntityType<?> entityType=net.minecraft.util.registry.Registry.ENTITY_TYPE.getByValue(type);
            String name=entityType==null?"unknown":entityType.getRegistryName().getNamespace();
            return "MOD_SPAWN id="+id+" mod="+name+" type="+type+" uuid="+new UUID(b.readLong(),b.readLong());
        }
        if(!(packet instanceof SSpawnMobPacket||packet instanceof SRespawnPacket||packet instanceof SDestroyEntitiesPacket||packet instanceof SUnloadChunkPacket
            ||packet instanceof SChunkDataPacket||packet instanceof SCombatPacket||packet instanceof SEntityMetadataPacket||packet instanceof CClientStatusPacket))return null;
        if(packet instanceof SSpawnMobPacket) {
            // Native 1.15 sends metadata separately. Observe only the decoded
            // spawn scalars and UUID; never alter the live receiving packet.
            int id=net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(SSpawnMobPacket.class,(SSpawnMobPacket)packet,"field_149042_a");
            int type=net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(SSpawnMobPacket.class,(SSpawnMobPacket)packet,"field_149040_b");
            UUID uuid=null;
            try {for(java.lang.reflect.Field field:SSpawnMobPacket.class.getDeclaredFields())if(field.getType()==UUID.class) {field.setAccessible(true);uuid=(UUID)field.get(packet);}}
            catch(ReflectiveOperationException invalid) {throw new AssertionError("Fixture spawn observation",invalid);}
            net.minecraft.entity.EntityType<?> entityType=Registry.ENTITY_TYPE.getByValue(type);
            String name=entityType==null?"unknown":entityType.getRegistryName().getNamespace();
            return "MOD_SPAWN id="+id+" mod="+name+" type="+type+" uuid="+uuid;
        }
        if(packet instanceof SRespawnPacket) {
            return NativeRespawnDescription.describe((SRespawnPacket)packet);
        }
        // Vanilla strips receiving-side accessors from a dedicated distribution.
        // Read an independent serialization, never its live reader index/fields.
        PacketBuffer b=new PacketBuffer(Unpooled.buffer());
        try {
            ((IPacket<?>)packet).writePacketData(b);
            if(packet instanceof SDestroyEntitiesPacket){int[] ids=new int[b.readVarInt()];for(int i=0;i<ids.length;i++)ids[i]=b.readVarInt();return "DESTROY ids="+Arrays.toString(ids);}
            if(packet instanceof SUnloadChunkPacket)return "UNLOAD chunk="+b.readInt()+","+b.readInt();
            if(packet instanceof SChunkDataPacket)return "CHUNK chunk="+b.readInt()+","+b.readInt()+" full="+b.readBoolean();
            if(packet instanceof SCombatPacket){SCombatPacket.Event type=b.readEnumValue(SCombatPacket.Event.class);return "COMBAT event="+type+(type==SCombatPacket.Event.ENTITY_DIED?" player="+b.readVarInt():"");}
            if(packet instanceof SEntityMetadataPacket)return "METADATA id="+b.readVarInt();
            if(packet instanceof CClientStatusPacket)return "CLIENT_STATUS action="+b.readEnumValue(CClientStatusPacket.State.class);
        } catch(IOException invalid){throw new AssertionError("Fixture packet serialization",invalid);}finally{b.release();}
        return null;
    }
}
