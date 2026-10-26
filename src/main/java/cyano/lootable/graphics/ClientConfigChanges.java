package cyano.lootable.graphics;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.api.distmarker.*;
@OnlyIn(Dist.CLIENT)
public final class ClientConfigChanges {
    private ClientConfigChanges() { }
    public static void apply() {
        boolean wasLocal=LootableBodies.useLocalSkin;LootableBodies.saveClientConfiguration();
        Minecraft mc=Minecraft.getInstance();
        if(wasLocal && !LootableBodies.useLocalSkin && mc.world!=null)
            for(Entity entity:mc.world.getAllEntities())if(entity instanceof EntityLootableBody)((EntityLootableBody)entity).refreshClientSkin();
    }
}
