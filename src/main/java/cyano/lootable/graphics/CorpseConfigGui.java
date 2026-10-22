package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.config.DummyConfigElement.DummyCategoryElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class CorpseConfigGui extends GuiConfig {
    public CorpseConfigGui(GuiScreen parent) {
        super(parent, elements(), LootableBodies.MODID, false, false, LootableBodies.NAME,
                I18n.format("lootablebodies.config.local_file"));
    }

    private static List<IConfigElement> elements() {
        List<IConfigElement> appearance = new ArrayList<>();
        appearance.add(option("options", "local_texture"));
        appearance.add(option("options", "display_nametag"));
        List<IConfigElement> rules = new ArrayList<>();
        for (String key : Arrays.asList("corpse_HP", "add_bones_to_corpse", "item_decay_rate"))
            rules.add(option("options", key));
        for (String key : Arrays.asList("hurt_by_environment", "hurt_by_weapons", "hurt_by_misc"))
            rules.add(option("corpse damage", key));
        for (String key : Arrays.asList("enable_corpse_decay", "empty_only_decay", "corpse_decay_time"))
            rules.add(option("corpse decay", key));
        return Arrays.<IConfigElement>asList(
                new DummyCategoryElement("appearance", "lootablebodies.config.appearance", appearance),
                new DummyCategoryElement("rules", "lootablebodies.config.rules", rules));
    }

    private static IConfigElement option(String category, String key) {
        Property property = LootableBodies.getConfiguration().getCategory(category).get(key);
        if (key.equals("corpse_decay_time")) property.setConfigEntryClass(DecayTimeEntry.class);
        return new ConfigElement(property);
    }

    /** Only constrain new GUI input; existing file values keep their startup semantics. */
    public static boolean isValidDecayTime(String value) {
        if (!value.matches("\\s*[0-9]+\\s*:\\s*[0-9]+\\s*(?::\\s*[0-9]+\\s*)?")) return false;
        String[] parts = value.trim().split(":");
        long total = 0;
        int[] multiplier = {3600, 60, 1};
        long limit = Integer.MAX_VALUE / 20;
        try {
            for (int i = 0; i < parts.length; i++) {
                long part = Long.parseLong(parts[i].trim());
                if (part > limit / multiplier[i]) return false;
                total += part * multiplier[i];
            }
            return total <= limit;
        } catch (NumberFormatException invalid) { return false; }
    }

    public static final class DecayTimeEntry extends GuiConfigEntries.StringEntry {
        public DecayTimeEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) {
            super(screen, entries, element);
            isValidValue = isValidDecayTime(textFieldValue.getText());
        }
        @Override public void keyTyped(char character, int key) {
            super.keyTyped(character, key);
            isValidValue = isValidDecayTime(textFieldValue.getText());
        }
        @Override public boolean saveConfigElement() {
            // Keep the previous value on invalid input instead of Forge's default reset.
            return isValidDecayTime(textFieldValue.getText()) && super.saveConfigElement();
        }
    }
}
