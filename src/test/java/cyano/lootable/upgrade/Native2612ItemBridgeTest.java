package cyano.lootable.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import cyano.lootable.buildtest.NativeVanillaDataFixer;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class Native2612ItemBridgeTest {
    @Test void released12111StacksSurviveTheExactNativeItemBoundary() {
        CompoundTag source = new CompoundTag();
        source.putInt("DataVersion",4671);
        CompoundTag plain = new CompoundTag();
        plain.putString("id","minecraft:diamond");
        plain.putInt("count",3);
        plain.putByte("Slot",(byte)47);
        CompoundTag sword = new CompoundTag();
        sword.putString("id","minecraft:diamond_sword");
        sword.putInt("count",1);
        sword.putByte("Slot",(byte)4);
        CompoundTag components = new CompoundTag();
        components.putInt("minecraft:damage",7);
        components.putString("minecraft:custom_name","BridgeBlade");
        CompoundTag enchantments = new CompoundTag();
        enchantments.putInt("minecraft:sharpness",2);
        components.put("minecraft:enchantments",enchantments);
        sword.put("components",components);
        UpgradePreservation.register(NativeVanillaDataFixer.get());
        for (CompoundTag stack : java.util.List.of(plain,sword)) {
            CompoundTag original = stack.copy();
            assertEquals(original,ItemMigration.convert(stack,source));
            assertEquals(original,stack);
        }
        sword.putString("UnrecognizedData","preserve for recovery");
        assertNull(ItemMigration.convert(sword,source));
    }
}
