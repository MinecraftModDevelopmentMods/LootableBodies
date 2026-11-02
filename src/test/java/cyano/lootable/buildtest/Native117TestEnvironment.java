package cyano.lootable.buildtest;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Native registries must be ready before any test-class static initializer. */
public final class Native117TestEnvironment implements LauncherSessionListener {
    @Override public void launcherSessionOpened(LauncherSession session) {
        net.minecraft.SharedConstants.tryDetectVersion();
        // Plain JUnit has no ModLauncher-generated event constructors. Seed
        // the real EventBus list through its supported instance API before
        // native Bootstrap registers the Forge handshake channel listener.
        new net.minecraftforge.network.NetworkEvent(() -> null).getListenerList();
        new net.minecraftforge.network.NetworkEvent.GatherLoginPayloadsEvent(new java.util.ArrayList<>(),false).getListenerList();
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
