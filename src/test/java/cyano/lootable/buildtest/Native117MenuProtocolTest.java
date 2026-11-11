package cyano.lootable.buildtest;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class Native117MenuProtocolTest {
    @BeforeAll static void bootstrap(){net.minecraft.server.Bootstrap.bootStrap();}
    private ItemStack stack(int count) {ItemStack item=new ItemStack(Items.DIAMOND,count);CompoundTag tag=new CompoundTag();tag.putString("keep","native packet");item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));return item;}
    private byte[] bytes(Packet<?> packet) throws Exception {
        RegistryFriendlyByteBuf buffer=wire();
        try {
            if(packet instanceof ClientboundContainerSetContentPacket content)
                ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buffer,content);
            else ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buffer,(ClientboundContainerSetSlotPacket)packet);
            byte[] bytes=new byte[buffer.readableBytes()];buffer.getBytes(0,bytes);return bytes;
        }finally{buffer.release();}
    }
    private RegistryFriendlyByteBuf wire() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),registries());
    }
    private RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
    @Test void fullContentCarriesStateAndCursorWithoutMutatingThePacket() throws Exception {
        NonNullList<ItemStack> slots=NonNullList.withSize(84,ItemStack.EMPTY);slots.set(47,stack(31));ItemStack cursor=stack(17);
        ClientboundContainerSetContentPacket packet=new ClientboundContainerSetContentPacket(7,321,slots,cursor);byte[] before=bytes(packet);
        NativeMenuObservation observed=NativeMenuObservation.decode(packet,registries());assertNotNull(observed);assertTrue(observed.content);
        assertEquals(7,observed.containerId);assertEquals(321,observed.stateId);assertEquals(84,observed.items.size());
        assertTrue(ItemStack.matches(slots.get(47),observed.items.get(47)));assertTrue(ItemStack.matches(cursor,observed.carried));assertArrayEquals(before,bytes(packet));
    }
    @Test void slotAndCursorCorrectionsUseTheirActualNativeWindowAndState() throws Exception {
        for(int window:new int[]{7,-1,-2}) {
            ClientboundContainerSetSlotPacket packet=new ClientboundContainerSetSlotPacket(window,123,window==-1?-1:47,stack(19));byte[] before=bytes(packet);
            NativeMenuObservation observed=NativeMenuObservation.decode(packet,registries());assertNotNull(observed);assertFalse(observed.content);
            assertEquals(window,observed.containerId);assertEquals(123,observed.stateId);assertEquals(window==-1?-1:47,observed.slot);
            assertTrue(ItemStack.matches(stack(19),observed.item));assertArrayEquals(before,bytes(packet));
        }
    }
    @Test void clickCodecRetainsStatePredictedSlotsAndCarriedStack() throws Exception {
        HashedPatchMap.HashGenerator hash = component -> component.value().hashCode();
        Int2ObjectOpenHashMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
        changed.put(6, HashedStack.create(stack(13), hash));
        changed.put(47, HashedStack.EMPTY);
        ServerboundContainerClickPacket outgoing = new ServerboundContainerClickPacket(
                7, 32766, (short)6, (byte)1, ContainerInput.PICKUP, changed, HashedStack.create(stack(9), hash));
        RegistryFriendlyByteBuf buffer=wire();
        try {
            ServerboundContainerClickPacket.STREAM_CODEC.encode(buffer,outgoing);byte[] before=new byte[buffer.readableBytes()];buffer.getBytes(0,before);
            ServerboundContainerClickPacket decoded=ServerboundContainerClickPacket.STREAM_CODEC.decode(buffer);
            assertEquals(7,decoded.containerId());assertEquals(32766,decoded.stateId());assertEquals(6,decoded.slotNum());assertEquals(1,decoded.buttonNum());
            assertEquals(ContainerInput.PICKUP,decoded.containerInput());assertTrue(decoded.carriedItem().matches(stack(9), hash));
            assertEquals(changed.keySet(),decoded.changedSlots().keySet());assertTrue(decoded.changedSlots().get(6).matches(stack(13), hash));assertTrue(decoded.changedSlots().get(47).matches(ItemStack.EMPTY, hash));
            assertEquals(before.length,buffer.readerIndex());byte[] after=new byte[before.length];buffer.getBytes(0,after);assertArrayEquals(before,after);
        }finally{buffer.release();}
    }
    @Test void unrelatedPacketsAreIgnoredWithoutSerializingThem() {
        Packet<ClientGamePacketListener> other=new Packet<ClientGamePacketListener>() {
            public net.minecraft.network.protocol.PacketType<? extends Packet<ClientGamePacketListener>> type(){throw new AssertionError("Observer serialized an unrelated packet");}
            public void handle(ClientGamePacketListener listener){throw new AssertionError("Observer handled a live packet");}
        };
        assertNull(NativeMenuObservation.decode(other,registries()));
    }
}
