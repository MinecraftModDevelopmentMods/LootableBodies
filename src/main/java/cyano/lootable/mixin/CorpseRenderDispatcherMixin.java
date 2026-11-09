package cyano.lootable.mixin;

import cyano.lootable.LootableBodies;
import cyano.lootable.graphics.CorpseRenderer;
import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityRenderDispatcher.class, remap = false)
abstract class CorpseRenderDispatcherMixin {
    @Shadow public Map<EntityType<?>, EntityRenderer<?, ?>> renderers;

    @Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"), cancellable = true)
    private void useCorpseRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> result) {
        // The corpse uses the avatar model and equipment layers. Vanilla's
        // submission pass otherwise sends its state to the player renderer.
        if (state instanceof AvatarRenderState && state.entityType == LootableBodies.CORPSE) {
            if (!(renderers.get(state.entityType) instanceof CorpseRenderer renderer)) {
                throw new IllegalStateException("Missing Lootable Bodies corpse renderer");
            }
            result.setReturnValue(renderer);
        }
    }
}
