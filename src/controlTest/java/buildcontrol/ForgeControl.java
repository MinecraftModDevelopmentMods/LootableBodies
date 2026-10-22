package buildcontrol;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Independent Forge/platform control; never loads Lootable Bodies. */
@Mod(modid="forgeplatformcontrol",name="Forge platform control",version="1")
public final class ForgeControl {
    @Mod.EventHandler public void started(FMLServerStartedEvent event) {
        if(FMLCommonHandler.instance().getSide().isServer()) {
            System.out.println("FORGE_PLATFORM_CONTROL_PASS");
            FMLCommonHandler.instance().getMinecraftServerInstance().initiateShutdown();
        }
    }
    @Mod.EventHandler public void init(FMLInitializationEvent event) {
        if(FMLCommonHandler.instance().getSide().isClient()) new ClientControl().register();
    }
    public static final class ClientControl {
        void register() { FMLCommonHandler.instance().bus().register(this); }
        @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
            if(event.phase==TickEvent.Phase.END && net.minecraft.client.Minecraft.getMinecraft().currentScreen instanceof net.minecraft.client.gui.GuiMainMenu) {
                System.out.println("FORGE_PLATFORM_CONTROL_PASS");
                net.minecraft.client.Minecraft.getMinecraft().shutdown();
            }
        }
    }
}
