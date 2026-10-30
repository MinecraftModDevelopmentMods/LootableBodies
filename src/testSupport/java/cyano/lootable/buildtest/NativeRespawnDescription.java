package cyano.lootable.buildtest;

import java.lang.reflect.Field;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;

/** Read decoded fixture packets without invoking the client-only dimension getter. */
final class NativeRespawnDescription {
    private NativeRespawnDescription() { }
    static String describe(ClientboundRespawnPacket packet) {
        try {
            ResourceKey<?> dimension=null;
            long seed=0;
            for(Field field:ClientboundRespawnPacket.class.getDeclaredFields()) {
                if(field.getType()==ResourceKey.class) {
                    if(dimension!=null)throw new AssertionError("Ambiguous respawn world key");
                    field.setAccessible(true);dimension=(ResourceKey<?>)field.get(packet);
                } else if(field.getType()==long.class) {
                    field.setAccessible(true);seed=field.getLong(packet);
                }
            }
            if(dimension==null)throw new AssertionError("Missing respawn world key");
            return "RESPAWN dimension="+dimension.location()+" hashedSeed="+seed;
        } catch(ReflectiveOperationException invalid) {throw new AssertionError("Fixture respawn observation",invalid);}
    }
}
