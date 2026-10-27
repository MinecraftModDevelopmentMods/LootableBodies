package cyano.lootable.buildtest;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SRespawnPacket;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldType;
import net.minecraft.world.dimension.DimensionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native115PacketTest {
    private static final long SEED=0x13572468ABCDEF01L;
    private SRespawnPacket packet(DimensionType dimension,long seed) {
        return new SRespawnPacket(dimension,seed,WorldType.DEFAULT,GameType.SURVIVAL);
    }
    @Test void native115HashedSeedSurvivesDecodedPacketObservation() throws Exception {
        PacketBuffer bytes=new PacketBuffer(Unpooled.buffer());
        try {
            SRespawnPacket outgoing=packet(DimensionType.THE_NETHER,SEED);
            outgoing.writePacketData(bytes);
            byte[] before=new byte[bytes.readableBytes()];bytes.getBytes(0,before);
            SRespawnPacket incoming=new SRespawnPacket();incoming.readPacketData(bytes);
            String expected="RESPAWN dimension=-1 hashedSeed="+SEED;
            assertEquals(expected,NativeRespawnDescription.describe(outgoing));
            assertEquals(expected,NativeRespawnDescription.describe(incoming));
            for(Field field:SRespawnPacket.class.getDeclaredFields()) if(field.getType()==DimensionType.class) {
                field.setAccessible(true);assertNull(field.get(incoming),"Observation resolved an unhandled client dimension");
            }
            assertEquals(before.length,bytes.readerIndex());
            byte[] after=new byte[before.length];bytes.getBytes(0,after);assertArrayEquals(before,after);
        } finally {bytes.release();}
    }
    @Test void differentNative115SeedsCannotHaveTheSameDescription() {
        assertNotEquals(NativeRespawnDescription.describe(packet(DimensionType.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(DimensionType.OVERWORLD,SEED+1)));
    }
    @Test void allSignedSeedValuesRemainVisible() {
        for(long seed:new long[]{0,-1,Long.MIN_VALUE,Long.MAX_VALUE})
            assertEquals("RESPAWN dimension=0 hashedSeed="+seed,
                NativeRespawnDescription.describe(packet(DimensionType.OVERWORLD,seed)));
    }
    @Test void nativeDimensionDistinctionIsRetained() {
        assertNotEquals(NativeRespawnDescription.describe(packet(DimensionType.OVERWORLD,SEED)),
                        NativeRespawnDescription.describe(packet(DimensionType.THE_NETHER,SEED)));
    }
}
