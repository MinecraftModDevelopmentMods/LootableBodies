package cyano.lootable.graphics;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraftforge.api.distmarker.*;
/** The regular Forge Mods-list Config button entry point. */
@OnlyIn(Dist.CLIENT)
public final class CorpseConfigGuiFactory implements BiFunction<Minecraft,Screen,Screen> {
    @Override public Screen apply(Minecraft minecraft,Screen parent){return new CorpseConfigGui(parent);}
}
