package cyano.lootable.buildtest;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** The native component codec used by the private runtime fixtures. */
final class FixtureStackData {
    private FixtureStackData() { }

    static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    static CompoundTag save(ItemStack stack) {
        return (CompoundTag)stack.save(registries());
    }

    static ItemStack load(CompoundTag raw) {
        var ops=registries().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        return ItemStack.CODEC.parse(ops,raw).result().orElse(ItemStack.EMPTY);
    }

    static CompoundTag custom(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
    }

    static boolean hasCustom(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_DATA);
    }

    static void setCustom(ItemStack stack,CompoundTag data) {
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
}
