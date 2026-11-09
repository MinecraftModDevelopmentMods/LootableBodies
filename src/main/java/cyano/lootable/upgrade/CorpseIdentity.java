package cyano.lootable.upgrade;

import static cyano.lootable.upgrade.NativeNbt.hasType;
import static cyano.lootable.upgrade.NativeNbt.readUuid;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Reads both native UUID encodings without inventing a saved entity identity. */
final class CorpseIdentity {
    private CorpseIdentity() { }
    static UUID read(CompoundTag data) {
        UUID current=null,legacy=null;
        if(data.contains("UUID")) {
            if(!hasType(data, "UUID", 11) || data.getIntArray("UUID").orElseGet(() -> new int[0]).length!=4)
                throw new IllegalArgumentException("Malformed corpse UUID array");
            current=readUuid(data, "UUID");
        }
        if(data.contains("UUIDMost") || data.contains("UUIDLeast")) {
            if(!hasType(data, "UUIDMost", 4) || !hasType(data, "UUIDLeast", 4))
                throw new IllegalArgumentException("Malformed legacy corpse UUID");
            legacy=new UUID(data.getLongOr("UUIDMost", 0L),data.getLongOr("UUIDLeast", 0L));
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
