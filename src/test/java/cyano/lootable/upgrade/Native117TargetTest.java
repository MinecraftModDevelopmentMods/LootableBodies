package cyano.lootable.upgrade;

import java.io.DataInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117TargetTest {
    @Test void targetMatchesTheActualNativeWorldVersion() {
        net.minecraft.SharedConstants.tryDetectVersion();
        assertEquals(2730,net.minecraft.SharedConstants.getCurrentVersion().getWorldVersion());
        assertEquals(2730,UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(7,UpgradePreservation.DATA_VERSION);
    }
    @Test void released16MarkerIsMigratedWithAllSlotsAndOriginalsRetained() {
        assertEquals(2586,cyano.lootable.buildtest.HistoricalVersions.dataVersion("1.16.5"));
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,6);
        ListTag items=new ListTag();
        for(int slot=0;slot<48;slot++) {
            CompoundTag item=new CompoundTag();item.putByte("Slot",(byte)slot);
            item.putString("id","minecraft:stone");item.putByte("Count",(byte)(slot+1));items.add(item);
        }
        body.put("Equipment",items);CompoundTag before=body.copy();
        CompoundTag source=new CompoundTag();source.putInt("DataVersion",2586);
        CompoundTag staged=NativeSafeFixer.stageCorpse(body,source);
        assertEquals(7,staged.getInt(UpgradePreservation.VERSION));
        assertEquals(48,staged.getList("LootableBodiesUnresolved",10).size());
        for(int slot=0;slot<48;slot++) {
            CompoundTag record=staged.getList("LootableBodiesUnresolved",10).getCompound(slot);
            assertEquals(items.getCompound(slot),record.getCompound("Data"));
            assertEquals("Equipment",record.getString("Source"));
            assertEquals(2586,record.getCompound("SourceVersions").getInt("DataVersion"));
        }
        assertEquals(before,body);
    }
    @Test void productionClassesUseJava16() throws Exception {
        try(DataInputStream bytes=new DataInputStream(getClass().getResourceAsStream("/cyano/lootable/entities/EntityLootableBody.class"))) {
            assertEquals(0xCAFEBABE,bytes.readInt());bytes.readUnsignedShort();assertEquals(60,bytes.readUnsignedShort());
        }
    }
    @Test void marker6OverridesADamagedOuterEntityStamp() {
        CompoundTag body=new CompoundTag();body.putString("id","lootablebodies:corpse");
        body.putInt(UpgradePreservation.VERSION,6);
        com.mojang.datafixers.DataFixer identity=new com.mojang.datafixers.DataFixer() {
            public com.mojang.datafixers.schemas.Schema getSchema(int version){throw new AssertionError("No schema needed for this pass-through control");}
            public <T> com.mojang.serialization.Dynamic<T> update(com.mojang.datafixers.DSL.TypeReference type,com.mojang.serialization.Dynamic<T> value,int from,int to){return value;}
        };
        CompoundTag staged=NativeNbt.update(UpgradePreservation.wrapper(identity),
                net.minecraft.util.datafix.fixes.References.ENTITY_TREE,body,512,2730);
        assertEquals(2586,staged.getCompound(UpgradePreservation.SOURCES).getInt("DataVersion"));
        assertEquals(6,body.getInt(UpgradePreservation.VERSION));
    }
}
