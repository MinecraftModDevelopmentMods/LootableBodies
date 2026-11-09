package cyano.lootable.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = LegacyStructureDataHandler.class, remap = false)
public interface LegacyStructuresMixin {
    @Invoker("updateFromLegacy")
    CompoundTag lootablebodies$updateFromLegacy(CompoundTag chunk);
}
