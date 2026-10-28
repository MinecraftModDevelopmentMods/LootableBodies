package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.IInventory;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;

/**
 * Created by Chris on 4/12/2016.
 */
@OnlyIn(Dist.CLIENT)
public class CorpseGUIContainer extends net.minecraft.client.gui.screen.inventory.ContainerScreen<CorpseContainer>{

	private final ResourceLocation image = new ResourceLocation(LootableBodies.MODID+":textures/gui/corpse.png");

	public CorpseGUIContainer(PlayerInventory playerItems, IInventory entity) {
		this(new CorpseContainer(playerItems,entity),playerItems,entity instanceof net.minecraft.entity.Entity?((net.minecraft.entity.Entity)entity).getDisplayName():new StringTextComponent(LootableBodies.NAME));
    }
    public CorpseGUIContainer(CorpseContainer container, PlayerInventory playerItems, ITextComponent title){
        super(container,playerItems,title);
		this.xSize = 176;
		this.ySize = 222;
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
	protected void drawGuiContainerBackgroundLayer(com.mojang.blaze3d.matrix.MatrixStack matrices, float partialTicks, int mouseX, int mouseY) {
		GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		int x = (width - xSize) / 2;
		int y = (height - ySize) / 2;
		this.minecraft.getTextureManager().bindTexture(image);
		this.blit(matrices, x, y, 0, 0, this.xSize, this.ySize); // x, y, textureOffsetX, textureOffsetY, width, height)
	}

    @Override
    protected void drawGuiContainerForegroundLayer(com.mojang.blaze3d.matrix.MatrixStack matrices,int mouseX,int mouseY) {
        // This seven-row layout has no label strip. Keep the original screen's
        // foreground; the new vanilla labels would overlap corpse item slots.
    }
}
