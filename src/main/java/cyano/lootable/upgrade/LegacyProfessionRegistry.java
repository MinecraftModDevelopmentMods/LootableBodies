package cyano.lootable.upgrade;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.registries.ForgeRegistryEntry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryBuilder;

/** Reads the retired vanilla registry while vanilla converts saved villagers. */
public final class LegacyProfessionRegistry {
    private LegacyProfessionRegistry() { }

    public static void create(RegistryEvent.NewRegistry event) {
        IForgeRegistry<Entry> registry = new RegistryBuilder<Entry>()
                .setName(new ResourceLocation("minecraft:villagerprofessions"))
                .setType(Entry.class).setIDRange(0, 32767)
                .disableSaving().disableSync().create();
        // These are the six actual 1.10-1.13 vanilla entries, in original ID order.
        // Unknown mod professions remain missing and retain Forge's normal gate.
        for (String name : new String[]{"farmer", "librarian", "priest", "smith", "butcher", "nitwit"})
            registry.register(new Entry().setRegistryName(new ResourceLocation("minecraft", name)));
    }

    public static final class Entry extends ForgeRegistryEntry<Entry> { }
}
