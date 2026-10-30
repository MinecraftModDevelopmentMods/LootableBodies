package cyano.lootable.buildtest;

import java.util.List;
import java.util.ArrayList;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
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
    public static NativeMenuObservation decode(Packet<?> packet) {
        boolean content=packet instanceof ClientboundContainerSetContentPacket;
        if(!content && !(packet instanceof ClientboundContainerSetSlotPacket))return null;
        FriendlyByteBuf wire=new FriendlyByteBuf(Unpooled.buffer());
        try {
            // Receiving-side getters can be stripped from a dedicated build.
            // Serialize independently instead of touching live packet state.
            packet.write(wire);
            int window=content?wire.readUnsignedByte():wire.readByte();int state=wire.readVarInt();
            NativeMenuObservation result;
            if(content) {
                int count=wire.readVarInt();List<ItemStack> items=new ArrayList<>();
                if(count<0 || count>4096)throw new IllegalStateException("Unexpected native menu slot count");
                for(int index=0;index<count;index++)items.add(wire.readItem());
                result=new NativeMenuObservation(true,window,state,-1,items,wire.readItem(),ItemStack.EMPTY);
            } else result=new NativeMenuObservation(false,window,state,wire.readShort(),java.util.Collections.emptyList(),ItemStack.EMPTY,wire.readItem());
            if(wire.isReadable())throw new IllegalStateException("Unexpected trailing native menu fields");
            return result;
        } catch(RuntimeException invalid) {throw new AssertionError("Native menu packet serialization failed",invalid);}
        finally {wire.release();}
    }
}
