package cyano.lootable.buildtest;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.nio.file.*;
import java.util.*;
import net.minecraftforge.common.ForgeConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Execute the pinned native37 spec; config corrections precede mod world hooks. */
class Native117ForgeConfigTest {
    private ForgeConfigSpec spec() throws Exception {
        java.lang.reflect.Field field=ForgeConfig.class.getDeclaredField("serverSpec");
        field.setAccessible(true);return (ForgeConfigSpec)field.get(null);
    }
    private CommentedConfig old() throws Exception {
        return new TomlParser().parse(Files.readString(Paths.get("tools/fixtures/forge36-server-default.toml")));
    }
    private Set<String> keys(CommentedConfig config) {
        return ((CommentedConfig)config.get("server")).valueMap().keySet();
    }
    @Test void actualNativeSpecDropsOnlyItsRetiredKeysAndAddsBlockEntityDefault() throws Exception {
        CommentedConfig config=old();Set<String> previous=new HashSet<>(keys(config));
        spec().correct(config);
        Set<String> removed=new HashSet<>(previous);removed.removeAll(keys(config));
        assertEquals(new HashSet<>(Arrays.asList("removeErroringEntities","removeErroringTileEntities",
            "logCascadingWorldGeneration","fixVanillaCascading","dimensionUnloadQueueDelay")),removed);
        assertEquals(new HashSet<>(Arrays.asList("removeErroringBlockEntities","fullBoundingBoxLadders",
            "zombieBaseSummonChance","zombieBabyChance","treatEmptyTagsAsAir","fixAdvancementLoading")),keys(config));
        assertEquals(false,config.get("server.removeErroringBlockEntities"));
        assertEquals(0,spec().correct(config),"Native correction must be stable");
    }
    @Test void actualNativeSpecRetainsAllSupportedNondefaultChoices() throws Exception {
        CommentedConfig config=old();config.set("server.fullBoundingBoxLadders",true);
        config.set("server.zombieBaseSummonChance",0.37D);config.set("server.zombieBabyChance",0.29D);
        config.set("server.treatEmptyTagsAsAir",true);config.set("server.fixAdvancementLoading",false);
        config.set("server.removeErroringBlockEntities",true);
        spec().correct(config);
        assertEquals(true,config.get("server.fullBoundingBoxLadders"));assertEquals(0.37D,(Double)config.get("server.zombieBaseSummonChance"));
        assertEquals(0.29D,(Double)config.get("server.zombieBabyChance"));assertEquals(true,config.get("server.treatEmptyTagsAsAir"));
        assertEquals(false,config.get("server.fixAdvancementLoading"));assertEquals(true,config.get("server.removeErroringBlockEntities"));
    }
    @Test void actualNativeDefaultMatchesRetainedPackagedOutput() throws Exception {
        CommentedConfig config=CommentedConfig.inMemory();spec().correct(config);
        CommentedConfig expected=new TomlParser().parse(Files.readString(Paths.get("tools/fixtures/forge37-server-default.toml")));
        assertEquals(expected,config);
    }
}
