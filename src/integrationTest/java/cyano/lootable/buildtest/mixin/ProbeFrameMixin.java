package cyano.lootable.buildtest.mixin;

import cyano.lootable.buildtest.ProbeFrames;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, remap = false)
abstract class ProbeFrameMixin {
    @Unique private Camera lootableBodies$observedCamera;
    @Unique private Frustum lootableBodies$observedFrustum;

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void captureNativeCull(Camera camera, Frustum frustum, DeltaTracker delta,
                                  LevelRenderState state, CallbackInfo callback) {
        lootableBodies$observedCamera = camera;
        lootableBodies$observedFrustum = frustum;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void observeCompletedFrame(CallbackInfo callback) {
        if (lootableBodies$observedFrustum != null) {
            ProbeFrames.afterRender(lootableBodies$observedCamera, lootableBodies$observedFrustum,
                    ((LevelRenderer)(Object)this).getTicks());
        }
        lootableBodies$observedCamera = null;
        lootableBodies$observedFrustum = null;
    }
}
