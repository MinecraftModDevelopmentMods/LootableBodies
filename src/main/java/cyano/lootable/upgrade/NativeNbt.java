package cyano.lootable.upgrade;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Native data-fixer entry point for explicit vanilla type references. */
public final class NativeNbt {
    // Keep the actual tags available for validation and raw recovery. Typed
    // field readers alone cannot distinguish a missing field from a bad type.
    private static final MapCodec<CompoundTag> RAW_TAGS = new MapCodec<>() {
        @Override public <T> DataResult<CompoundTag> decode(DynamicOps<T> ops, MapLike<T> input) {
            return CompoundTag.CODEC.parse(ops, ops.createMap(input.entries()));
        }

        @Override public <T> RecordBuilder<T> encode(CompoundTag value, DynamicOps<T> ops, RecordBuilder<T> output) {
            for (String key : value.keySet()) {
                output.add(key, NbtOps.INSTANCE.convertTo(ops, value.get(key)));
            }
            return output;
        }

        @Override public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.empty();
        }
    };

    public static CompoundTag rawTags(ValueInput input) {
        return input.read(RAW_TAGS).orElseThrow(() -> new IllegalStateException("Missing or invalid corpse data"));
    }

    public static void writeRawTags(ValueOutput output, CompoundTag tags) {
        output.store(RAW_TAGS, tags);
    }

    private NativeNbt() { }
    public static CompoundTag update(DataFixer fixer, DSL.TypeReference type,
                                     CompoundTag input, int source, int target) {
        return (CompoundTag) fixer.update(type, new Dynamic<>(NbtOps.INSTANCE, input), source, target).getValue();
    }

    /** Check the stored tag, without the numeric coercion of native getters. */
    public static boolean hasType(CompoundTag tag, String key, int type) {
        Tag raw = tag.get(key);
        return raw != null && (type == 99 ? raw instanceof NumericTag : raw.getId() == type);
    }

    /** The new native list codec accepts mixed types; these saved lists do not. */
    public static boolean isListOf(Tag value, int type) {
        return value instanceof ListTag list && list.stream().allMatch(entry -> entry.getId() == type);
    }

    public static ListTag typedList(CompoundTag tag, String key, int type) {
        Tag raw = tag.get(key);
        return isListOf(raw, type) ? (ListTag) raw : new ListTag();
    }

    public static boolean hasUuid(CompoundTag tag, String key) {
        return tag.getIntArray(key).filter(value -> value.length == 4).isPresent();
    }

    public static UUID readUuid(CompoundTag tag, String key) {
        return tag.read(key, UUIDUtil.CODEC).orElseThrow(() -> new IllegalArgumentException("Invalid UUID " + key));
    }

    public static void writeUuid(CompoundTag tag, String key, UUID uuid) {
        tag.store(key, UUIDUtil.CODEC, uuid);
    }
}
