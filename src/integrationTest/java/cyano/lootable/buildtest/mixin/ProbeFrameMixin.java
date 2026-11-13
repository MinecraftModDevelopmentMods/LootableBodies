package cyano.lootable.buildtest.mixin;

import cyano.lootable.buildtest.ProbeFrames;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelExtractor.class, remap = false)
abstract class ProbeFrameMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void captureNativeCull(Camera camera, Frustum frustum, DeltaTracker delta,
                                  LevelRenderState state, CallbackInfo callback) {
        ProbeFrames.capture(camera,frustum,state.gameTime);
    }
}
