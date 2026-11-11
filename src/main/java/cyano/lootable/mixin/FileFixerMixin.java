package cyano.lootable.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import cyano.lootable.upgrade.EarlyWorldPreservation;
import cyano.lootable.upgrade.UpgradePreservation;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.filefix.AbortedFileFixException;
import net.minecraft.util.filefix.FileFixerUpper;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FileFixerUpper.class, remap = false)
abstract class FileFixerMixin implements EarlyWorldPreservation.LayoutAttached {
    @Unique private static final String FIX =
            "fix(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"
            + "Lcom/mojang/serialization/Dynamic;Lnet/minecraft/util/worldupdate/UpgradeProgress;I)"
            + "Lcom/mojang/serialization/Dynamic;";

    @Shadow public abstract boolean requiresFileFixing(int version);

    @Inject(method = FIX, at = @At("HEAD"))
    private void beforeFileChanges(LevelStorageSource.LevelStorageAccess access, Dynamic<?> header,
            UpgradeProgress progress, int target, CallbackInfoReturnable<Dynamic<?>> callback)
            throws AbortedFileFixException {
        EarlyWorldPreservation.beforeFileFix(access, requiresFileFixing(NbtUtils.getDataVersion(header)));
    }

    @Inject(method = FIX, at = @At("RETURN"))
    private void afterFileChanges(LevelStorageSource.LevelStorageAccess access, Dynamic<?> header,
            UpgradeProgress progress, int target, CallbackInfoReturnable<Dynamic<?>> callback)
            throws AbortedFileFixException {
        EarlyWorldPreservation.afterFileFix(access);
    }

    @ModifyExpressionValue(method = {FIX, "writeUpdatedLevelData"}, at = @At(value = "INVOKE",
            target = "Lcom/mojang/datafixers/DataFixerBuilder$Result;fixer()Lcom/mojang/datafixers/DataFixer;"))
    private DataFixer protectNativeLevelConversion(DataFixer fixer) {
        return UpgradePreservation.wrapper(fixer);
    }

    @WrapOperation(method = "writeUpdatedLevelData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"))
    private void retainLevelMetadata(CompoundTag updated, Path path, Operation<Void> original) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.defaultQuota());
        root.put("Data", updated.getCompoundOrEmpty("Data"));
        original.call(root, path);
    }
}
