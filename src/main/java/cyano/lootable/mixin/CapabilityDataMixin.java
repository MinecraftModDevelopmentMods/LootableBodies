package cyano.lootable.mixin;

import cyano.lootable.upgrade.UpgradePreservation;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SavedDataStorage.class, remap = false)
abstract class CapabilityDataMixin {
    @Inject(method = "readTagFromDisk", at = @At("RETURN"), cancellable = true)
    private void preserveOldCapabilities(Path path, DataFixTypes fixer, int version,
            CallbackInfoReturnable<CompoundTag> callback) {
        if (fixer == null && path.endsWith(Path.of("forge", "capabilities.dat")))
            callback.setReturnValue(UpgradePreservation.upgradeLevelCapabilities(callback.getReturnValue()));
    }
}
