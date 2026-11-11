package cyano.lootable.upgrade;

import static cyano.lootable.buildtest.TestNativeNbt.typedList;
import static cyano.lootable.buildtest.TestNativeNbt.readUuid;
import static cyano.lootable.buildtest.TestNativeNbt.writeUuid;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native114UpgradeTest {
    @AfterEach void close() { UpgradePreservation.close(); }
    private CompoundTag context(int version) {
        CompoundTag context = new CompoundTag(); context.putInt("DataVersion", version); return context;
    }
    private CompoundTag item(int slot) {
        CompoundTag item = new CompoundTag(); item.putString("id", "minecraft:sign");
        item.putByte("Count", (byte) 11); item.putByte("Slot", (byte) slot);
        CompoundTag tag = new CompoundTag(); tag.putString("keepsake", "original-113");
        CompoundTag nested = new CompoundTag(); nested.putIntArray("typed", new int[]{7, 8, 9});
        tag.put("nested", nested); item.put("tag", tag); return item;
    }
    private CompoundTag body(int marker) {
        CompoundTag body = new CompoundTag(); body.putString("id", "lootablebodies:corpse");
        writeUuid(body, "UUID", UUID.fromString("00000000-1144-4144-8144-000000000001"));
        body.putInt(UpgradePreservation.VERSION, marker);
        ListTag equipment = new ListTag(); for (int i=0; i<48; i++) equipment.add(item(i));
        body.put("Equipment", equipment); ListTag aux = new ListTag(); aux.add(item(47)); body.put("Aux", aux);
        return body;
    }
    @Test void currentFormatNamesThe119VanillaSchema() {
        assertEquals(4790, UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(14,UpgradePreservation.DATA_VERSION);
    }
    @Test void released113EquipmentAndOverflowAreRetainedBeforeNativeConversion() {
        CompoundTag original = body(3), before = original.copy();
        CompoundTag staged = NativeSafeFixer.stageCorpse(original, context(1631));
        assertEquals(14,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(readUuid(original, "UUID"), readUuid(staged, "UUID"));
        assertTrue(typedList(staged, "Equipment", 10).isEmpty());
        ListTag records = typedList(staged, "LootableBodiesUnresolved", 10); assertEquals(49, records.size());
        for (int i=0; i<48; i++) {
            CompoundTag record = records.getCompoundOrEmpty(i);
            assertEquals("Equipment", record.getStringOr("Source", ""));
            assertEquals(typedList(original, "Equipment", 10).get(i), record.get("Data"));
            assertEquals(1631, record.getCompoundOrEmpty("SourceVersions").getIntOr("DataVersion", 0));
        }
        assertEquals("Aux", records.getCompoundOrEmpty(48).getStringOr("Source", "")); assertEquals(before, original);
    }
    @Test void olderRecoveryPayloadsKeepTheirOriginalSourceAndData() {
        CompoundTag original = body(3), record = new CompoundTag();
        record.putString("Source", "Equipment"); record.put("Data", item(47));
        record.put("SourceVersions", context(922)); ListTag recovery = new ListTag(); recovery.add(record.copy());
        original.put("LootableBodiesUnresolved", recovery);
        CompoundTag staged = NativeSafeFixer.stageCorpse(original, context(1631));
        assertEquals(14,staged.getIntOr(UpgradePreservation.VERSION, 0));
        assertEquals(record, typedList(staged, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(49));
        assertEquals(record, typedList(original, "LootableBodiesUnresolved", 10).getCompoundOrEmpty(0));
    }
    @Test void currentRecordsAreNotWrappedOrDuplicatedAgain() {
        CompoundTag original = body(UpgradePreservation.DATA_VERSION);
        assertEquals(original, NativeSafeFixer.stageCorpse(original, context(1976)));
    }
    @Test void vanilla114ItemRenamesRunForReleased113InventoryCopies() {
        UpgradePreservation.register(DataFixers.getDataFixer());
        CompoundTag original = item(47), before = original.copy();
        CompoundTag fixed = ItemMigration.convertNbt(original, context(1631));
        assertNotNull(fixed); assertEquals("minecraft:oak_sign", fixed.getStringOr("id", ""));
        assertEquals(11, fixed.getByteOr("Count", (byte) 0)); assertEquals(47, fixed.getByteOr("Slot", (byte) 0));
        assertEquals(original.get("tag"), fixed.get("tag")); assertEquals(before, original);
    }
}
