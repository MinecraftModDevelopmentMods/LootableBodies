package cyano.lootable.mixin;

import com.mojang.serialization.Dynamic;
import cyano.lootable.upgrade.EarlyWorldPreservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LevelStorageSource.LevelStorageAccess.class, remap = false)
abstract class SaveAccessMixin implements EarlyWorldPreservation.Attached {
    @Inject(method = "getDataTag(Z)Lcom/mojang/serialization/Dynamic;", at = @At("HEAD"))
    private void preserveBeforeRead(boolean fallback, CallbackInfoReturnable<Dynamic<?>> callback) {
        EarlyWorldPreservation.beforeRead((LevelStorageSource.LevelStorageAccess) (Object) this, fallback);
    }

    @ModifyArg(method = "saveDataTag(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/common/ForgeHooks;writeAdditionalLevelSaveData(Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/nbt/CompoundTag;)V"),
            index = 1)
    private CompoundTag preserveBeforeWrite(CompoundTag root) {
        EarlyWorldPreservation.beforeWrite((LevelStorageSource.LevelStorageAccess) (Object) this, root);
        return root;
    }
}
