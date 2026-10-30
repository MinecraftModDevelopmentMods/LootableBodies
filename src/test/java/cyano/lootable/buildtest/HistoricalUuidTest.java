package cyano.lootable.buildtest;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HistoricalUuidTest {
    private final UUID id=UUID.fromString("13572468-abcd-4f01-9234-56789abcdef0");
    private CompoundTag legacy(String name){CompoundTag n=new CompoundTag();n.putLong(name+"Most",id.getMostSignificantBits());n.putLong(name+"Least",id.getLeastSignificantBits());return n;}
    @Test void readsUnmodifiedHistoricalEntityAndPlayerPairs(){for(String key:new String[]{"UUID","Player"}){CompoundTag n=legacy(key),before=n.copy();assertEquals(id,HistoricalUuid.read(n,key));assertEquals(before,n);}}
    @Test void readsNativeArraysWithoutChangingTheirBytes(){CompoundTag n=new CompoundTag();n.putUUID("UUID",id);int[] before=n.getIntArray("UUID").clone();assertEquals(id,HistoricalUuid.read(n,"UUID"));assertArrayEquals(before,n.getIntArray("UUID"));}
    @Test void malformedAndPartialSourceIdentitiesCannotBeGuessed(){
        CompoundTag n=legacy("UUID");n.remove("UUIDLeast");assertThrows(IllegalArgumentException.class,()->HistoricalUuid.read(n,"UUID"));
        n.putString("UUIDLeast","opaque");assertThrows(IllegalArgumentException.class,()->HistoricalUuid.read(n,"UUID"));
        CompoundTag array=new CompoundTag();array.putIntArray("UUID",new int[]{1,2,3});assertThrows(IllegalArgumentException.class,()->HistoricalUuid.read(array,"UUID"));
    }
    @Test void conflictingLegacyAndCurrentSourcesAreRejected(){CompoundTag n=legacy("UUID");n.putUUID("UUID",new UUID(0,1));assertThrows(IllegalArgumentException.class,()->HistoricalUuid.read(n,"UUID"));}
    @Test void zeroAndSignedBitsArePreserved(){for(UUID value:new UUID[]{new UUID(0,0),new UUID(Long.MIN_VALUE,Long.MAX_VALUE)}){CompoundTag n=new CompoundTag();n.putUUID("UUID",value);assertEquals(value,HistoricalUuid.read(n,"UUID"));}}
}
