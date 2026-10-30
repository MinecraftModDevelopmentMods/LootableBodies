package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;

/**
 * Created by Chris on 4/12/2016.
 */
@OnlyIn(Dist.CLIENT)
public class CorpseGUIContainer extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<CorpseContainer>{

	private final ResourceLocation image = new ResourceLocation(LootableBodies.MODID+":textures/gui/corpse.png");

	public CorpseGUIContainer(Inventory playerItems, Container entity) {
		this(new CorpseContainer(playerItems,entity),playerItems,entity instanceof net.minecraft.world.entity.Entity?((net.minecraft.world.entity.Entity)entity).getDisplayName():new TextComponent(LootableBodies.NAME));
    }
    public CorpseGUIContainer(CorpseContainer container, Inventory playerItems, Component title){
        super(container,playerItems,title);
		this.imageWidth = 176;
		this.imageHeight = 222;
	}

	/**
	 * Draws the background layer of this container (behind the items).
	 *
	 * @param partialTicks How far into the current tick the game is, with 0.0 being the start of the tick and 1.0 being
	 *                     the end.
	 * @param mouseX       Mouse x coordinate
	 * @param mouseY       Mouse y coordinate
	 */
	@Override
	protected void renderBg(com.mojang.blaze3d.vertex.PoseStack matrices, float partialTicks, int mouseX, int mouseY) {
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;
		RenderSystem.setShaderTexture(0,image);
		this.blit(matrices, x, y, 0, 0, this.imageWidth, this.imageHeight); // x, y, textureOffsetX, textureOffsetY, width, height)
	}

    @Override
    protected void renderLabels(com.mojang.blaze3d.vertex.PoseStack matrices,int mouseX,int mouseY) {
        // This seven-row layout has no label strip. Keep the original screen's
        // foreground; the new vanilla labels would overlap corpse item slots.
    }
}
