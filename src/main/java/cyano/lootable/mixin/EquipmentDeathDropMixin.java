package cyano.lootable.mixin;

import cyano.lootable.events.DeathDropCapture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = EntityEquipment.class, remap = false)
abstract class EquipmentDeathDropMixin {
    @Redirect(method = "dropAll(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private boolean captureDeathEquipmentDrops(Level level, Entity item, LivingEntity owner) {
        return DeathDropCapture.addOrCapture(owner, level, item);
    }
}
