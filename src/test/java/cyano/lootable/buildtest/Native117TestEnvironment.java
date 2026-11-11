package cyano.lootable.buildtest;

import java.util.List;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.FMLLoader;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Boots native codecs in plain JUnit without discovering or loading any mods. */
public final class Native117TestEnvironment implements LauncherSessionListener {
    private static boolean initialized;

    @Override public void launcherSessionOpened(LauncherSession session) {
        initialize();
    }

    public static synchronized void initialize() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if(!initialized) {
            try {
                var stateType=Class.forName("net.minecraftforge.fml.loading.ModSorter$State");
                var stateConstructor=stateType.getDeclaredConstructor(List.class,List.class);
                stateConstructor.setAccessible(true);
                var loadingType=Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl");
                var init=loadingType.getDeclaredMethod("init",stateType,
                        net.minecraftforge.fml.loading.EarlyLoadingException.class);
                init.setAccessible(true);
                init.invoke(null,stateConstructor.newInstance(List.of(),List.of()),null);
                var layerField=FMLLoader.class.getDeclaredField("moduleLayerManager");
                layerField.setAccessible(true);
                layerField.set(null,(cpw.mods.modlauncher.api.IModuleLayerManager)
                        layer->java.util.Optional.of(ModuleLayer.boot()));
                ModList.init();
                var setLoaded=ModList.class.getDeclaredMethod("setLoadedMods",List.class);
                setLoaded.setAccessible(true);
                setLoaded.invoke(null,List.of());
                var valid=ModLoader.class.getDeclaredField("loadingStateValid");
                valid.setAccessible(true);
                valid.setBoolean(null,true);
                net.minecraft.server.Bootstrap.bootStrap();
                net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                        .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                        .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
                initialized=true;
            } catch(ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot initialize the empty Forge unit-test context",failure);
            }
        }
    }
}
