package cyano.lootable.upgrade;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Native12111LegacyChestTest {
    @Test
    void oldChestAndNestedItemNamesUseTheNativeTextFix() throws Exception {
        CompoundTag source = TagParser.parseCompoundFully("""
                {DataVersion:1456,Level:{xPos:0,zPos:0,Sections:[],Entities:[],TileEntities:[
                  {id:"minecraft:chest",x:1,y:64,z:2,CustomName:"Old storage",
                   Items:[{id:"minecraft:pig_spawn_egg",Slot:3b,Count:7b,
                           tag:{EntityTag:{id:"minecraft:pig",CustomName:"Saved pig"},display:{Name:"Old keepsake"},opaque:[B;0b,-1b,17b]}}]}]}}
                """);
        CompoundTag before = source.copy();
        CompoundTag chest = source.getCompoundOrEmpty("Level").getListOrEmpty("TileEntities").getCompoundOrEmpty(0);
        CompoundTag item = chest.getListOrEmpty("Items").getCompoundOrEmpty(0);
        var vanilla = NativeVanillaDataFixer.get();
        CompoundTag fixedItem = NativeNbt.update(vanilla, References.ITEM_STACK, item.copy(), 1456, 1458);
        CompoundTag noItems = chest.copy();
        noItems.remove("Items");
        CompoundTag expected = NativeNbt.update(vanilla, References.BLOCK_ENTITY, noItems, 1456, 1458);
        var items = new net.minecraft.nbt.ListTag();
        items.add(fixedItem);
        expected.put("Items", items);
        CompoundTag fixed = NativeNbt.update(new NativeSafeFixer(vanilla, null), References.CHUNK, source, 1456, 1458);
        assertEquals(before, source);
        assertEquals(expected, fixed.getCompoundOrEmpty("Level").getListOrEmpty("TileEntities").getCompoundOrEmpty(0));
    }
}
