package cyano.lootable.buildtest;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEnterPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatEndPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.core.Registry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddMobPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraftforge.fmllegacy.network.ICustomPacket;

/** Observes decoded packets without dropping, delaying, mutating or reordering them. */
final class RespawnPacketTrace extends ChannelDuplexHandler {
    interface After {void received(String packet);}
    private final File log;
    private final After after;
    private long sequence;
    private RespawnPacketTrace(File log,After after){this.log=log;this.after=after;}
    static void install(Connection manager,File log,After after) {
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
            FriendlyByteBuf b=new FriendlyByteBuf(f.getInternalData().duplicate());if(b.readableBytes()<6 || b.readVarInt()!=0)return null;
            int type=b.readVarInt(),id=b.readInt();net.minecraft.world.entity.EntityType<?> entityType=net.minecraft.core.Registry.ENTITY_TYPE.byId(type);
            String name=entityType==null?"unknown":entityType.getRegistryName().getNamespace();
            return "MOD_SPAWN id="+id+" mod="+name+" type="+type+" uuid="+new UUID(b.readLong(),b.readLong());
        }
        if(!(packet instanceof ClientboundAddMobPacket||packet instanceof ClientboundRespawnPacket||packet instanceof ClientboundRemoveEntitiesPacket||packet instanceof ClientboundForgetLevelChunkPacket
            ||packet instanceof ClientboundLevelChunkPacket||packet instanceof ClientboundPlayerCombatEnterPacket||packet instanceof ClientboundPlayerCombatEndPacket
            ||packet instanceof ClientboundPlayerCombatKillPacket||packet instanceof ClientboundSetEntityDataPacket||packet instanceof ServerboundClientCommandPacket))return null;
        if(packet instanceof ClientboundRespawnPacket) {
            return NativeRespawnDescription.describe((ClientboundRespawnPacket)packet);
        }
        // Vanilla strips receiving-side accessors from a dedicated distribution.
        // Read an independent serialization, never its live reader index/fields.
        FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
        try {
            ((Packet<?>)packet).write(b);
            if(packet instanceof ClientboundAddMobPacket) {
                int id=b.readVarInt();UUID uuid=b.readUUID();int type=b.readVarInt();
                net.minecraft.world.entity.EntityType<?> entityType=Registry.ENTITY_TYPE.byId(type);
                String name=entityType==null?"unknown":entityType.getRegistryName().getNamespace();
                return "MOD_SPAWN id="+id+" mod="+name+" type="+type+" uuid="+uuid;
            }
            if(packet instanceof ClientboundRemoveEntitiesPacket){int[] ids=new int[b.readVarInt()];for(int i=0;i<ids.length;i++)ids[i]=b.readVarInt();return "DESTROY ids="+Arrays.toString(ids);}
            if(packet instanceof ClientboundForgetLevelChunkPacket)return "UNLOAD chunk="+b.readInt()+","+b.readInt();
            // Native 1.17 always carries the biome array with this chunk packet;
            // the old partial/full flag no longer exists on the wire.
            if(packet instanceof ClientboundLevelChunkPacket)return "CHUNK chunk="+b.readInt()+","+b.readInt()+" full=true";
            if(packet instanceof ClientboundPlayerCombatEnterPacket)return "COMBAT event=ENTER_COMBAT";
            if(packet instanceof ClientboundPlayerCombatEndPacket)return "COMBAT event=END_COMBAT";
            if(packet instanceof ClientboundPlayerCombatKillPacket)return "COMBAT event=ENTITY_DIED player="+b.readVarInt();
            if(packet instanceof ClientboundSetEntityDataPacket)return "METADATA id="+b.readVarInt();
            if(packet instanceof ServerboundClientCommandPacket)return "CLIENT_STATUS action="+b.readEnum(ServerboundClientCommandPacket.Action.class);
        } catch(RuntimeException invalid){throw new AssertionError("Fixture packet serialization",invalid);}finally{b.release();}
        return null;
    }
}
