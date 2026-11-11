package cyano.lootable;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
public final class ClientProxy {
    private ClientProxy() { }
    public static void register(FMLJavaModLoadingContext context) {
        TickEvent.ClientTickEvent.Post.BUS.addListener(new cyano.lootable.network.ClientLoginReadGuard()::tick);
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                ()->new ConfigScreenHandler.ConfigScreenFactory(new CorpseConfigGuiFactory()));
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(ClientProxy::setup);
        EntityRenderersEvent.RegisterRenderers.BUS.addListener(ClientProxy::renderers);
    }
    public static java.util.concurrent.CompletableFuture<java.util.Optional<com.mojang.authlib.GameProfile>> fetchProfile(String name) {
        var partial = net.minecraft.world.item.component.ResolvableProfile.createUnresolved(name);
        return partial.resolveProfile(Minecraft.getInstance().services().profileResolver())
                .thenApply(profile -> profile.equals(partial.partialProfile()) ? java.util.Optional.empty() : java.util.Optional.of(profile));
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
