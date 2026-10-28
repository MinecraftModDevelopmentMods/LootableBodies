package cyano.lootable.upgrade;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.NBTDynamicOps;

/** Native data-fixer entry point for explicit vanilla type references. */
public final class NativeNbt {
    private NativeNbt() { }
    public static CompoundNBT update(DataFixer fixer, DSL.TypeReference type,
                                     CompoundNBT input, int source, int target) {
        return (CompoundNBT) fixer.update(type, new Dynamic<>(NBTDynamicOps.INSTANCE, input), source, target).getValue();
    }
}
