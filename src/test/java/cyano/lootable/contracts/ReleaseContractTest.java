package cyano.lootable.contracts;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseContractTest {
    private Properties properties() throws Exception {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(Paths.get("gradle.properties"))) {
            values.load(input);
        }
        return values;
    }

    private String text(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test void releaseVersionEncodesTheActualMinecraftAndLoader() throws Exception {
        Properties values = properties();
        String version = values.getProperty("mod_version");
        assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"), version);
        assertEquals("2.4.7.116051", version);
        assertEquals("forge", values.getProperty("loader_name"));
        assertEquals("1", values.getProperty("loader_code"));
        assertTrue(text("src/main/java/cyano/lootable/LootableBodies.java")
                .contains("VERSION = \"" + version + "\""));
    }

    @Test void mavenIdentityIsIndependentOfSavedEntityIdentity() throws Exception {
        Properties values = properties();
        assertEquals("zone.moddev.mc.lootablebodies", values.getProperty("mod_group"));
        assertEquals("LootableBodies", values.getProperty("mod_artifact"));
        assertEquals("lootablebodies", values.getProperty("mod_id"));
        assertEquals("240968", values.getProperty("curseforge_project_id"));
        String source = text("src/main/java/cyano/lootable/LootableBodies.java");
        assertTrue(source.startsWith("package cyano.lootable;"));
        assertTrue(source.contains("new ResourceLocation(\"lootablebodies:corpse\")"));
        assertTrue(text("build/resources/main/META-INF/mods.toml").contains("versionRange=\"[1.16.5]\""));
    }

    @Test void maintainedReleaseHasLgplAndRetainsTheOriginalNotice() throws Exception {
        assertTrue(text("LICENSE").contains("GNU LESSER GENERAL PUBLIC LICENSE"));
        assertTrue(text("LICENSE").contains("Version 2.1, February 1999"));
        assertTrue(text("LICENSE-CC0").startsWith("CC0 1.0 Universal"));
        assertTrue(text("NOTICE").contains("Cyanobacterium"));
        assertEquals("LGPL-2.1-only", properties().getProperty("mod_license"));
    }

    @Test void pomDescribesThisModWithoutBundlingRuntimeOrTestingDependencies() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document pom = factory.newDocumentBuilder().parse("build/publications/mavenJava/pom-default.xml");
        Properties values = properties();
        assertEquals(values.getProperty("mod_group"), pom.getElementsByTagName("groupId").item(0).getTextContent());
        assertEquals(values.getProperty("mod_artifact"), pom.getElementsByTagName("artifactId").item(0).getTextContent());
        assertEquals(values.getProperty("mod_version"), pom.getElementsByTagName("version").item(0).getTextContent());
        assertEquals(values.getProperty("mod_license_name"), ((Element) pom.getElementsByTagName("license").item(0))
                .getElementsByTagName("name").item(0).getTextContent());
        assertEquals(0, pom.getElementsByTagName("dependency").getLength());
        assertTrue(text("build/publications/mavenJava/pom-default.xml").contains("Cyanobacterium"));
    }

    @Test void productionMetadataLinksToTheMaintainedProject() throws Exception {
        Properties values = properties();
        assertEquals("https://github.com/MinecraftModDevelopmentMods/LootableBodies", values.getProperty("mod_url"));
        assertTrue(text("build/resources/main/META-INF/mods.toml").contains(values.getProperty("mod_url")));
        assertTrue(text("README.md").contains("LootableBodies-" + values.getProperty("mod_version") + ".jar"));
    }

    @Test void adoptedModCreditsTheOriginalAuthorAndMaintainer() throws Exception {
        assertEquals("Cyanobacterium,SkyBlade1978", properties().getProperty("mod_authors"));
        String metadata = text("build/resources/main/META-INF/mods.toml");
        assertTrue(metadata.contains("authors=\"Cyanobacterium, SkyBlade1978\""));
        assertTrue(text("build/publications/mavenJava/pom-default.xml").contains("<id>SkyBlade1978</id>"));
        assertTrue(text("README.md").contains("SkyBlade1978"));
    }
}
