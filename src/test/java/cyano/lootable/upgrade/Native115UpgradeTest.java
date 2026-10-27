package cyano.lootable.upgrade;

import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.datafix.DefaultTypeReferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native115UpgradeTest {
    @AfterEach void close() { UpgradePreservation.close(); }

    private CompoundNBT chunk(int[] biomes) {
        CompoundNBT root = new CompoundNBT(), level = new CompoundNBT();
        root.putInt("DataVersion", 1976);
        level.putInt("xPos", 0); level.putInt("zPos", 0); level.putString("Status", "full");
        level.put("Sections", new ListNBT()); level.put("Entities", new ListNBT());
        level.put("TileEntities", new ListNBT()); level.putIntArray("Biomes", biomes);
        root.put("Level", level); return root;
    }
    private CompoundNBT convert(CompoundNBT root, int source) {
        return NBTUtil.update(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()),
                DefaultTypeReferences.CHUNK, root, source, 2230);
    }
    private int[] currentBiomes() {
        int[] values = new int[1024];
        for (int i = 0; i < values.length; i++) values[i] = i;
        return values;
    }
    @Test void currentFormatUsesThe115SchemaAndItsOwnMarker() {
        assertEquals(2230, UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(5, UpgradePreservation.DATA_VERSION);
    }
    @Test void currentBiomeValuesSurvivePlausibleOlderStampsAndRepeatedConversion() {
        int[] values = currentBiomes(); CompoundNBT original = chunk(values), before = original.copy();
        CompoundNBT first = convert(original, 1976);
        assertArrayEquals(values, first.getCompound("Level").getIntArray("Biomes"));
        assertEquals(before, original);
        CompoundNBT restamped = first.copy(); restamped.putInt("DataVersion", 1976);
        assertArrayEquals(values, convert(restamped, 1976).getCompound("Level").getIntArray("Biomes"));
    }
    @Test void currentBiomeValuesSurviveMissingAndWronglyTypedStamps() {
        for (INBT stamp : new INBT[]{null, StringNBT.valueOf("damaged"), IntNBT.valueOf(-1)}) {
            int[] values = currentBiomes(); CompoundNBT original = chunk(values);
            if (stamp == null) original.remove("DataVersion"); else original.put("DataVersion", stamp);
            assertArrayEquals(values, convert(original, 1631).getCompound("Level").getIntArray("Biomes"));
        }
    }
    @Test void biomeProtectionStillRunsOlderVanillaEntityFixes() {
        int[] values = currentBiomes(); CompoundNBT original = chunk(values);
        original.putInt("DataVersion", 1631);
        CompoundNBT villager = new CompoundNBT(); villager.putString("id", "minecraft:villager");
        villager.putInt("Profession", 1); villager.putInt("Career", 1); villager.putInt("CareerLevel", 3);
        original.getCompound("Level").getList("Entities", 10).add(villager);
        CompoundNBT before = original.copy(), fixed = convert(original, 1631);
        assertArrayEquals(values, fixed.getCompound("Level").getIntArray("Biomes"));
        assertEquals("minecraft:librarian", fixed.getCompound("Level").getList("Entities", 10)
                .getCompound(0).getCompound("VillagerData").getString("profession"));
        assertEquals(before, original);
    }
    @Test void historical256BiomeValuesStillUseNativeSampling() {
        int[] values = new int[256]; for (int i=0; i<values.length; i++) values[i]=i%17;
        int[] fixed = convert(chunk(values), 1976).getCompound("Level").getIntArray("Biomes");
        assertEquals(1024, fixed.length);
        for (int y=0; y<64; y++) for (int z=0; z<4; z++) for (int x=0; x<4; x++)
            assertEquals(values[(z*4+2)*16+x*4+2], fixed[y*16+z*4+x]);
    }
    @Test void released114MarkerKeepsItsOriginalItemContext() {
        CompoundNBT raw = new CompoundNBT(), item = new CompoundNBT(), context = new CompoundNBT();
        raw.putString("id", "lootablebodies:corpse"); raw.putInt(UpgradePreservation.VERSION, 4);
        item.putString("id", "minecraft:oak_sign"); item.putByte("Count", (byte) 9);
        item.putByte("Slot", (byte) 47); item.putIntArray("CustomTyped", new int[]{8, 5, 3});
        ListNBT items = new ListNBT(); items.add(item); raw.put("Equipment", items);
        context.putInt("DataVersion", 1976); CompoundNBT before = raw.copy();
        CompoundNBT staged = NativeSafeFixer.stageCorpse(raw, context);
        assertEquals(5, staged.getInt(UpgradePreservation.VERSION));
        CompoundNBT record = staged.getList("LootableBodiesUnresolved", 10).getCompound(0);
        assertEquals(item, record.get("Data")); assertEquals("Equipment", record.getString("Source"));
        assertEquals(1976, record.getCompound("SourceVersions").getInt("DataVersion"));
        assertEquals(before, raw);
    }
    @Test void released114MarkerWinsOverAnOlderOuterEntityContext() {
        CompoundNBT raw = new CompoundNBT(), item = new CompoundNBT();
        raw.putString("id", "lootablebodies:corpse"); raw.putInt(UpgradePreservation.VERSION, 4);
        item.putString("id", "minecraft:oak_sign"); item.putByte("Count", (byte) 7);
        item.putByte("Slot", (byte) 47); ListNBT equipment = new ListNBT(); equipment.add(item);
        raw.put("Equipment", equipment); CompoundNBT before = raw.copy();
        CompoundNBT fixed = NativeNbt.update(UpgradePreservation.wrapper(DataFixesManager.getDataFixer()),
                net.minecraft.util.datafix.TypeReferences.ENTITY_TYPE, raw, 1631, 2230);
        assertEquals(1976, fixed.getList("LootableBodiesUnresolved", 10).getCompound(0)
                .getCompound("SourceVersions").getInt("DataVersion"));
        assertEquals(before, raw);
    }
}
