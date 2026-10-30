package cyano.lootable.upgrade;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Reads both native UUID encodings without inventing a saved entity identity. */
final class CorpseIdentity {
    private CorpseIdentity() { }
    static UUID read(CompoundTag data) {
        UUID current=null,legacy=null;
        if(data.contains("UUID")) {
            if(!data.contains("UUID",11) || data.getIntArray("UUID").length!=4)
                throw new IllegalArgumentException("Malformed corpse UUID array");
            current=data.getUUID("UUID");
        }
        if(data.contains("UUIDMost") || data.contains("UUIDLeast")) {
            if(!data.contains("UUIDMost",4) || !data.contains("UUIDLeast",4))
                throw new IllegalArgumentException("Malformed legacy corpse UUID");
            legacy=new UUID(data.getLong("UUIDMost"),data.getLong("UUIDLeast"));
        }
        if(current!=null && legacy!=null && !current.equals(legacy))
            throw new IllegalArgumentException("Conflicting saved corpse UUIDs");
        if(current==null && legacy==null)throw new IllegalArgumentException("Missing saved corpse UUID");
        return current==null?legacy:current;
    }
    static boolean present(CompoundTag data) {
        return data.contains("UUID") || data.contains("UUIDMost") || data.contains("UUIDLeast");
    }
}
