package cyano.lootable.mixin;

import com.mojang.serialization.Dynamic;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.filefix.access.CompressedNbt;
import net.minecraft.util.filefix.access.LevelDat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = LevelDat.class, remap = false)
abstract class FileFixLevelDataMixin extends CompressedNbt {
    protected FileFixLevelDataMixin(Path path, MissingSeverity severity) {
        super(path, severity);
    }

    @ModifyArg(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/filefix/access/LevelDat;writeFile(Lcom/mojang/serialization/Dynamic;)V"))
    private Dynamic<Tag> retainRootFields(Dynamic<?> updated) throws IOException {
        Dynamic<Tag> converted = updated.convert(NbtOps.INSTANCE);
        Dynamic<Tag> root = readFile().orElseThrow(() -> new IOException("Missing level data during native file upgrade"));
        return root.set("Data", converted.get("Data").result().orElseThrow());
    }
}
