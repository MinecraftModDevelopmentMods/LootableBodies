package cyano.lootable.buildtest;

import java.lang.reflect.Field;
import net.minecraft.network.play.server.SRespawnPacket;
import net.minecraft.util.RegistryKey;

/** Read decoded fixture packets without invoking the client-only dimension getter. */
final class NativeRespawnDescription {
    private NativeRespawnDescription() { }
    static String describe(SRespawnPacket packet) {
        try {
            RegistryKey<?> dimension=null;
            long seed=0;
            for(Field field:SRespawnPacket.class.getDeclaredFields()) {
                if(field.getType()==RegistryKey.class) {
                    if(dimension!=null)throw new AssertionError("Ambiguous respawn world key");
                    field.setAccessible(true);dimension=(RegistryKey<?>)field.get(packet);
                } else if(field.getType()==long.class) {
                    field.setAccessible(true);seed=field.getLong(packet);
                }
            }
            if(dimension==null)throw new AssertionError("Missing respawn world key");
            return "RESPAWN dimension="+dimension.getLocation()+" hashedSeed="+seed;
        } catch(ReflectiveOperationException invalid) {throw new AssertionError("Fixture respawn observation",invalid);}
    }
}
