package cyano.lootable.buildtest;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native115PacketTest {
    static { net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap(); }
    private static final long SEED=0x13572468ABCDEF01L;
    private ClientboundRespawnPacket packet(ResourceKey<Level> dimension,long seed) {
        return new ClientboundRespawnPacket(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD,
                dimension,seed,GameType.SURVIVAL,GameType.CREATIVE,false,true,
                ClientboundRespawnPacket.KEEP_ALL_DATA,java.util.Optional.empty());
    }
    @Test void native115HashedSeedSurvivesDecodedPacketObservation() throws Exception {
        FriendlyByteBuf bytes=new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundRespawnPacket outgoing=packet(Level.NETHER,SEED);
            outgoing.write(bytes);
            byte[] before=new byte[bytes.readableBytes()];bytes.getBytes(0,before);
            ClientboundRespawnPacket incoming=new ClientboundRespawnPacket(bytes);
            String expected="RESPAWN dimension=minecraft:the_nether hashedSeed="+SEED;
            assertEquals(expected,NativeRespawnDescription.describe(outgoing));
            assertEquals(expected,NativeRespawnDescription.describe(incoming));
            assertEquals(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD,incoming.getDimensionType(),"Native codec dimension type key was lost");
            assertEquals(before.length,bytes.readerIndex());
            byte[] after=new byte[before.length];bytes.getBytes(0,after);assertArrayEquals(before,after);
        } finally {bytes.release();}
    }
    @Test void differentNative115SeedsCannotHaveTheSameDescription() {
        assertNotEquals(NativeRespawnDescription.describe(packet(Level.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(Level.OVERWORLD,SEED+1)));
    }
    @Test void allSignedSeedValuesRemainVisible() {
        for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE})
            assertEquals("RESPAWN dimension=minecraft:overworld hashedSeed="+seed,
                NativeRespawnDescription.describe(packet(Level.OVERWORLD,seed)));
    }
    @Test void nativeDimensionDistinctionIsRetained() {
        assertNotEquals(NativeRespawnDescription.describe(packet(Level.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(Level.NETHER,SEED)));
    }
    @Test void observationKeepsUnknownDimensionKeysAndEveryDecodedField() throws Exception {
        ResourceKey<Level> key=ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,new net.minecraft.resources.ResourceLocation("fixture:unhandled"));
        ClientboundRespawnPacket packet=packet(key,SEED);
        java.util.Map<Field,Object> before=new java.util.HashMap<>();
        for(Field field:ClientboundRespawnPacket.class.getDeclaredFields()){field.setAccessible(true);before.put(field,field.get(packet));}
        assertEquals("RESPAWN dimension=fixture:unhandled hashedSeed="+SEED,NativeRespawnDescription.describe(packet));
        for(java.util.Map.Entry<Field,Object> field:before.entrySet())assertEquals(field.getValue(),field.getKey().get(packet));
    }
}
