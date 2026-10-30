package cyano.lootable.buildtest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screens.ConnectScreen;

/** Uses ordinary Forge connection setup; retries only a runner-confirmed pre-login encoding failure. */
final class FixtureConnections {
    private static Thread retiringServer;
    private FixtureConnections() {}
    static void disconnect(Minecraft mc) {
        if(mc.getSingleplayerServer()!=null)retiringServer=mc.getSingleplayerServer().getRunningThread();
        if(mc.player!=null)mc.player.closeContainer();
        if(mc.level!=null)mc.level.disconnect();
        mc.clearLevel();mc.setScreen(new TitleScreen());
    }
    static boolean stopped(Minecraft mc) {
        return ServerStopGate.ready(mc.level==null,mc.hasSingleplayerServer(),retiringServer);
    }
    static boolean ready(String role) {
        return "A".equals(role)||new File(System.getProperty("lootablebodies.multiplayer.sync"),"client-A-joined").isFile();
    }
    static void connect(Minecraft mc) {
        String address="127.0.0.1:"+Integer.parseInt(System.getProperty("lootablebodies.multiplayer.port"));
        ServerData destination=new ServerData("Disposable check",address,false);
        ConnectScreen.startConnecting(new TitleScreen(),mc,net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),destination);
    }
    static boolean retry(Minecraft mc,String role) throws Exception {
        if(!(mc.screen instanceof DisconnectedScreen)||mc.level!=null)return false;
        File sync=new File(System.getProperty("lootablebodies.multiplayer.sync"));
        File permit=new File(sync,"login-retry-"+role),used=new File(sync,"login-retried-"+role);
        if(!permit.isFile())return false;
        int requested=read(permit),previous=used.isFile()?read(used):0;
        if(requested<=previous)return false;
        RuntimeChecks.require(requested==previous+1&&requested<=3,"Invalid fixture login retry permit");
        Files.write(used.toPath(),Integer.toString(requested).getBytes(StandardCharsets.UTF_8));
        System.out.println("FIXTURE_FORGE_LOGIN_RETRY role="+role+" attempt="+requested);
        mc.setScreen(new TitleScreen());return true;
    }
    private static int read(File file) throws Exception {
        return Integer.parseInt(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim());
    }
}
