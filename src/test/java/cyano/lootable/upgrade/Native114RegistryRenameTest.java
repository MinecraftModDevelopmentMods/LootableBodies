package cyano.lootable.upgrade;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.DataFixesManager;
import net.minecraft.util.datafix.TypeReferences;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native114RegistryRenameTest {
    @Test void itemRegistryBridgesAgreeWithVanillasActualItemConversion() {
        for (String[] names : new String[][]{{"sign","oak_sign"},{"rose_red","red_dye"},
                {"cactus_green","green_dye"},{"dandelion_yellow","yellow_dye"}}) {
            ResourceLocation old = new ResourceLocation("minecraft", names[0]);
            ResourceLocation current = new ResourceLocation("minecraft", names[1]);
            assertEquals(current, LegacyRegistryMappings.itemReplacement(old));
            CompoundNBT input = new CompoundNBT(); input.putString("id", old.toString()); input.putByte("Count", (byte)11);
            CompoundNBT converted = NativeNbt.update(DataFixesManager.getDataFixer(), TypeReferences.ITEM_STACK, input, 1631, 1976);
            assertEquals(current.toString(), converted.getString("id")); assertEquals(11, converted.getByte("Count"));
        }
    }
    @Test void onlyTheTwoRetiredVanillaSignBlocksAreBridged() {
        assertEquals(new ResourceLocation("minecraft:oak_sign"), LegacyRegistryMappings.blockReplacement(new ResourceLocation("minecraft:sign")));
        assertEquals(new ResourceLocation("minecraft:oak_wall_sign"), LegacyRegistryMappings.blockReplacement(new ResourceLocation("minecraft:wall_sign")));
        for (String name : new String[]{"othermod:sign", "minecraft:unknown", "minecraft:oak_sign"}) {
            assertNull(LegacyRegistryMappings.itemReplacement(new ResourceLocation(name)));
            assertNull(LegacyRegistryMappings.blockReplacement(new ResourceLocation(name)));
        }
    }
}
