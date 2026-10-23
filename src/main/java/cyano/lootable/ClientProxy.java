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
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ClientProxy::enableConfigButton);
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY,CorpseConfigGuiFactory::new);
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.GUIFACTORY,()->message->{
            Minecraft mc=Minecraft.getInstance();
            if(mc.world==null)return null;
            Entity entity=mc.world.getEntityByID(message.getAdditionalData().readVarInt());
            if(!(entity instanceof EntityLootableBody))return null;
            CorpseGUIContainer gui=new CorpseGUIContainer(mc.player.inventory,(EntityLootableBody)entity);
            gui.inventorySlots.windowId=message.getWindowId();return gui;
        });
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientProxy::setup);
    }
    private static void enableConfigButton(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre event) {
        if(!(event.getGui() instanceof net.minecraftforge.fml.client.gui.GuiModList))return;
        // Forge 25's ModInfo.hasConfigUI() returns false unconditionally. The
        // ordinary button already calls CONFIGGUIFACTORY when enabled.
        try {
            Class<?> type=net.minecraftforge.fml.client.gui.GuiModList.class;
            java.lang.reflect.Field selected=type.getDeclaredField("selectedMod"),button=type.getDeclaredField("configButton");
            selected.setAccessible(true);button.setAccessible(true);Object mod=selected.get(event.getGui());
            if(mod instanceof net.minecraftforge.forgespi.language.IModInfo
                    && ((net.minecraftforge.forgespi.language.IModInfo)mod).getModId().equals(LootableBodies.MODID)
                    && net.minecraftforge.fml.ModList.get().getModContainerById(LootableBodies.MODID).get().getCustomExtension(ExtensionPoint.CONFIGGUIFACTORY).isPresent())
                ((net.minecraft.client.gui.GuiButton)button.get(event.getGui())).enabled=true;
        }catch(ReflectiveOperationException incompatible){throw new IllegalStateException("Cannot connect the Forge Mods-list Config button",incompatible);}
    }
    private static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        RenderingRegistry.registerEntityRenderingHandler(EntityLootableBody.class,CorpseRenderer::new);
    }
}
