package cyano.lootable.buildtest.mixin;

import cyano.lootable.buildtest.ProbeFrames;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=GameRenderer.class,remap=false)
abstract class ProbeRenderFrameMixin {
    @Inject(method="renderLevel",at=@At("RETURN"))
    private void observeCompletedFrame(CallbackInfo callback) {
        ProbeFrames.complete();
    }
}
