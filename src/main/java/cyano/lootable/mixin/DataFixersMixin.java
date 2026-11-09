package cyano.lootable.mixin;

import com.mojang.datafixers.DataFixer;
import cyano.lootable.upgrade.UpgradePreservation;
import net.minecraft.util.datafix.DataFixers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DataFixers.class, remap = false)
abstract class DataFixersMixin {
    @Inject(method = "getDataFixer", at = @At("RETURN"), cancellable = true)
    private static void wrapNativeFixer(CallbackInfoReturnable<DataFixer> callback) {
        callback.setReturnValue(UpgradePreservation.wrapper(callback.getReturnValue()));
    }
}
