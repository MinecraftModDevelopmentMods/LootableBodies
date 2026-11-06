package cyano.lootable.buildtest;
import java.io.*;
import java.util.Arrays;
import io.netty.channel.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;

/** Observes decoded packets without dropping, delaying, mutating or reordering them. */
final class RespawnPacketTrace extends ChannelDuplexHandler {
    interface After {void received(String packet);}
    private final File log;
    private final After after;
    private long sequence;
    private RespawnPacketTrace(File log,After after){this.log=log;this.after=after;}
    static void install(Connection manager,File log,After after) {
        Channel channel=manager.channel();
        // Native19 assembles decoded spawn children into a bundle before the
        // packet listener. Observe that real wire stream before aggregation.
        channel.eventLoop().submit(()->{
            String before=channel.pipeline().get("bundler")!=null?"bundler":"packet_handler";
            channel.pipeline().addBefore(before,"corpse_packet_trace",new RespawnPacketTrace(log,after));
        }).syncUninterruptibly();
    }
    @Override public void channelRead(ChannelHandlerContext context,Object packet) throws Exception {
        String description=describe(packet);if(description!=null)record("IN "+description);
        if(Boolean.getBoolean("lootablebodies.respawn.dropSpawn")&&log.getName().equals("client-packets.txt")
            &&description!=null&&description.startsWith("MOD_SPAWN ")&&description.contains("mod=lootablebodies ")) {
            record("FAULT_DROPPED "+description);return;
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
        if(packet instanceof ClientboundAddEntityPacket spawn) {
            var registry=BuiltInRegistries.ENTITY_TYPE;
            var key=registry.getKey(spawn.getType());
            return "MOD_SPAWN id="+spawn.getId()+" mod="+key.getNamespace()+" type="
                    +registry.getId(spawn.getType())+" uuid="+spawn.getUUID();
        }
        if(packet instanceof ClientboundRespawnPacket respawn)return NativeRespawnDescription.describe(respawn);
        if(packet instanceof ClientboundRemoveEntitiesPacket destroy)
            return "DESTROY ids="+Arrays.toString(destroy.getEntityIds().toIntArray());
        if(packet instanceof ClientboundForgetLevelChunkPacket unload)
            return "UNLOAD chunk="+unload.pos().x+","+unload.pos().z;
        if(packet instanceof ClientboundLevelChunkWithLightPacket chunk)
            return "CHUNK chunk="+chunk.getX()+","+chunk.getZ()+" full=true";
        if(packet instanceof ClientboundPlayerCombatEnterPacket)return "COMBAT event=ENTER_COMBAT";
        if(packet instanceof ClientboundPlayerCombatEndPacket)return "COMBAT event=END_COMBAT";
        if(packet instanceof ClientboundPlayerCombatKillPacket killed)
            return "COMBAT event=ENTITY_DIED player="+killed.playerId();
        if(packet instanceof ClientboundSetEntityDataPacket metadata)return "METADATA id="+metadata.id();
        if(packet instanceof ServerboundClientCommandPacket status)return "CLIENT_STATUS action="+status.getAction();
        return null;
    }
}
