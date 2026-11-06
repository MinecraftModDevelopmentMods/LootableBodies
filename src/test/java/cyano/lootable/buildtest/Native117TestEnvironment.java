package cyano.lootable.buildtest;

import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Boots native codecs in plain JUnit without discovering or loading any mods. */
public final class Native117TestEnvironment implements LauncherSessionListener {
    @Override public void launcherSessionOpened(LauncherSession session) {
        initialize();
    }

    public static synchronized void initialize() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if(FMLLoader.getLoadingModList()==null) {
            try {
                LoadingModList loading=LoadingModList.of(List.of(),List.of(),null);
                loading.setBrokenFiles(List.of());
                var loadingField=FMLLoader.class.getDeclaredField("loadingModList");
                loadingField.setAccessible(true);
                loadingField.set(null,loading);
                var layerField=FMLLoader.class.getDeclaredField("moduleLayerManager");
                layerField.setAccessible(true);
                layerField.set(null,(cpw.mods.modlauncher.api.IModuleLayerManager)
                        layer->java.util.Optional.of(ModuleLayer.boot()));
                ModList mods=ModList.of(List.of(),List.of());
                var setLoaded=ModList.class.getDeclaredMethod("setLoadedMods",List.class);
                setLoaded.setAccessible(true);
                setLoaded.invoke(mods,List.of());
                var valid=ModLoader.class.getDeclaredField("loadingStateValid");
                valid.setAccessible(true);
                valid.setBoolean(ModLoader.get(),true);
            } catch(ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot initialize the empty Forge unit-test context",failure);
            }
        }
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
