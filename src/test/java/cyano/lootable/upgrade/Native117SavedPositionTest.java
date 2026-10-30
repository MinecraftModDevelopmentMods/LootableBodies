package cyano.lootable.upgrade;

import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.fixes.References;
import cyano.lootable.buildtest.NativeVanillaDataFixer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cyano.lootable.upgrade.Native117RegionFixtures.*;

/** Saved containing coordinates must reach the conversion before construction. */
class Native117SavedPositionTest {
    private static final UUID ID=new UUID(117,171);
    private static final String RECOVERY="LootableBodiesInvalidFields";
    private ListTag pos(double x,double y,double z) {
        ListTag list=new ListTag();for(double v:new double[]{x,y,z})list.add(DoubleTag.valueOf(v));return list;
    }
    private CompoundTag fixed(CompoundTag raw,boolean separate) {
        return NativeNbt.update(UpgradePreservation.wrapper(NativeVanillaDataFixer.get()),
                separate?References.ENTITY_CHUNK:References.CHUNK,raw,2730,2730);
    }
    private CompoundTag root(CompoundTag body,boolean separate,int x,int z) {
        CompoundTag root=chunk(2730,body);
        if(separate)root.putIntArray("Position",new int[]{x,z});
        else {
            CompoundTag level=new CompoundTag();level.putInt("xPos",x);level.putInt("zPos",z);level.put("Entities",root.get("Entities"));
            root.remove("Entities");root.remove("Position");root.put("Level",level);
        }
        return root;
    }
    private CompoundTag first(CompoundTag root,boolean separate){return (separate?root:root.getCompound("Level")).getList("Entities",10).getCompound(0);}
    @Test void malformedAxisUsesContainingRegionBeforeEitherNativeLoaderConstructsAnEntity() {
        for(boolean separate:new boolean[]{false,true}) {
            CompoundTag body=body(ID,7);body.put("Pos",pos(Double.NaN,80,160.5));
            CompoundTag raw=root(body,separate,-11,10),before=raw.copy();CompoundTag converted=fixed(raw,separate),actual=first(converted,separate);
            assertEquals(pos(-167.5,80,160.5),actual.get("Pos"));assertEquals(before,raw);
            CompoundTag record=actual.getList(RECOVERY,10).getCompound(0);assertEquals("Pos",record.getString("Source"));assertEquals(body.get("Pos"),record.get("Data"));
            for(String key:new String[]{"UUID","Name","Equipment","Aux","LootableBodiesUnresolved"})assertEquals(body.get(key),actual.get(key),key);
            assertEquals(converted,fixed(converted,separate),"Conversion accumulated records or moved an already repaired body");
        }
    }
    @Test void finiteMismatchedRootAndMissingPositionCannotBeIndexedInTheWrongSection() {
        CompoundTag body=body(ID,7);body.put("Pos",pos(160.5,80,160.5));
        assertEquals(pos(-167.5,80,160.5),first(fixed(root(body,true,-11,10),true),true).get("Pos"));
        body.remove("Pos");CompoundTag actual=first(fixed(root(body,true,-11,10),true),true);
        assertEquals(pos(-167.5,64,168.5),actual.get("Pos"));assertFalse(actual.contains(RECOVERY),"Missing data invented a raw record");
    }
    @Test void passengersKeepValidCrossChunkPositionsAndUnknownRecoveryRecords() {
        CompoundTag rider=body(new UUID(117,172),7);rider.put("Pos",pos(17,80,8));
        CompoundTag body=body(ID,7);body.put("Pos",pos(8,80,8));ListTag riders=new ListTag();riders.add(rider);body.put("Passengers",riders);
        assertEquals(body,first(fixed(root(body,true,0,0),true),true));
    }
    @Test void rawRecoveryTagSurvivesPositionRepairExactlyOnce() {
        CompoundTag body=body(ID,7);body.putString("Pos","opaque position");body.putString(RECOVERY,"future raw recovery");
        CompoundTag result=fixed(root(body,true,0,0),true),actual=first(result,true);ListTag records=actual.getList(RECOVERY,10);
        assertEquals(2,records.size());assertEquals("Recovery",records.getCompound(0).getString("Source"));
        assertEquals(StringTag.valueOf("future raw recovery"),records.getCompound(0).get("Data"));
        assertEquals("Pos",records.getCompound(1).getString("Source"));assertEquals(StringTag.valueOf("opaque position"),records.getCompound(1).get("Data"));
        assertEquals(result,fixed(result,true));
    }
    @Test void impossibleContainingCoordinatesRefuseBeforeNativeConstruction() {
        CompoundTag raw=root(body(ID,7),true,Integer.MAX_VALUE,0),before=raw.copy();
        assertThrows(IllegalStateException.class,()->fixed(raw,true));assertEquals(before,raw);
    }
    @Test void verticalValuesOutsideTheNativeLoadLimitRetainRawDataBeforeClamping() {
        for(boolean separate:new boolean[]{false,true})for(double y:new double[]{-20000000.01,20000000.01,-3e7,3e7}) {
            CompoundTag body=body(ID,7);body.put("Pos",pos(8.5,y,8.5));CompoundTag raw=root(body,separate,0,0),before=raw.copy();
            CompoundTag actual=first(fixed(raw,separate),separate);
            assertEquals(pos(8.5,64,8.5),actual.get("Pos"));assertEquals(before,raw);
            assertEquals(body.get("Pos"),actual.getList(RECOVERY,10).getCompound(0).get("Data"));
            for(String key:new String[]{"UUID","Equipment","Aux","LootableBodiesUnresolved"})assertEquals(body.get(key),actual.get(key));
        }
    }
    @Test void nativeHorizontalAndVerticalBoundaryValuesRemainExact() {
        for(boolean separate:new boolean[]{false,true})for(double y:new double[]{-2e7,2e7}) {
            CompoundTag body=body(ID,7);body.put("Pos",pos(3e7,y,3e7));
            assertEquals(body,first(fixed(root(body,separate,1875000,1875000),separate),separate));
        }
    }
}
