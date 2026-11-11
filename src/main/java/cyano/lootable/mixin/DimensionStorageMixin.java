package cyano.lootable.mixin;

import com.mojang.datafixers.schemas.Schema;
import java.util.List;
import java.util.Map;
import net.minecraft.util.filefix.FileFix;
import net.minecraft.util.filefix.access.FileRelation;
import net.minecraft.util.filefix.fixes.DimensionStorageFileFix;
import net.minecraft.util.filefix.operations.FileFixOperations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DimensionStorageFileFix.class, remap = false)
abstract class DimensionStorageMixin extends FileFix {
    protected DimensionStorageMixin(Schema schema) {
        super(schema);
    }

    @Inject(method = "makeFixer", at = @At("HEAD"))
    private void moveForgeCapabilities(CallbackInfo callback) {
        addFileFixOperation(FileFixOperations.applyInFolders(FileRelation.DIMENSIONS_DATA,
                List.of(FileFixOperations.move("capabilities.dat", "forge/capabilities.dat"))));
        addFileFixOperation(FileFixOperations.groupMove(Map.of(
                "data", "dimensions/minecraft/overworld/data",
                "DIM-1/data", "dimensions/minecraft/the_nether/data",
                "DIM1/data", "dimensions/minecraft/the_end/data"),
                List.of(FileFixOperations.move("capabilities.dat", "forge/capabilities.dat"))));
    }
}
