package cyano.lootable.buildtest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.screen.ConnectingScreen;

/** Uses ordinary Forge connection setup; retries only a runner-confirmed pre-login encoding failure. */
final class FixtureConnections {
    private static Thread retiringServer;
    private FixtureConnections() {}
    static void disconnect(Minecraft mc) {
        if(mc.getIntegratedServer()!=null)retiringServer=mc.getIntegratedServer().getExecutionThread();
        if(mc.player!=null)mc.player.closeScreen();
        if(mc.world!=null)mc.world.sendQuittingDisconnectingPacket();
        mc.func_213254_o();mc.displayGuiScreen(new MainMenuScreen());
    }
    static boolean stopped(Minecraft mc) {
        return ServerStopGate.ready(mc.world==null,mc.isIntegratedServerRunning(),retiringServer);
    }
    static boolean ready(String role) {
        return "A".equals(role)||new File(System.getProperty("lootablebodies.multiplayer.sync"),"client-A-joined").isFile();
    }
    static void connect(Minecraft mc) {
        String address="127.0.0.1:"+Integer.parseInt(System.getProperty("lootablebodies.multiplayer.port"));
        mc.displayGuiScreen(new ConnectingScreen(new MainMenuScreen(),mc,new ServerData("Disposable check",address,false)));
    }
    static boolean retry(Minecraft mc,String role) throws Exception {
        if(!(mc.currentScreen instanceof DisconnectedScreen)||mc.world!=null)return false;
        File sync=new File(System.getProperty("lootablebodies.multiplayer.sync"));
        File permit=new File(sync,"login-retry-"+role),used=new File(sync,"login-retried-"+role);
        if(!permit.isFile())return false;
        int requested=read(permit),previous=used.isFile()?read(used):0;
        if(requested<=previous)return false;
        RuntimeChecks.require(requested==previous+1&&requested<=3,"Invalid fixture login retry permit");
        Files.write(used.toPath(),Integer.toString(requested).getBytes(StandardCharsets.UTF_8));
        System.out.println("FIXTURE_FORGE_LOGIN_RETRY role="+role+" attempt="+requested);
        mc.displayGuiScreen(new MainMenuScreen());return true;
    }
    private static int read(File file) throws Exception {
        return Integer.parseInt(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).trim());
    }
}
