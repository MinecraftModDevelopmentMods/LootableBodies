package cyano.lootable.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import net.minecraft.network.ProtocolType;
import net.minecraft.network.NetworkManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the queued clearReadPending race in Minecraft's pinned Netty. */
class LoginReadGuardTest {
    public static final class TestChannel extends NioSocketChannel {
        public int readInterest() { return selectionKey().interestOps() & java.nio.channels.SelectionKey.OP_READ; }
    }
    private static void guard(Channel channel) throws Exception {
        try {Class.forName("cyano.lootable.network.ClientLoginReadGuard").getMethod("resumeReads",Channel.class).invoke(null,channel);}
        catch(ClassNotFoundException beforeImplementation) { }
    }
    private static final class Connection implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final ExecutorService acceptor=Executors.newSingleThreadExecutor();
        final NioEventLoopGroup loop=new NioEventLoopGroup(1);
        final CompletableFuture<byte[]> received=new CompletableFuture<>();
        final Socket peer;
        final TestChannel channel;
        Connection()throws Exception {
            Future<Socket> accepted=acceptor.submit(server::accept);
            channel=(TestChannel)new Bootstrap().group(loop).channel(TestChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                            protected void channelRead0(ChannelHandlerContext context,ByteBuf input) {
                                while(input.isReadable())bytes.write(input.readByte());
                                if(bytes.size()>=8)received.complete(bytes.toByteArray());
                            }
                        });
                    }
                }).connect(server.getLocalSocketAddress()).sync().channel();
            peer=accepted.get(5,TimeUnit.SECONDS);
            channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).set(ProtocolType.LOGIN);
            drain();
        }
        void drain()throws Exception {channel.eventLoop().submit(()->{}).get(5,TimeUnit.SECONDS);}
        void stall()throws Exception {
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            Future<?> active=channel.eventLoop().submit(()-> {
                entered.countDown();
                try {if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture synchronization timed out");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
                channel.config().setAutoRead(true);
            });
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                // Vanilla disables reads on its connector thread, then enables
                // them on the event loop before the older clear task executes.
                channel.config().setAutoRead(false);
            } finally {resume.countDown();}
            active.get(5,TimeUnit.SECONDS);drain();
            assertTrue(channel.config().isAutoRead());
            assertEquals(0,channel.readInterest(),"Pinned Netty race was not reproduced");
        }
        public void close()throws Exception {
            channel.close().sync();peer.close();server.close();acceptor.shutdownNow();loop.shutdownGracefully(0,5,TimeUnit.SECONDS).sync();
        }
    }
    @Test void stalledLoginRestoresReadInterestAndReceivesTheOriginalBytes()throws Exception {
        try(Connection c=new Connection()) {
            c.stall();guard(c.channel);c.drain();
            assertNotEquals(0,c.channel.readInterest(),"Auto-read login channel has no selector read interest");
            byte[] message={1,2,3,4,5,6,7,8};c.peer.getOutputStream().write(message);c.peer.getOutputStream().flush();
            assertArrayEquals(message,c.received.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void deliberateReadPauseIsPreserved()throws Exception {
        try(Connection c=new Connection()) {
            c.channel.config().setAutoRead(false);c.drain();guard(c.channel);c.drain();
            assertFalse(c.channel.config().isAutoRead());assertEquals(0,c.channel.readInterest());
        }
    }
    @Test void otherProtocolStatesAreNotChanged()throws Exception {
        for(ProtocolType state:new ProtocolType[]{ProtocolType.HANDSHAKING,ProtocolType.STATUS,ProtocolType.PLAY}) {
            try(Connection c=new Connection()) {
                c.stall();c.channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).set(state);guard(c.channel);c.drain();
                assertEquals(0,c.channel.readInterest());assertEquals(state,c.channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get());
            }
        }
    }
    @Test void noConnectionIsHarmless()throws Exception {guard(null);}
    @Test void closedConnectionIsHarmless()throws Exception {
        try(Connection c=new Connection()) {c.channel.close().sync();guard(c.channel);assertFalse(c.channel.isActive());}
    }
    @Test void aPauseQueuedBeforeTheRepairExecutesIsPreserved()throws Exception {
        try(Connection c=new Connection()) {
            c.stall();CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            c.channel.eventLoop().execute(()-> {
                entered.countDown();
                try {if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture synchronization timed out");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
            });
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));guard(c.channel);c.channel.config().setAutoRead(false);}
            finally {resume.countDown();}
            c.drain();assertFalse(c.channel.config().isAutoRead());assertEquals(0,c.channel.readInterest());
        }
    }
    @Test void aProtocolChangeQueuedBeforeTheRepairExecutesIsPreserved()throws Exception {
        try(Connection c=new Connection()) {
            c.stall();CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            c.channel.eventLoop().execute(()-> {
                entered.countDown();
                try {if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture synchronization timed out");}
                catch(InterruptedException failure){throw new AssertionError(failure);}
            });
            try {assertTrue(entered.await(5,TimeUnit.SECONDS));guard(c.channel);c.channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).set(ProtocolType.PLAY);}
            finally {resume.countDown();}
            c.drain();assertEquals(0,c.channel.readInterest());assertEquals(ProtocolType.PLAY,c.channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get());
        }
    }
}
