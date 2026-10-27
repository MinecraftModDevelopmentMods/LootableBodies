package cyano.lootable.buildtest;

import java.lang.reflect.Field;
import net.minecraft.network.play.server.SRespawnPacket;
import net.minecraft.world.dimension.DimensionType;

/** Read decoded fixture packets without invoking the client-only dimension getter. */
final class NativeRespawnDescription {
    private NativeRespawnDescription() { }
    static String describe(SRespawnPacket packet) {
        try {
            DimensionType dimension=null;
            int id=0;
            long seed=0;
            for(Field field:SRespawnPacket.class.getDeclaredFields()) {
                if(field.getType()==DimensionType.class) {
                    field.setAccessible(true);dimension=(DimensionType)field.get(packet);
                } else if(field.getType()==int.class) {
                    field.setAccessible(true);id=field.getInt(packet);
                } else if(field.getType()==long.class) {
                    field.setAccessible(true);seed=field.getLong(packet);
                }
            }
            return "RESPAWN dimension="+(dimension==null?id:dimension.getId())+" hashedSeed="+seed;
        } catch(ReflectiveOperationException invalid) {throw new AssertionError("Fixture respawn observation",invalid);}
    }
}
