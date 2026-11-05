package cyano.lootable.upgrade;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryBuilder;

/** Reads the retired vanilla registry while vanilla converts saved villagers. */
public final class LegacyProfessionRegistry {
    private LegacyProfessionRegistry() { }

    public static void create(NewRegistryEvent event) {
        RegistryBuilder<Entry> builder = new RegistryBuilder<Entry>()
                .setName(new ResourceLocation("minecraft:villagerprofessions"))
                .setIDRange(0, 32767)
                .disableSaving().disableSync();
        // These are the six actual 1.10-1.13 vanilla entries, in original ID order.
        // Unknown mod professions remain missing and retain Forge's normal gate.
        event.create(builder, registry -> {
            for (String name : new String[]{"farmer", "librarian", "priest", "smith", "butcher", "nitwit"})
                registry.register(new ResourceLocation("minecraft", name),new Entry());
        });
    }

    public static final class Entry { }
}
