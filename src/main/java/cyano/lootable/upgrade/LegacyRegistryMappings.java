package cyano.lootable.upgrade;

import cyano.lootable.LootableBodies;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** The vanilla XP pickup sound was renamed after Minecraft 1.10. */
@Mod.EventBusSubscriber(modid=LootableBodies.MODID)
public final class LegacyRegistryMappings {
    private static final ResourceLocation OLD_XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.touch");
    private static final ResourceLocation XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.pickup");
    private LegacyRegistryMappings() { }

    @SubscribeEvent public static void sounds(RegistryEvent.MissingMappings<SoundEvent> event) {
        for(RegistryEvent.MissingMappings.Mapping<SoundEvent> mapping:event.getAllMappings())
            if(mapping.key.equals(OLD_XP_SOUND)) mapping.remap(event.getRegistry().getValue(XP_SOUND));
    }
}
