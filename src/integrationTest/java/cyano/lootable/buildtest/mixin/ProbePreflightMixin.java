package cyano.lootable.buildtest.mixin;

import com.mojang.datafixers.DataFixer;
import cyano.lootable.buildtest.NativePreflightChecks;
import cyano.lootable.upgrade.UpgradePreservation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = UpgradePreservation.class, remap = false)
abstract class ProbePreflightMixin {
    @ModifyArg(method = "prepareEarly(Ljava/nio/file/Path;Ljava/nio/file/Path;)Lcyano/lootable/upgrade/UpgradeJournal;",
            at = @At(value = "INVOKE",
                    target = "Lcyano/lootable/upgrade/UpgradePreservation;prepareEarly(Ljava/nio/file/Path;Ljava/nio/file/Path;Lcom/mojang/datafixers/DataFixer;)Lcyano/lootable/upgrade/UpgradeJournal;"),
            index = 2)
    private static DataFixer observePreflight(DataFixer fixer) {
        return NativePreflightChecks.observe(fixer);
    }
}
