package cyano.lootable;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fmlclient.ConfigGuiHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
@OnlyIn(Dist.CLIENT)
public final class ClientProxy {
    private ClientProxy() { }
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new cyano.lootable.network.ClientLoginReadGuard());
        ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
                ()->new ConfigGuiHandler.ConfigGuiFactory(new CorpseConfigGuiFactory()));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientProxy::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientProxy::renderers);
    }
    private static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        // The client creates these readers before mod construction. Replace
        // their cached fixer on the main thread, before opening any world.
        event.enqueueWork(()-> {
            net.minecraft.client.Minecraft client=net.minecraft.client.Minecraft.getInstance();
            cyano.lootable.upgrade.ChunkVersionContext.installClient(client,client.getLevelSource());
            net.minecraft.client.gui.screens.MenuScreens.<CorpseContainer,CorpseGUIContainer>register(
                    LootableBodies.CORPSE_CONTAINER,CorpseGUIContainer::new);
        });
    }
    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LootableBodies.CORPSE,CorpseRenderer::new);
    }
}
