package cyano.lootable.buildtest;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Native registries must be ready before any test-class static initializer. */
public final class Native117TestEnvironment implements LauncherSessionListener {
    @Override public void launcherSessionOpened(LauncherSession session) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
