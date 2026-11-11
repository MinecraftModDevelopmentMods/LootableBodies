package cyano.lootable.graphics;

import cyano.lootable.LootableBodies;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/**
 * Created by Chris on 4/12/2016.
 */
public class CorpseGUIContainer extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<CorpseContainer>{

	private final Identifier image = Identifier.parse(LootableBodies.MODID+":textures/gui/corpse.png");

	public CorpseGUIContainer(Inventory playerItems, Container entity) {
		this(new CorpseContainer(playerItems,entity),playerItems,entity instanceof net.minecraft.world.entity.Entity?((net.minecraft.world.entity.Entity)entity).getDisplayName():Component.literal(LootableBodies.NAME));
    }
    public CorpseGUIContainer(CorpseContainer container, Inventory playerItems, Component title){
        super(container, playerItems, title, 176, 222);
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
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
		super.extractBackground(graphics, mouseX, mouseY, partialTicks);
		int x = (width - imageWidth) / 2;
		int y = (height - imageHeight) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, image, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
	}

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics,int mouseX,int mouseY) {
        // This seven-row layout has no label strip. Keep the original screen's
        // foreground; the new vanilla labels would overlap corpse item slots.
    }
}
