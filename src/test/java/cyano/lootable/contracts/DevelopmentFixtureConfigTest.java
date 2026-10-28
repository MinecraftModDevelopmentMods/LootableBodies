package cyano.lootable.contracts;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Forge must not create and correct the same watched file during fixture startup. */
class DevelopmentFixtureConfigTest {
    private void complete(String root) throws Exception {
        String text=new String(Files.readAllBytes(Paths.get(root,"config/fml.toml")),StandardCharsets.UTF_8);
        CommentedConfig config=new TomlParser().parse(text);
        assertEquals(new HashSet<>(Arrays.asList("versionCheck","splashscreen","maxThreads","defaultConfigPath")),config.valueMap().keySet());
        assertEquals(Boolean.FALSE,config.get("versionCheck"));
        assertEquals(Boolean.TRUE,config.get("splashscreen"));
        assertEquals(Integer.valueOf(-1),config.get("maxThreads"));
        assertEquals("defaultconfigs",config.get("defaultConfigPath"));
    }
    @Test void ownedIntegrationHomeHasCompleteNativeSettingsBeforeForgeStarts() throws Exception {
        complete(System.getProperty("lootablebodies.integrationFixtureRoot","build/integration-run"));
    }
    @Test void independentControlHasCompleteNativeSettingsBeforeForgeStarts() throws Exception {
        complete("build/forge-control-run");
    }
}
