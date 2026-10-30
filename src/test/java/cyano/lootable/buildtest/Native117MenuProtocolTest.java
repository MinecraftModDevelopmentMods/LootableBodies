package cyano.lootable.buildtest;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class Native117MenuProtocolTest {
    @BeforeAll static void bootstrap(){net.minecraft.server.Bootstrap.bootStrap();}
    private ItemStack stack(int count) {ItemStack item=new ItemStack(Items.DIAMOND,count);CompoundTag tag=new CompoundTag();tag.putString("keep","native packet");item.setTag(tag);return item;}
    private byte[] bytes(Packet<?> packet) throws Exception {
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {packet.write(buffer);byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(0,bytes);return bytes;}finally{buffer.release();}
    }
    @Test void fullContentCarriesStateAndCursorWithoutMutatingThePacket() throws Exception {
        NonNullList<ItemStack> slots=NonNullList.withSize(84,ItemStack.EMPTY);slots.set(47,stack(31));ItemStack cursor=stack(17);
        ClientboundContainerSetContentPacket packet=new ClientboundContainerSetContentPacket(7,321,slots,cursor);byte[] before=bytes(packet);
        NativeMenuObservation observed=NativeMenuObservation.decode(packet);assertNotNull(observed);assertTrue(observed.content);
        assertEquals(7,observed.containerId);assertEquals(321,observed.stateId);assertEquals(84,observed.items.size());
        assertTrue(ItemStack.matches(slots.get(47),observed.items.get(47)));assertTrue(ItemStack.matches(cursor,observed.carried));assertArrayEquals(before,bytes(packet));
    }
    @Test void slotAndCursorCorrectionsUseTheirActualNativeWindowAndState() throws Exception {
        for(int window:new int[]{7,-1,-2}) {
            ClientboundContainerSetSlotPacket packet=new ClientboundContainerSetSlotPacket(window,123,window==-1?-1:47,stack(19));byte[] before=bytes(packet);
            NativeMenuObservation observed=NativeMenuObservation.decode(packet);assertNotNull(observed);assertFalse(observed.content);
            assertEquals(window,observed.containerId);assertEquals(123,observed.stateId);assertEquals(window==-1?-1:47,observed.slot);
            assertTrue(ItemStack.matches(stack(19),observed.item));assertArrayEquals(before,bytes(packet));
        }
    }
    @Test void clickCodecRetainsStatePredictedSlotsAndCarriedStack() throws Exception {
        Int2ObjectOpenHashMap<ItemStack> changed=new Int2ObjectOpenHashMap<>();changed.put(6,stack(13));changed.put(47,ItemStack.EMPTY);
        ServerboundContainerClickPacket outgoing=new ServerboundContainerClickPacket(7,32766,6,1,ClickType.PICKUP,stack(9),changed);
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
        try {
            outgoing.write(buffer);byte[] before=new byte[buffer.readableBytes()];buffer.getBytes(0,before);
            ServerboundContainerClickPacket decoded=new ServerboundContainerClickPacket(buffer);
            assertEquals(7,decoded.getContainerId());assertEquals(32766,decoded.getStateId());assertEquals(6,decoded.getSlotNum());assertEquals(1,decoded.getButtonNum());
            assertEquals(ClickType.PICKUP,decoded.getClickType());assertTrue(ItemStack.matches(stack(9),decoded.getCarriedItem()));
            assertEquals(changed.keySet(),decoded.getChangedSlots().keySet());assertTrue(ItemStack.matches(stack(13),decoded.getChangedSlots().get(6)));assertTrue(decoded.getChangedSlots().get(47).isEmpty());
            assertEquals(before.length,buffer.readerIndex());byte[] after=new byte[before.length];buffer.getBytes(0,after);assertArrayEquals(before,after);
        }finally{buffer.release();}
    }
    @Test void unrelatedPacketsAreIgnoredWithoutSerializingThem() {
        Packet<ClientGamePacketListener> other=new Packet<ClientGamePacketListener>() {
            public void write(FriendlyByteBuf buffer){throw new AssertionError("Observer serialized an unrelated packet");}
            public void handle(ClientGamePacketListener listener){throw new AssertionError("Observer handled a live packet");}
        };
        assertNull(NativeMenuObservation.decode(other));
    }
}
