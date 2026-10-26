package cyano.lootable.upgrade;

import cyano.lootable.LootableBodies;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** The vanilla XP pickup sound was renamed after Minecraft 1.10. */
@Mod.EventBusSubscriber(modid=LootableBodies.MODID,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class LegacyRegistryMappings {
    public static ResourceLocation itemReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return new ResourceLocation("minecraft:oak_sign");
            case "rose_red": return new ResourceLocation("minecraft:red_dye");
            case "cactus_green": return new ResourceLocation("minecraft:green_dye");
            case "dandelion_yellow": return new ResourceLocation("minecraft:yellow_dye");
            default: return null;
        }
    }
    public static ResourceLocation blockReplacement(ResourceLocation old) {
        if (!old.getNamespace().equals("minecraft")) return null;
        switch (old.getPath()) {
            case "sign": return new ResourceLocation("minecraft:oak_sign");
            case "wall_sign": return new ResourceLocation("minecraft:oak_wall_sign");
            default: return null;
        }
    }
    private static final ResourceLocation OLD_XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.touch");
    private static final ResourceLocation XP_SOUND=new ResourceLocation("minecraft:entity.experience_orb.pickup");
    private LegacyRegistryMappings() { }

    @SubscribeEvent public static void items(RegistryEvent.MissingMappings<net.minecraft.item.Item> event) {
        for (RegistryEvent.MissingMappings.Mapping<net.minecraft.item.Item> mapping : event.getAllMappings()) {
            ResourceLocation replacement = itemReplacement(mapping.key);
            if (replacement != null) mapping.remap(event.getRegistry().getValue(replacement));
        }
    }
    @SubscribeEvent public static void blocks(RegistryEvent.MissingMappings<net.minecraft.block.Block> event) {
        for (RegistryEvent.MissingMappings.Mapping<net.minecraft.block.Block> mapping : event.getAllMappings()) {
            ResourceLocation replacement = blockReplacement(mapping.key);
            if (replacement != null) mapping.remap(event.getRegistry().getValue(replacement));
        }
    }

    @SubscribeEvent public static void sounds(RegistryEvent.MissingMappings<SoundEvent> event) {
        for(RegistryEvent.MissingMappings.Mapping<SoundEvent> mapping:event.getAllMappings())
            if(mapping.key.equals(OLD_XP_SOUND)) mapping.remap(event.getRegistry().getValue(XP_SOUND));
    }
}
