package cyano.lootable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Dynamic;
import cyano.lootable.upgrade.EarlyWorldPreservation;
import java.io.IOException;
import net.minecraft.server.Main;
import net.minecraft.util.filefix.AbortedFileFixException;
import net.minecraft.util.filefix.FileFixException;
import net.minecraft.util.filefix.FileFixerUpper;
import net.minecraft.util.worldupdate.UpgradeProgress;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Main.class, remap = false)
abstract class DedicatedWorldMixin {
    @WrapOperation(method = "main", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;getUnfixedDataTagWithFallback()Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> readWorld(LevelStorageSource.LevelStorageAccess access, Operation<Dynamic<?>> original)
            throws IOException {
        return EarlyWorldPreservation.readDedicatedWorld(access, () -> original.call(access));
    }

    @WrapOperation(method = "main", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/filefix/FileFixerUpper;fix(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lcom/mojang/serialization/Dynamic;Lnet/minecraft/util/worldupdate/UpgradeProgress;)Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> fixWorldFiles(FileFixerUpper fixer, LevelStorageSource.LevelStorageAccess access,
            Dynamic<?> header, UpgradeProgress progress, Operation<Dynamic<?>> original) throws FileFixException {
        try {
            return original.call(fixer, access, header, progress);
        } catch (AbortedFileFixException failure) {
            EarlyWorldPreservation.handleDedicatedFileRefusal(access, failure);
            throw failure;
        }
    }
}
