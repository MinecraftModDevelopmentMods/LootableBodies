package cyano.lootable.buildtest;

import java.util.List;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;

/** Receiving-side menu observations from independent native serialization. */
public final class NativeMenuObservation {
    public final boolean content;
    public final int containerId,stateId,slot;
    public final List<ItemStack> items;
    public final ItemStack carried,item;
    NativeMenuObservation(boolean content,int containerId,int stateId,int slot,List<ItemStack> items,ItemStack carried,ItemStack item) {
        this.content=content;this.containerId=containerId;this.stateId=stateId;this.slot=slot;this.items=items;this.carried=carried;this.item=item;
    }
    public static NativeMenuObservation decode(Packet<?> packet,RegistryAccess registries) {
        if(!(packet instanceof ClientboundContainerSetContentPacket)
                && !(packet instanceof ClientboundContainerSetSlotPacket))return null;
        var wire=new RegistryFriendlyByteBuf(Unpooled.buffer(),registries);
        try {
            NativeMenuObservation result;
            if(packet instanceof ClientboundContainerSetContentPacket content) {
                ClientboundContainerSetContentPacket.STREAM_CODEC.encode(wire,content);
                var decoded=ClientboundContainerSetContentPacket.STREAM_CODEC.decode(wire);
                result=new NativeMenuObservation(true,decoded.getContainerId(),decoded.getStateId(),-1,
                        List.copyOf(decoded.getItems()),decoded.getCarriedItem(),ItemStack.EMPTY);
            } else {
                ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(wire,(ClientboundContainerSetSlotPacket)packet);
                var decoded=ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(wire);
                result=new NativeMenuObservation(false,decoded.getContainerId(),decoded.getStateId(),
                        decoded.getSlot(),List.of(),ItemStack.EMPTY,decoded.getItem());
            }
            if(wire.isReadable())throw new IllegalStateException("Unexpected trailing native menu fields");
            return result;
        } catch(RuntimeException invalid) {
            throw new AssertionError("Native menu packet serialization failed",invalid);
        } finally {
            wire.release();
        }
    }
}
