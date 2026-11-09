package cyano.lootable.mixin;

import com.mojang.datafixers.DataFixer;
import cyano.lootable.upgrade.UpgradePreservation;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.chunk.storage.LegacyTagFixer;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SimpleRegionStorage.class, remap = false)
abstract class RegionStorageMixin implements UpgradePreservation.RegionAttached {
    @Shadow @Final private DataFixer fixerUpper;
    @Shadow @Final private DataFixTypes dataFixType;
    @Shadow @Final private Supplier<LegacyTagFixer> legacyFixer;

    @Inject(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"), cancellable = true)
    private void preserveRegion(CompoundTag input, int fallback, CompoundTag context,
            CallbackInfoReturnable<CompoundTag> callback) {
        if (dataFixType != DataFixTypes.CHUNK && dataFixType != DataFixTypes.ENTITY_CHUNK) return;
        callback.setReturnValue(UpgradePreservation.upgradeRegion(fixerUpper, dataFixType, input,
                fallback, context, this::lootablebodies$applyLegacyStructures));
    }

    private CompoundTag lootablebodies$applyLegacyStructures(CompoundTag converted) {
        LegacyTagFixer legacy = legacyFixer.get();
        // At version 1493 the native entry point initializes its structure
        // index without running its unprotected pre-1493 conversion again.
        CompoundTag initialized = legacy.applyFix(converted);
        return legacy instanceof LegacyStructuresMixin structures
                ? structures.lootablebodies$updateFromLegacy(initialized) : initialized;
    }
}
