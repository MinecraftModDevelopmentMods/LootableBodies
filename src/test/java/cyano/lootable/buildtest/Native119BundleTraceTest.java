package cyano.lootable.buildtest;

import io.netty.channel.*;
import io.netty.channel.local.LocalChannel;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real native19 bundling pipeline; the private observer must see decoded wire children. */
class Native119BundleTraceTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    private ClientboundAddEntityPacket spawn(int id) {
        return new ClientboundAddEntityPacket(id,new UUID(1194,id),1,2,3,4,5,EntityType.PIG,6,new Vec3(.1,.2,.3),7);
    }
    private byte[] bytes(Packet<?> packet) {
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {packet.write(buffer);byte[] result=new byte[buffer.readableBytes()];buffer.getBytes(0,result);return result;}
        finally {buffer.release();}
    }
    private List<Packet<?>> wire(ClientboundBundlePacket bundle) {
        List<Packet<?>> wire=new ArrayList<>();
        ConnectionProtocol.PLAY.getBundlerInfo(PacketFlow.CLIENTBOUND).unbundlePacket(bundle,wire::add);
        return wire;
    }
    private final class Fixture implements AutoCloseable {
        final EventLoopGroup loop=new DefaultEventLoopGroup(1);
        final LocalChannel channel=new LocalChannel();
        final List<Object> received=new ArrayList<>();
        final URLClassLoader loader;
        final Path log=temporary.resolve("received-packets.txt");
        Fixture() throws Exception {
            loader=new URLClassLoader(new URL[]{Path.of("build/integration-test-mod/classes").toUri().toURL()},getClass().getClassLoader());
            channel.attr(BundlerInfo.BUNDLER_PROVIDER).set(ConnectionProtocol.PLAY);
            channel.pipeline().addLast("bundler",new PacketBundlePacker(PacketFlow.CLIENTBOUND));
            channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter(){
                @Override public void channelRead(ChannelHandlerContext context,Object packet){received.add(packet);}
            });
            loop.register(channel).sync();
            Connection connection=new Connection(PacketFlow.CLIENTBOUND);
            Field field=Connection.class.getDeclaredField("channel");field.setAccessible(true);field.set(connection,channel);
            Class<?> observer=Class.forName("cyano.lootable.buildtest.RespawnPacketTrace",true,loader);
            Class<?> after=Class.forName("cyano.lootable.buildtest.RespawnPacketTrace$After",true,loader);
            Method install=observer.getDeclaredMethod("install",Connection.class,File.class,after);install.setAccessible(true);
            install.invoke(null,connection,log.toFile(),null);
        }
        void receive(List<Packet<?>> packets) {
            channel.eventLoop().submit(()->packets.forEach(channel.pipeline()::fireChannelRead)).syncUninterruptibly();
        }
        @Override public void close() throws Exception {
            channel.close().sync();loop.shutdownGracefully(0,2,TimeUnit.SECONDS).sync();loader.close();
        }
    }
    @Test void actualBundledSpawnIsObservedWithoutChangingNativeDeliveryOrBytes() throws Exception {
        ClientboundAddEntityPacket spawn=spawn(12);Packet<ClientGamePacketListener> remove=new ClientboundRemoveEntitiesPacket(12);
        ClientboundBundlePacket bundle=new ClientboundBundlePacket(List.of(spawn,remove));byte[] original=bytes(spawn);
        try(Fixture fixture=new Fixture()) {
            fixture.receive(wire(bundle));assertEquals(1,fixture.received.size());
            ClientboundBundlePacket delivered=assertInstanceOf(ClientboundBundlePacket.class,fixture.received.get(0));
            List<Packet<ClientGamePacketListener>> children=new ArrayList<>();delivered.subPackets().forEach(children::add);
            assertEquals(List.of(spawn,remove),children);assertArrayEquals(original,bytes(spawn));
            String text=Files.readString(fixture.log);
            assertTrue(text.contains(" IN MOD_SPAWN id=12 mod=minecraft ")&&text.contains("uuid="+spawn.getUUID()),"Native bundled spawn disappeared from receiving wire proof");
            assertTrue(text.indexOf(" IN MOD_SPAWN ")<text.indexOf(" IN DESTROY "));
        }
    }
    @Test void multipleNativeBundlesAndOrdinaryPacketsRetainTheirWireOrder() throws Exception {
        ClientboundAddEntityPacket first=spawn(13),second=spawn(14);
        ClientboundBundlePacket a=new ClientboundBundlePacket(List.of(first));
        ClientboundRemoveEntitiesPacket ordinary=new ClientboundRemoveEntitiesPacket(13);
        ClientboundBundlePacket b=new ClientboundBundlePacket(List.of(second));
        List<Packet<?>> wire=new ArrayList<>(wire(a));wire.add(ordinary);wire.addAll(wire(b));
        try(Fixture fixture=new Fixture()) {
            fixture.receive(wire);assertEquals(3,fixture.received.size());assertSame(ordinary,fixture.received.get(1));
            List<String> lines=Files.readAllLines(fixture.log);assertEquals(3,lines.size());
            assertTrue(lines.get(0).contains("uuid="+first.getUUID()));assertTrue(lines.get(1).contains(" IN DESTROY "));
            assertTrue(lines.get(2).contains("uuid="+second.getUUID()));
        }
    }
}
