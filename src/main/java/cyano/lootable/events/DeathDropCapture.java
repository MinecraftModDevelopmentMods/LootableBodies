package cyano.lootable.events;

import java.util.Collection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Restores the active Forge player death capture at native66 inventory admissions. */
public final class DeathDropCapture {
    private DeathDropCapture() { }

    public static boolean addOrCapture(LivingEntity owner, Level level, Entity entity) {
        if (owner instanceof Player && entity instanceof ItemEntity item) {
            Collection<ItemEntity> captured = owner.captureDrops();
            if (captured != null) {
                captured.add(item);
                return true;
            }
        }
        return level.addFreshEntity(entity);
    }
}
