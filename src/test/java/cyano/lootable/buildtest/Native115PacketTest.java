package cyano.lootable.buildtest;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SRespawnPacket;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.DimensionType;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.DynamicRegistries;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native115PacketTest {
    static { net.minecraft.util.registry.Bootstrap.register(); }
    private static final long SEED=0x13572468ABCDEF01L;
    private SRespawnPacket packet(RegistryKey<World> dimension,long seed) {
        DimensionType type=DynamicRegistries.func_239770_b_().getRegistry(Registry.DIMENSION_TYPE_KEY).getValueForKey(DimensionType.OVERWORLD);
        return new SRespawnPacket(type,dimension,seed,GameType.SURVIVAL,GameType.CREATIVE,false,true,true);
    }
    @Test void native115HashedSeedSurvivesDecodedPacketObservation() throws Exception {
        PacketBuffer bytes=new PacketBuffer(Unpooled.buffer());
        try {
            SRespawnPacket outgoing=packet(World.THE_NETHER,SEED);
            outgoing.writePacketData(bytes);
            byte[] before=new byte[bytes.readableBytes()];bytes.getBytes(0,before);
            SRespawnPacket incoming=new SRespawnPacket();incoming.readPacketData(bytes);
            String expected="RESPAWN dimension=minecraft:the_nether hashedSeed="+SEED;
            assertEquals(expected,NativeRespawnDescription.describe(outgoing));
            assertEquals(expected,NativeRespawnDescription.describe(incoming));
            for(Field field:SRespawnPacket.class.getDeclaredFields()) if(field.getType()==DimensionType.class) {
                field.setAccessible(true);assertNotNull(field.get(incoming),"Native codec dimension type was lost");
            }
            assertEquals(before.length,bytes.readerIndex());
            byte[] after=new byte[before.length];bytes.getBytes(0,after);assertArrayEquals(before,after);
        } finally {bytes.release();}
    }
    @Test void differentNative115SeedsCannotHaveTheSameDescription() {
        assertNotEquals(NativeRespawnDescription.describe(packet(World.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(World.OVERWORLD,SEED+1)));
    }
    @Test void allSignedSeedValuesRemainVisible() {
        for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE})
            assertEquals("RESPAWN dimension=minecraft:overworld hashedSeed="+seed,
                NativeRespawnDescription.describe(packet(World.OVERWORLD,seed)));
    }
    @Test void nativeDimensionDistinctionIsRetained() {
        assertNotEquals(NativeRespawnDescription.describe(packet(World.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(World.THE_NETHER,SEED)));
    }
    @Test void observationKeepsUnknownDimensionKeysAndEveryDecodedField() throws Exception {
        RegistryKey<World> key=RegistryKey.getOrCreateKey(Registry.WORLD_KEY,new net.minecraft.util.ResourceLocation("fixture:unhandled"));
        SRespawnPacket packet=packet(key,SEED);
        java.util.Map<Field,Object> before=new java.util.HashMap<>();
        for(Field field:SRespawnPacket.class.getDeclaredFields()){field.setAccessible(true);before.put(field,field.get(packet));}
        assertEquals("RESPAWN dimension=fixture:unhandled hashedSeed="+SEED,NativeRespawnDescription.describe(packet));
        for(java.util.Map.Entry<Field,Object> field:before.entrySet())assertEquals(field.getValue(),field.getKey().get(packet));
    }
}
