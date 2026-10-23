package cyano.lootable.contracts;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.*;

class CorpseTranslationTest {
    @TestFactory Stream<DynamicTest> everyLocaleTranslatesTheRegisteredEntityName() throws Exception {
        Path folder = Paths.get("build/resources/main/assets/lootablebodies/lang");
        try (Stream<Path> files = Files.list(folder)) {
            Path[] locales = files.filter(p -> p.toString().endsWith(".json")).sorted().toArray(Path[]::new);
            assertEquals(14, locales.length);
            return Stream.of(locales).map(locale -> DynamicTest.dynamicTest(locale.getFileName().toString(), () -> {
                com.google.gson.JsonObject language;
                try (Reader reader = Files.newBufferedReader(locale, StandardCharsets.UTF_8)) { language = new com.google.gson.JsonParser().parse(reader).getAsJsonObject(); }
                String original = language.get("entity.lootablebodies.Corpse.name").getAsString();
                assertNotNull(original, "Retain the original translation");
                assertEquals(original, language.get("entity.lootablebodies.corpse.name").getAsString());
                // EntityList.getEntityString returns the registered display name
                // Corpse; the namespaced registry/save ID is a separate value.
                assertEquals(original, language.get("entity.lootablebodies.corpse").getAsString(),
                        "Nameless corpses and their spawn eggs must resolve the actual runtime key");
            }));
        }
    }
}
