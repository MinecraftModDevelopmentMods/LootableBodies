package cyano.lootable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Dynamic;
import cyano.lootable.upgrade.EarlyWorldPreservation;
import java.io.IOException;
import net.minecraft.server.Main;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Main.class, remap = false)
abstract class DedicatedWorldMixin {
    @WrapOperation(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;getDataTag()Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> readWorld(LevelStorageSource.LevelStorageAccess access, Operation<Dynamic<?>> original) throws IOException {
        return EarlyWorldPreservation.readDedicatedWorld(access, () -> original.call(access));
    }

    @WrapOperation(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;getDataTagFallback()Lcom/mojang/serialization/Dynamic;"))
    private static Dynamic<?> readFallback(LevelStorageSource.LevelStorageAccess access, Operation<Dynamic<?>> original) throws IOException {
        return EarlyWorldPreservation.readDedicatedWorld(access, () -> original.call(access));
    }
}
