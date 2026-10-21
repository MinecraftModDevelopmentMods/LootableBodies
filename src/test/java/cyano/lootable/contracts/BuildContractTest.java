package cyano.lootable.contracts;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildContractTest {
    private String text(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
    @Test void originalIdentityAndDeclaredReleaseRemainConsistent() throws Exception {
        String source = text("src/main/java/cyano/lootable/LootableBodies.java");
        assertTrue(source.contains("MODID = \"lootablebodies\""));
        Properties release = new Properties();
        try (java.io.InputStream input = Files.newInputStream(Paths.get("gradle.properties"))) { release.load(input); }
        assertTrue(source.contains("VERSION = \"" + release.getProperty("mod_version") + "\""));
        assertTrue(source.contains("String idName = \"Corpse\""));
        assertTrue(source.contains("EntityRegistry.registerModEntity(entityClass, idName"));
        assertTrue(text("LICENSE-CC0").startsWith("CC0 1.0 Universal"));
    }
    @Test void processedResourcesHaveNoTemplatesOrDuplicateMetadata() throws Exception {
        String info = text("build/resources/main/mcmod.info");
        assertTrue(info.contains("\"modid\": \"lootablebodies\""));
        Properties release = new Properties();
        try (java.io.InputStream input = Files.newInputStream(Paths.get("gradle.properties"))) { release.load(input); }
        assertTrue(info.contains("\"version\": \"" + release.getProperty("mod_version") + "\""));
        assertTrue(info.contains("\"mcversion\": \"1.10.2\""));
        assertTrue(info.contains("Cyanobacterium"));
        assertFalse(info.contains("${"));
        assertFalse(Files.exists(Paths.get("mcmod.info")));
        assertFalse(Files.exists(Paths.get("resources/mcmod.info")));
        assertTrue(text("build/resources/main/pack.mcmeta").contains("\"pack_format\":2"));
    }
    @Test void legacyLocalesRetainEntityTranslation() throws Exception {
        try (DirectoryStream<Path> locales = Files.newDirectoryStream(Paths.get("build/resources/main/assets/lootablebodies/lang"), "*.lang")) {
            int count = 0;
            for (Path locale : locales) {
                assertTrue(locale.getFileName().toString().matches("[a-z]{2}_[A-Z]{2}\\.lang"));
                String value = new String(Files.readAllBytes(locale), StandardCharsets.UTF_8);
                assertTrue(value.startsWith("entity.lootablebodies.Corpse.name="), locale.toString());
                assertFalse(value.contains("\uFFFD"));
                count++;
            }
            assertEquals(14, count);
        }
    }
    @Test void corpseSaveKeysAndSlotOrderRemainStable() throws Exception {
        String source = text("src/main/java/cyano/lootable/entities/EntityLootableBody.java");
        for (String key : Arrays.asList("Equipment", "Aux", "Slot", "Vac", "Name", "DeathTime", "DecayTime"))
            assertTrue(source.contains("\"" + key + "\""), key);
        assertTrue(source.contains("new ItemStack[6*7]"));
        assertTrue(source.contains("EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET, EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND"));
    }
    @Test void configurationNamesAndDefaultsRemainStable() throws Exception {
        String source = text("src/main/java/cyano/lootable/LootableBodies.java");
        for (String key : Arrays.asList("corpse_HP", "local_texture", "display_nametag", "add_bones_to_corpse", "hurt_by_environment", "hurt_by_weapons", "hurt_by_misc", "item_decay_rate", "enable_corpse_decay", "empty_only_decay", "corpse_decay_time"))
            assertTrue(source.contains("\"" + key + "\""), key);
        assertTrue(source.contains("5F, -1F, 1e9F"));
        assertTrue(source.contains("\"1:00:00\""));
        assertTrue(source.contains("addBonesToCorpse = true"));
        assertTrue(source.contains("useLocalSkin = false"));
    }
}
