package cyano.lootable.buildtest;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native37 loads mods as distinct Java modules: split packages are fatal. */
class Native117ProbeIsolationTest {
    private Set<String> packages(String archive) throws Exception {
        Set<String> result=new TreeSet<>();
        try(ZipFile zip=new ZipFile(archive)) {
            for(java.util.Enumeration<? extends java.util.zip.ZipEntry> entries=zip.entries();entries.hasMoreElements();) {
                java.util.zip.ZipEntry entry=entries.nextElement();String path=entry.getName();
                if(!path.endsWith(".class"))continue;
                byte[] code=zip.getInputStream(entry).readAllBytes();
                assertEquals(60,((code[6]&255)<<8)|(code[7]&255),path);
                result.add(path.substring(0,path.lastIndexOf('/')));
            }
        }
        assertFalse(result.isEmpty(),archive);return result;
    }
    @Test void integrationModuleHasNoProductionPackageDefinitions() throws Exception {
        Set<String> main=packages("build/libs-dev/LootableBodies-2.4.8.117011-deobf.jar");
        Set<String> probe=packages("build/integration-test-mod/lootablebodies-build-tests.jar");
        probe.retainAll(main);assertTrue(probe.isEmpty(),"Native37 module split packages: "+probe);
    }
    @Test void independentControlHasNoModOrIntegrationPackages() throws Exception {
        Set<String> control=packages("build/control-test-mod/forge-platform-control.jar");
        assertTrue(control.stream().allMatch(name->name.equals("buildcontrol")),control.toString());
    }
}
