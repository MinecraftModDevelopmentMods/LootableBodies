package cyano.lootable.upgrade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native114RegistryRenameTest {
    @Test void itemRegistryBridgesAgreeWithVanillasActualItemConversion() {
        for (String[] names : new String[][]{{"sign","oak_sign"},{"rose_red","red_dye"},
                {"cactus_green","green_dye"},{"dandelion_yellow","yellow_dye"}}) {
            Identifier old = Identifier.fromNamespaceAndPath("minecraft", names[0]);
            Identifier current = Identifier.fromNamespaceAndPath("minecraft", names[1]);
            assertEquals(current, LegacyRegistryMappings.itemReplacement(old));
            CompoundTag input = new CompoundTag(); input.putString("id", old.toString()); input.putByte("Count", (byte)11);
            CompoundTag converted = NativeNbt.update(DataFixers.getDataFixer(), References.ITEM_STACK, input, 1631, 1976);
            assertEquals(current.toString(), converted.getStringOr("id", "")); assertEquals(11, converted.getByteOr("Count", (byte) 0));
        }
    }
    @Test void onlyTheTwoRetiredVanillaSignBlocksAreBridged() {
        assertEquals(Identifier.parse("minecraft:oak_sign"), LegacyRegistryMappings.blockReplacement(Identifier.parse("minecraft:sign")));
        assertEquals(Identifier.parse("minecraft:oak_wall_sign"), LegacyRegistryMappings.blockReplacement(Identifier.parse("minecraft:wall_sign")));
        for (String name : new String[]{"othermod:sign", "minecraft:unknown", "minecraft:oak_sign"}) {
            assertNull(LegacyRegistryMappings.itemReplacement(Identifier.parse(name)));
            assertNull(LegacyRegistryMappings.blockReplacement(Identifier.parse(name)));
        }
    }
}
