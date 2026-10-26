package cyano.lootable;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
@OnlyIn(Dist.CLIENT)
public final class ClientProxy {
    private ClientProxy() { }
    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new cyano.lootable.network.ClientLoginReadGuard());
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,CorpseConfigGuiFactory::new);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientProxy::setup);
    }
    private static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        RenderingRegistry.registerEntityRenderingHandler(EntityLootableBody.class,CorpseRenderer::new);
        net.minecraft.client.gui.ScreenManager.<CorpseContainer,CorpseGUIContainer>registerFactory(LootableBodies.CORPSE_CONTAINER,CorpseGUIContainer::new);
    }
}
