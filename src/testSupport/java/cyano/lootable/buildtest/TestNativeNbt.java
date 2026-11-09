package cyano.lootable.buildtest;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;

/** Native test-side NBT observations, independent of the production converter. */
public final class TestNativeNbt {
    private TestNativeNbt() { }

    public static boolean hasType(CompoundTag data, String name, int type) {
        Tag value = data.get(name);
        if (value == null) return false;
        return type == 99 ? value instanceof NumericTag : value.getId() == type;
    }

    public static ListTag typedList(CompoundTag data, String name, int type) {
        if (!(data.get(name) instanceof ListTag list)) return new ListTag();
        for (Tag entry : list) {
            if (entry.getId() != type) return new ListTag();
        }
        return list;
    }

    public static boolean hasUuid(CompoundTag data, String name) {
        return data.getIntArray(name).filter(array -> array.length == 4).isPresent();
    }

    public static UUID readUuid(CompoundTag data, String name) {
        return data.read(name, UUIDUtil.CODEC).orElseThrow();
    }

    public static void writeUuid(CompoundTag data, String name, UUID uuid) {
        data.store(name, UUIDUtil.CODEC, uuid);
    }

    public static ValueInput input(Entity entity, CompoundTag data) {
        return TagValueInput.create(ProblemReporter.DISCARDING, entity.registryAccess(), data);
    }

    public static CompoundTag save(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.saveAsPassenger(output)) throw new AssertionError("Native entity did not save");
        return output.buildResult();
    }

    public static CompoundTag saveWithoutId(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    public static net.minecraft.network.chat.Component legacyJsonText(String text, HolderLookup.Provider registries) {
        return net.minecraft.network.chat.ComponentSerialization.CODEC.parse(
                registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),
                com.google.gson.JsonParser.parseString(text)).getOrThrow();
    }

    public static CompoundTag saveStack(ItemStack stack, HolderLookup.Provider registries) {
        return (CompoundTag) ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }
}
