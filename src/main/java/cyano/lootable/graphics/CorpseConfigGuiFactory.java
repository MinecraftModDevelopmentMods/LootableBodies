package cyano.lootable.graphics;

import java.util.Collections;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** The regular Forge Mods-list Config button entry point. */
@SideOnly(Side.CLIENT)
public final class CorpseConfigGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft minecraft) { }
    @Override public Class<? extends GuiScreen> mainConfigGuiClass() { return CorpseConfigGui.class; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return Collections.emptySet(); }
    @Override public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) { return null; }
}
