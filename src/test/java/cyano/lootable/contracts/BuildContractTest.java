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
    @Test void packagedTranslationsRetainTheirUtf8Bytes() throws Exception {
        Properties release = new Properties();
        try (java.io.InputStream input = Files.newInputStream(Paths.get("gradle.properties"))) { release.load(input); }
        String prefix = "LootableBodies-" + release.getProperty("mod_version");
        for (String archive : Arrays.asList("build/libs-dev/" + prefix + "-deobf.jar", "build/libs/" + prefix + "-sources.jar")) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                int locales = 0;
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith("assets/lootablebodies/lang/") || !entry.getName().endsWith(".json")) continue;
                    byte[] bytes;
                    try (java.io.InputStream input = zip.getInputStream(entry); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024]; int count;
                        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                        bytes = out.toByteArray();
                    }
                    StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
                    assertArrayEquals(Files.readAllBytes(Paths.get("build/resources/main", entry.getName())), bytes, archive + ": " + entry.getName());
                    locales++;
                }
                assertEquals(14, locales, archive);
            }
        }
    }
    @Test void originalIdentityAndDeclaredReleaseRemainConsistent() throws Exception {
        String source = text("src/main/java/cyano/lootable/LootableBodies.java");
        assertTrue(source.contains("MODID = \"lootablebodies\""));
        Properties release = new Properties();
        try (java.io.InputStream input = Files.newInputStream(Paths.get("gradle.properties"))) { release.load(input); }
        assertTrue(source.contains("VERSION = \"" + release.getProperty("mod_version") + "\""));
        assertTrue(source.contains("new ResourceLocation(\"lootablebodies:corpse\")"));
        assertTrue(source.contains("new ResourceLocation(\"lootablebodies:corpse\")"));
        assertTrue(text("LICENSE-CC0").startsWith("CC0 1.0 Universal"));
    }
    @Test void processedResourcesHaveNoTemplatesOrLegacyMetadata() throws Exception {
        String info=text("build/resources/main/META-INF/mods.toml");
        assertTrue(info.contains("modId=\"lootablebodies\""));
        Properties release=new Properties();try(java.io.InputStream input=Files.newInputStream(Paths.get("gradle.properties"))){release.load(input);}
        assertTrue(info.contains("version=\""+release.getProperty("mod_version")+"\""));
        assertTrue(info.contains("versionRange=\"[1.13.2]\""));assertTrue(info.contains("Cyanobacterium"));assertFalse(info.contains("${"));
        assertFalse(Files.exists(Paths.get("build/resources/main/mcmod.info")));
        assertEquals(4,new com.google.gson.JsonParser().parse(text("build/resources/main/pack.mcmeta")).getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt());
    }
    @Test void everyOriginalTranslationSurvivesTheNativeJsonFormat() throws Exception {
        try(DirectoryStream<Path> locales=Files.newDirectoryStream(Paths.get("src/test/resources/legacy-translations"),"*.lang")){
            int count=0;for(Path original:locales){
                Properties before=new Properties();try(java.io.Reader reader=Files.newBufferedReader(original,StandardCharsets.UTF_8)){before.load(reader);}
                Path target=Paths.get("build/resources/main/assets/lootablebodies/lang",original.getFileName().toString().replace(".lang",".json"));
                com.google.gson.JsonObject after=new com.google.gson.JsonParser().parse(new String(Files.readAllBytes(target),StandardCharsets.UTF_8)).getAsJsonObject();
                for(String key:before.stringPropertyNames())assertEquals(before.getProperty(key),after.get(key).getAsString(),original+":"+key);count++;
            }assertEquals(14,count);
        }
    }
    @Test void corpseSaveKeysAndSlotOrderRemainStable() throws Exception {
        String source = text("src/main/java/cyano/lootable/entities/EntityLootableBody.java");
        for (String key : Arrays.asList("Equipment", "Aux", "Slot", "Vac", "Name", "DeathTime", "DecayTime"))
            assertTrue(source.contains("\"" + key + "\""), key);
        assertTrue(source.contains("NonNullList.withSize(6*7, ItemStack.EMPTY)"));
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
