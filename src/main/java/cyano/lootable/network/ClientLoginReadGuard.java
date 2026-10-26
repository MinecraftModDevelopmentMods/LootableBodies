package cyano.lootable.network;

import io.netty.channel.Channel;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.network.ProtocolType;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.event.TickEvent;

/** Repairs the pinned Netty's stale read-clear task during client login. */
@OnlyIn(Dist.CLIENT)
public final class ClientLoginReadGuard {
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END)return;
        Minecraft minecraft=Minecraft.getInstance();
        if(!(minecraft.currentScreen instanceof ConnectingScreen))return;
        NetworkManager manager=ObfuscationReflectionHelper.getPrivateValue(
                ConnectingScreen.class,(ConnectingScreen)minecraft.currentScreen,"field_146371_g");
        if(manager!=null)resumeReads(manager.channel());
    }

    /** Keep intentional pauses and all non-login connections untouched. */
    public static void resumeReads(Channel channel) {
        if(channel==null || !channel.isActive() || !channel.isRegistered())return;
        try {
            channel.eventLoop().execute(()-> {
                // Check on the event loop, after any protocol or pause changes.
                // A queued clearReadPending from the connector thread can have
                // removed OP_READ even though autoRead has already become true.
                if(channel.isActive() && channel.config().isAutoRead()
                        && channel.attr(NetworkManager.PROTOCOL_ATTRIBUTE_KEY).get()==ProtocolType.LOGIN)
                    channel.read();
            });
        }catch(RejectedExecutionException closed) {
            if(channel.isActive())throw closed;
        }
    }
}
