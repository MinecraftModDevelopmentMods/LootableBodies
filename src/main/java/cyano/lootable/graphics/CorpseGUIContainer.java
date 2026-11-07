package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Created by Chris on 4/12/2016.
 */
@OnlyIn(Dist.CLIENT)
public class CorpseGUIContainer extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<CorpseContainer>{

	private final ResourceLocation image = ResourceLocation.parse(LootableBodies.MODID+":textures/gui/corpse.png");

	public CorpseGUIContainer(Inventory playerItems, Container entity) {
		this(new CorpseContainer(playerItems,entity),playerItems,entity instanceof net.minecraft.world.entity.Entity?((net.minecraft.world.entity.Entity)entity).getDisplayName():Component.literal(LootableBodies.NAME));
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
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;
		graphics.blit(image, x, y, 0, 0, imageWidth, imageHeight);
	}

    @Override
    protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY) {
        // This seven-row layout has no label strip. Keep the original screen's
        // foreground; the new vanilla labels would overlap corpse item slots.
    }
}
