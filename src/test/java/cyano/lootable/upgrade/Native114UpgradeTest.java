package cyano.lootable.upgrade;

import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native114UpgradeTest {
    @AfterEach void close() { UpgradePreservation.close(); }
    private CompoundNBT context(int version) {
        CompoundNBT context = new CompoundNBT(); context.putInt("DataVersion", version); return context;
    }
    private CompoundNBT item(int slot) {
        CompoundNBT item = new CompoundNBT(); item.putString("id", "minecraft:sign");
        item.putByte("Count", (byte) 11); item.putByte("Slot", (byte) slot);
        CompoundNBT tag = new CompoundNBT(); tag.putString("keepsake", "original-113");
        CompoundNBT nested = new CompoundNBT(); nested.putIntArray("typed", new int[]{7, 8, 9});
        tag.put("nested", nested); item.put("tag", tag); return item;
    }
    private CompoundNBT body(int marker) {
        CompoundNBT body = new CompoundNBT(); body.putString("id", "lootablebodies:corpse");
        body.putUniqueId("UUID", UUID.fromString("00000000-1144-4144-8144-000000000001"));
        body.putInt(UpgradePreservation.VERSION, marker);
        ListNBT equipment = new ListNBT(); for (int i=0; i<48; i++) equipment.add(item(i));
        body.put("Equipment", equipment); ListNBT aux = new ListNBT(); aux.add(item(47)); body.put("Aux", aux);
        return body;
    }
    @Test void currentFormatNamesThe114VanillaSchema() {
        assertEquals(1976, UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(4, UpgradePreservation.DATA_VERSION);
    }
    @Test void released113EquipmentAndOverflowAreRetainedBeforeNativeConversion() {
        CompoundNBT original = body(3), before = original.copy();
        CompoundNBT staged = NativeSafeFixer.stageCorpse(original, context(1631));
        assertEquals(4, staged.getInt(UpgradePreservation.VERSION));
        assertEquals(original.getUniqueId("UUID"), staged.getUniqueId("UUID"));
        assertTrue(staged.getList("Equipment", 10).isEmpty());
        ListNBT records = staged.getList("LootableBodiesUnresolved", 10); assertEquals(49, records.size());
        for (int i=0; i<48; i++) {
            CompoundNBT record = records.getCompound(i);
            assertEquals("Equipment", record.getString("Source"));
            assertEquals(original.getList("Equipment", 10).get(i), record.get("Data"));
            assertEquals(1631, record.getCompound("SourceVersions").getInt("DataVersion"));
        }
        assertEquals("Aux", records.getCompound(48).getString("Source")); assertEquals(before, original);
    }
    @Test void olderRecoveryPayloadsKeepTheirOriginalSourceAndData() {
        CompoundNBT original = body(3), record = new CompoundNBT();
        record.putString("Source", "Equipment"); record.put("Data", item(47));
        record.put("SourceVersions", context(922)); ListNBT recovery = new ListNBT(); recovery.add(record.copy());
        original.put("LootableBodiesUnresolved", recovery);
        CompoundNBT staged = NativeSafeFixer.stageCorpse(original, context(1631));
        assertEquals(4, staged.getInt(UpgradePreservation.VERSION));
        assertEquals(record, staged.getList("LootableBodiesUnresolved", 10).getCompound(49));
        assertEquals(record, original.getList("LootableBodiesUnresolved", 10).getCompound(0));
    }
    @Test void current114RecordsAreNotWrappedOrDuplicatedAgain() {
        CompoundNBT original = body(4);
        assertEquals(original, NativeSafeFixer.stageCorpse(original, context(1976)));
    }
    @Test void vanilla114ItemRenamesRunForReleased113InventoryCopies() {
        UpgradePreservation.register(DataFixesManager.getDataFixer());
        CompoundNBT original = item(47), before = original.copy();
        CompoundNBT fixed = ItemMigration.convertNbt(original, context(1631));
        assertNotNull(fixed); assertEquals("minecraft:oak_sign", fixed.getString("id"));
        assertEquals(11, fixed.getByte("Count")); assertEquals(47, fixed.getByte("Slot"));
        assertEquals(original.get("tag"), fixed.get("tag")); assertEquals(before, original);
    }
}
