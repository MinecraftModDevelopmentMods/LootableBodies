package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientConfigChanges {
    @SubscribeEvent public void changed(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!LootableBodies.MODID.equals(event.getModID())) return;
        boolean wasLocalSkin = LootableBodies.useLocalSkin;
        LootableBodies.saveClientConfiguration();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (wasLocalSkin && !LootableBodies.useLocalSkin && minecraft.world != null) {
            for (Entity entity : minecraft.world.loadedEntityList)
                if (entity instanceof EntityLootableBody) ((EntityLootableBody) entity).refreshClientSkin();
        }
    }
}
