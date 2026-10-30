package cyano.lootable.upgrade;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/** Native data-fixer entry point for explicit vanilla type references. */
public final class NativeNbt {
    private NativeNbt() { }
    public static CompoundTag update(DataFixer fixer, DSL.TypeReference type,
                                     CompoundTag input, int source, int target) {
        return (CompoundTag) fixer.update(type, new Dynamic<>(NbtOps.INSTANCE, input), source, target).getValue();
    }
}
