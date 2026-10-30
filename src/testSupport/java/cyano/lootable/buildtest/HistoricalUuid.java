package cyano.lootable.buildtest;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
/** Independent source-fixture identity reader; never rewrites historical NBT. */
public final class HistoricalUuid {
    private HistoricalUuid() { }
    public static UUID read(CompoundTag n,String key) {
        UUID array=null,pair=null;
        if(n.contains(key)) {
            if(!n.contains(key,11) || n.getIntArray(key).length!=4)throw new IllegalArgumentException("Malformed fixture identity array");
            int[] values=n.getIntArray(key);
            array=new UUID(((long)values[0]<<32)|(values[1]&0xffffffffL),((long)values[2]<<32)|(values[3]&0xffffffffL));
        }
        if(n.contains(key+"Most") || n.contains(key+"Least")) {
            if(!n.contains(key+"Most",4) || !n.contains(key+"Least",4))throw new IllegalArgumentException("Malformed fixture identity pair");
            pair=new UUID(n.getLong(key+"Most"),n.getLong(key+"Least"));
        }
        if(array!=null && pair!=null && !array.equals(pair))throw new IllegalArgumentException("Conflicting fixture identities");
        if(array==null && pair==null)throw new IllegalArgumentException("Missing fixture identity");
        return array!=null?array:pair;
    }
}
