package cyano.lootable.mixin;

import cyano.lootable.upgrade.UpgradePreservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DimensionDataStorage.class, remap = false)
abstract class CapabilityDataMixin {
    @Inject(method = "readTagFromDisk", at = @At("RETURN"), cancellable = true)
    private void preserveOldCapabilities(String id, DataFixTypes fixer, int version,
            CallbackInfoReturnable<CompoundTag> callback) {
        if (fixer == null && id.equals("capabilities"))
            callback.setReturnValue(UpgradePreservation.upgradeLevelCapabilities(callback.getReturnValue()));
    }
}
