package cyano.lootable.mixin;

import com.mojang.serialization.Dynamic;
import cyano.lootable.upgrade.EarlyWorldPreservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelStorageSource.LevelStorageAccess.class, remap = false)
abstract class SaveAccessMixin implements EarlyWorldPreservation.Attached {
    @Inject(method = "getUnfixedDataTagWithFallback", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;restoreLevelDataFromOld()Z"))
    private void preserveBeforeFallbackRestoration(CallbackInfoReturnable<Dynamic<?>> callback) {
        EarlyWorldPreservation.beforeRead((LevelStorageSource.LevelStorageAccess) (Object) this, true);
    }

    @Inject(method = "saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"))
    private void preserveBeforeWrite(CompoundTag root, CallbackInfo callback) {
        EarlyWorldPreservation.beforeWrite((LevelStorageSource.LevelStorageAccess) (Object) this, root);
    }
}
