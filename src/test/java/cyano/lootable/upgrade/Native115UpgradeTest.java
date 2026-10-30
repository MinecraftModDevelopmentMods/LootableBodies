package cyano.lootable.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native115UpgradeTest {
    @AfterEach void close() { UpgradePreservation.close(); }

    private CompoundTag chunk(int[] biomes) {
        CompoundTag root = new CompoundTag(), level = new CompoundTag();
        root.putInt("DataVersion", 1976);
        level.putInt("xPos", 0); level.putInt("zPos", 0); level.putString("Status", "full");
        level.put("Sections", new ListTag()); level.put("Entities", new ListTag());
        level.put("TileEntities", new ListTag()); level.putIntArray("Biomes", biomes);
        root.put("Level", level); return root;
    }
    private CompoundTag convert(CompoundTag root, int source) {
        return NbtUtils.update(UpgradePreservation.wrapper(DataFixers.getDataFixer()),
                DataFixTypes.CHUNK, root, source, 2586);
    }
    private int[] currentBiomes() {
        int[] values = new int[1024];
        for (int i = 0; i < values.length; i++) values[i] = i;
        return values;
    }
    @Test void currentFormatUsesThe117SchemaAndItsOwnMarker() {
        assertEquals(2730, UpgradePreservation.MINECRAFT_VERSION);
        assertEquals(7, UpgradePreservation.DATA_VERSION);
    }
    @Test void currentBiomeValuesSurvivePlausibleOlderStampsAndRepeatedConversion() {
        int[] values = currentBiomes(); CompoundTag original = chunk(values), before = original.copy();
        CompoundTag first = convert(original, 1976);
        assertArrayEquals(values, first.getCompound("Level").getIntArray("Biomes"));
        assertEquals(before, original);
        CompoundTag restamped = first.copy(); restamped.putInt("DataVersion", 1976);
        assertArrayEquals(values, convert(restamped, 1976).getCompound("Level").getIntArray("Biomes"));
    }
    @Test void currentBiomeValuesSurviveMissingAndWronglyTypedStamps() {
        for (Tag stamp : new Tag[]{null, StringTag.valueOf("damaged"), IntTag.valueOf(-1)}) {
            int[] values = currentBiomes(); CompoundTag original = chunk(values);
            if (stamp == null) original.remove("DataVersion"); else original.put("DataVersion", stamp);
            assertArrayEquals(values, convert(original, 1631).getCompound("Level").getIntArray("Biomes"));
        }
    }
    @Test void biomeProtectionStillRunsOlderVanillaEntityFixes() {
        int[] values = currentBiomes(); CompoundTag original = chunk(values);
        original.putInt("DataVersion", 1631);
        CompoundTag villager = new CompoundTag(); villager.putString("id", "minecraft:villager");
        villager.putInt("Profession", 1); villager.putInt("Career", 1); villager.putInt("CareerLevel", 3);
        original.getCompound("Level").getList("Entities", 10).add(villager);
        CompoundTag before = original.copy(), fixed = convert(original, 1631);
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
        CompoundTag raw = new CompoundTag(), item = new CompoundTag(), context = new CompoundTag();
        raw.putString("id", "lootablebodies:corpse"); raw.putInt(UpgradePreservation.VERSION, 4);
        item.putString("id", "minecraft:oak_sign"); item.putByte("Count", (byte) 9);
        item.putByte("Slot", (byte) 47); item.putIntArray("CustomTyped", new int[]{8, 5, 3});
        ListTag items = new ListTag(); items.add(item); raw.put("Equipment", items);
        context.putInt("DataVersion", 1976); CompoundTag before = raw.copy();
        CompoundTag staged = NativeSafeFixer.stageCorpse(raw, context);
        assertEquals(7, staged.getInt(UpgradePreservation.VERSION));
        CompoundTag record = staged.getList("LootableBodiesUnresolved", 10).getCompound(0);
        assertEquals(item, record.get("Data")); assertEquals("Equipment", record.getString("Source"));
        assertEquals(1976, record.getCompound("SourceVersions").getInt("DataVersion"));
        assertEquals(before, raw);
    }
    @Test void released114MarkerWinsOverAnOlderOuterEntityContext() {
        CompoundTag raw = new CompoundTag(), item = new CompoundTag();
        raw.putString("id", "lootablebodies:corpse"); raw.putInt(UpgradePreservation.VERSION, 4);
        item.putString("id", "minecraft:oak_sign"); item.putByte("Count", (byte) 7);
        item.putByte("Slot", (byte) 47); ListTag equipment = new ListTag(); equipment.add(item);
        raw.put("Equipment", equipment); CompoundTag before = raw.copy();
        CompoundTag fixed = NativeNbt.update(UpgradePreservation.wrapper(DataFixers.getDataFixer()),
                net.minecraft.util.datafix.fixes.References.ENTITY_TREE, raw, 1631, 2586);
        assertEquals(1976, fixed.getList("LootableBodiesUnresolved", 10).getCompound(0)
                .getCompound("SourceVersions").getInt("DataVersion"));
        assertEquals(before, raw);
    }
}
