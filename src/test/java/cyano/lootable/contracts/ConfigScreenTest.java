package cyano.lootable.contracts;

import cyano.lootable.LootableBodies;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigScreenTest {
    @TempDir Path directory;
    private Object previousHome;
    @BeforeEach void prepareForgeConfig() throws Exception {
        java.lang.reflect.Field home = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); previousHome = home.get(null); home.set(null, directory.toFile());
    }
    @AfterEach void restoreForgeConfig() throws Exception {
        java.lang.reflect.Field home = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, previousHome);
    }

    private Configuration load(String contents) throws Exception {
        Path file = directory.resolve("lootablebodies.cfg");
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        LootableBodies.class.getMethod("loadConfiguration", File.class).invoke(null, file.toFile());
        return (Configuration) LootableBodies.class.getMethod("getConfiguration").invoke(null);
    }

    private void save() throws Exception {
        LootableBodies.class.getMethod("saveClientConfiguration").invoke(null);
    }

    @Test void modsListUsesAClientOnlyFactory() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/cyano/lootable/LootableBodies.java")), StandardCharsets.UTF_8);
        assertTrue(source.contains("guiFactory = \"cyano.lootable.graphics.CorpseConfigGuiFactory\""));
        assertFalse(source.contains("import net.minecraft.client"));
    }

    @Test void existingKeysHaveCorrectRestartPolicyAndDefaults() throws Exception {
        // Other tests deliberately alter the static gameplay fields.
        LootableBodies.corpseHP = 20; LootableBodies.addBonesToCorpse = true;
        LootableBodies.hurtByEnvironment = LootableBodies.hurtByAttacks = LootableBodies.hurtByMisc = false;
        LootableBodies.allowCorpseDecay = LootableBodies.decayOnlyWhenEmpty = true;
        LootableBodies.useLocalSkin = false; LootableBodies.displayNameTag = true;
        Configuration config = load("");
        assertEquals(new TreeSet<>(Arrays.asList("options", "corpse damage", "corpse decay")), config.getCategoryNames());
        int count = 0;
        for (String category : config.getCategoryNames()) for (Property property : config.getCategory(category).values()) {
            boolean appearance = property.getName().equals("local_texture") || property.getName().equals("display_nametag");
            assertEquals(!appearance, property.requiresMcRestart(), property.getName());
            assertEquals(!appearance, property.requiresWorldRestart(), property.getName());
            assertTrue(property.getLanguageKey().startsWith("lootablebodies.config."));
            count++;
        }
        assertEquals(11, count);
        assertEquals(6000, LootableBodies.ticksPerItemDecay);
        assertEquals(72000, LootableBodies.corpseDecayTime);
        assertEquals("1:00:00", config.getCategory("corpse decay").get("corpse_decay_time").getString());
        assertTrue(LootableBodies.completelyInvulnerable);
    }

    @Test void appearanceSaveDoesNotActivatePendingGameplayChanges() throws Exception {
        Configuration config = load("options {\n D:corpse_HP=37\n D:item_decay_rate=7\n B:local_texture=false\n B:display_nametag=true\n}\n"
                + "\"corpse decay\" {\n S:corpse_decay_time=2:03:04\n}\n");
        config.getCategory("options").get("local_texture").set(true);
        config.getCategory("options").get("display_nametag").set(false);
        config.getCategory("options").get("corpse_HP").set(99);
        config.getCategory("options").get("item_decay_rate").set(0);
        config.getCategory("corpse decay").get("corpse_decay_time").set("0:00:10");
        save();
        assertTrue(LootableBodies.useLocalSkin); assertFalse(LootableBodies.displayNameTag);
        assertEquals(37, LootableBodies.corpseHP);
        assertEquals(8400, LootableBodies.ticksPerItemDecay);
        assertEquals((2 * 3600 + 3 * 60 + 4) * 20, LootableBodies.corpseDecayTime);
        Configuration disk = new Configuration(directory.resolve("lootablebodies.cfg").toFile()); disk.load();
        assertEquals(99, disk.getCategory("options").get("corpse_HP").getDouble());
        LootableBodies.class.getMethod("loadConfiguration", File.class).invoke(null, directory.resolve("lootablebodies.cfg").toFile());
        assertEquals(99, LootableBodies.corpseHP);
        assertEquals(0, LootableBodies.ticksPerItemDecay);
        assertEquals(200, LootableBodies.corpseDecayTime);
    }

    @Test void savingPreservesUnknownOptionsAndLegacyCategories() throws Exception {
        Configuration config = load("options {\n S:pack_note=keep this\n}\ncustom {\n I:example=42\n}\n");
        config.getCategory("options").get("local_texture").set(true); save();
        Configuration disk = new Configuration(directory.resolve("lootablebodies.cfg").toFile()); disk.load();
        assertEquals("keep this", disk.getCategory("options").get("pack_note").getString());
        assertEquals(42, disk.getCategory("custom").get("example").getInt());
        assertEquals(3, disk.getCategory("corpse damage").size());
    }

    @Test void timeInputRejectsMalformedAndOverflowingValues() throws Exception {
        java.lang.reflect.Method validate = Class.forName("cyano.lootable.graphics.CorpseConfigGui")
                .getMethod("isValidDecayTime", String.class);
        for (String valid : Arrays.asList("1:00:00", "0:00", " 1 : 2 : 3 ", "0:120", "29826:9:42"))
            assertEquals(true, validate.invoke(null, valid), valid);
        for (String invalid : Arrays.asList("", "garbage", "1", "1:2:3:4", "1:-2", "NaN:00", "1.5:00",
                "999999999999999999999:00", "29826:9:43", "0:0:2147483647"))
            assertEquals(false, validate.invoke(null, invalid), invalid);
    }

    @Test void englishLabelsCoverAllVisibleOptions() throws Exception {
        Configuration config = load("");
        Properties language = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(Paths.get(
                "build/resources/main/assets/lootablebodies/lang/en_us.lang"), StandardCharsets.UTF_8)) { language.load(reader); }
        for (String category : config.getCategoryNames()) for (Property property : config.getCategory(category).values()) {
            assertNotNull(language.getProperty(property.getLanguageKey()), property.getName());
            assertNotNull(language.getProperty(property.getLanguageKey() + ".tooltip"), property.getName());
        }
    }
}
