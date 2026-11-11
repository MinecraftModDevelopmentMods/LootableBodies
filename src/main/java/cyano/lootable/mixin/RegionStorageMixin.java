package cyano.lootable.mixin;

import com.mojang.datafixers.DataFixer;
import cyano.lootable.upgrade.UpgradePreservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
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

    @Inject(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"), cancellable = true)
    private void preserveRegion(CompoundTag input, int fallback, CompoundTag context, int target,
            CallbackInfoReturnable<CompoundTag> callback) {
        if (dataFixType != DataFixTypes.CHUNK && dataFixType != DataFixTypes.ENTITY_CHUNK) return;
        // Native 26.1.2 migrates legacy structures in the file fixer. It may
        // request an intermediate target before it applies its own changes.
        callback.setReturnValue(UpgradePreservation.upgradeRegion(fixerUpper, dataFixType, input,
                fallback, context, target, null));
    }
}
