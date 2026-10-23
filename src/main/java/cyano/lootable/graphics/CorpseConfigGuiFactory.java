package cyano.lootable.graphics;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.api.distmarker.*;
/** The regular Forge Mods-list Config button entry point. */
@OnlyIn(Dist.CLIENT)
public final class CorpseConfigGuiFactory implements BiFunction<Minecraft,GuiScreen,GuiScreen> {
    @Override public GuiScreen apply(Minecraft minecraft,GuiScreen parent){return new CorpseConfigGui(parent);}
}
