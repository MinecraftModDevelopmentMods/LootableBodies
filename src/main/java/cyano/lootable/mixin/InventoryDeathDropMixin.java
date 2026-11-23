package cyano.lootable.mixin;

import cyano.lootable.events.DeathDropCapture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Inventory.class, remap = false)
abstract class InventoryDeathDropMixin {
    @Shadow @Final public Player player;

    @Redirect(method = "dropAll()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"), require = 1)
    private boolean captureDeathInventoryDrops(Level level, Entity item) {
        return DeathDropCapture.addOrCapture(player, level, item);
    }
}
