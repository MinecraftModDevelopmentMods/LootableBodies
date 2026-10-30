package cyano.lootable.graphics;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.*;
@OnlyIn(Dist.CLIENT)
public final class ClientConfigChanges {
    private ClientConfigChanges() { }
    public static void apply() {
        boolean wasLocal=LootableBodies.useLocalSkin;LootableBodies.saveClientConfiguration();
        Minecraft mc=Minecraft.getInstance();
        if(wasLocal && !LootableBodies.useLocalSkin && mc.level!=null)
            for(Entity entity:mc.level.entitiesForRendering())if(entity instanceof EntityLootableBody)((EntityLootableBody)entity).refreshClientSkin();
    }
}
