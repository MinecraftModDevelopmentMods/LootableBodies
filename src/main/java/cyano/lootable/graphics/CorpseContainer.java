package cyano.lootable.graphics;

import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.ClickType;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemTossEvent;

/**
 * Created by Chris on 4/12/2016.
 */
public class CorpseContainer extends net.minecraft.inventory.Container {

	private static final EntityEquipmentSlot[] EQUIPMENT_SLOTS = EntityLootableBody.EQUIPMENT_SLOTS;

	private final IInventory targetInventory;
	private boolean dragging;
	public CorpseContainer(InventoryPlayer playerItems, IInventory entity){
		this.targetInventory = entity;
		net.minecraft.inventory.ContainerHorseInventory k;
		net.minecraft.inventory.ContainerPlayer h;
		int index = 0;
		while(index < 4) {
			// armor
			final int i = index;
			this.addSlotToContainer(new net.minecraft.inventory.Slot(targetInventory, i, 8, 8 + 18*i) {
				@Override
				public int getSlotStackLimit() {
					return 1;
				}

				@Override
				public boolean isItemValid(ItemStack item) {
					return item != null && item.stackSize > 0 && super.isItemValid(item) && item.getItem().isValidArmor(item, EQUIPMENT_SLOTS[i], (Entity) targetInventory);
				}
				@SideOnly(Side.CLIENT)
				public String getSlotTexture()
				{
					return ItemArmor.EMPTY_SLOT_NAMES[3-i];
				}
			});
			index++;
		}
		while(index < EQUIPMENT_SLOTS.length){
			// held items
			final int i = index;
			this.addSlotToContainer(new net.minecraft.inventory.Slot(targetInventory, i, 8 + 18 * (i - 4), 98 ) {
				@SideOnly(Side.CLIENT)
				public String getSlotTexture()
				{
					return "minecraft:items/empty_armor_slot_shield";
				}
			});
			index++;
		}

		while(index < targetInventory.getSizeInventory()){
			int n = index - EQUIPMENT_SLOTS.length;
			int x = n % 6;
			int y = n / 6;

			this.addSlotToContainer(new Slot(targetInventory, index, 62 + x * 18, 8 + y * 18));

			index++;
		}

		bindPlayerInventory(playerItems, 140);
	}



	@Override
	public boolean canInteractWith(EntityPlayer entityplayer) {
		return targetInventory.isUsableByPlayer(entityplayer);
	}

	@Override
	public ItemStack slotClick(int slotId, int button, ClickType type, EntityPlayer player) {
		// A click can arrive before the next player tick closes a stale window.
		if(!canInteractWith(player)) {
			resetDrag();
			return null;
		}
		if(type != ClickType.QUICK_CRAFT && dragging) {
			resetDrag();
			return null;
		}
		if(type == ClickType.QUICK_CRAFT && getDragEvent(button) == 0
				&& player.inventory.getItemStack() != null && isValidDragMode(extractDragMode(button),player)) dragging = true;
		if(type == ClickType.THROW && player.inventory.getItemStack() == null
				&& slotId >= 0 && slotId < inventorySlots.size()) {
			Slot slot = getSlot(slotId);
			if(slot.getHasStack() && slot.canTakeStack(player)) {
				ItemStack original = slot.getStack().copy();
				ItemStack removed = slot.decrStackSize(button == 0 ? 1 : original.stackSize);
				slot.onPickupFromSlot(player,removed);
				if(!tryRelease(player,removed,true)) {
					slot.putStack(original);
					resync(player);
				}
			}
			return null;
		}
		if((type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && slotId == -999 && (button == 0 || button == 1)) {
			ItemStack cursor = player.inventory.getItemStack();
			if(cursor != null) {
				ItemStack original = cursor.copy();
				ItemStack removed = cursor.splitStack(button == 0 ? cursor.stackSize : 1);
				if(cursor.stackSize == 0) player.inventory.setItemStack(null);
				if(!tryRelease(player,removed,true)) {
					player.inventory.setItemStack(original);
					resync(player);
				}
			}
			return null;
		}
		if(type == ClickType.SWAP && button >= 0 && button < 9
				&& slotId >= 0 && slotId < targetInventory.getSizeInventory()) {
			Slot slot = getSlot(slotId);
			ItemStack incoming = player.inventory.getStackInSlot(button);
			ItemStack displaced = slot.getStack();
			if(incoming != null && displaced != null && slot.canTakeStack(player) && slot.isItemValid(incoming)) {
				int limit = Math.min(slot.getItemStackLimit(incoming),incoming.getMaxStackSize());
				if(limit > 0 && incoming.stackSize > limit) {
					slot.putStack(incoming.splitStack(limit));
					slot.onPickupFromSlot(player,displaced);
					storeInPlayerInventory(player.inventory,displaced);
					if(displaced.stackSize > 0) releaseOrRetain(player,displaced);
					return null;
				}
			}
		}
		return super.slotClick(slotId,button,type,player);
	}

	@Override
	public void onContainerClosed(EntityPlayer player) {
		ItemStack cursor = player.inventory.getItemStack();
		if(cursor != null) {
			releaseOrRetain(player,cursor);
			player.inventory.setItemStack(null);
		}
	}

	@Override
	protected void resetDrag() {
		dragging = false;
		super.resetDrag();
	}

	private void resync(EntityPlayer player) {
		// Accepted transaction replies assume client prediction succeeded. A
		// rejected toss needs an explicit source/cursor correction as well.
		if(player instanceof EntityPlayerMP) ((EntityPlayerMP)player).sendContainerToPlayer(this);
	}

	private void releaseOrRetain(EntityPlayer player, ItemStack stack) {
		if(stack == null || stack.stackSize <= 0 || tryRelease(player,stack,false)) return;
		ItemStack remainder = stack.copy();
		// InventoryPlayer discards overflow in creative mode. Retain real
		// corpse/cursor items even when every player slot is full.
		storeInPlayerInventory(player.inventory,remainder);
		if(remainder.stackSize > 0) ((EntityLootableBody)targetInventory).retainItem(remainder);
	}

    private void storeInPlayerInventory(InventoryPlayer inventory, ItemStack remainder) {
        // Keep vanilla's selected-slot, off-hand, main-slot and first-empty
        // priority, without InventoryPlayer's creative overflow discard.
        while(remainder.stackSize > 0) {
            int destination = -1;
            if(!remainder.isItemDamaged()) {
                if(canStore(inventory,inventory.currentItem,remainder)) destination = inventory.currentItem;
                else if(canStore(inventory,40,remainder)) destination = 40;
                else for(int i = 0; i < inventory.mainInventory.length; i++) {
                    if(canStore(inventory,i,remainder)) { destination = i; break; }
                }
            }
            if(destination < 0) destination = inventory.getFirstEmptyStack();
            if(destination < 0) return;
            ItemStack current = inventory.getStackInSlot(destination);
            int limit = Math.min(remainder.getMaxStackSize(),inventory.getInventoryStackLimit());
            int amount = Math.min(remainder.stackSize,Math.max(0,limit - (current == null ? 0 : current.stackSize)));
            if(amount == 0) return;
            if(current == null) {
                inventory.setInventorySlotContents(destination,remainder.splitStack(amount));
                current = inventory.getStackInSlot(destination);
            } else {
                current.stackSize += amount;
                remainder.stackSize -= amount;
            }
            current.animationsToGo = 5;
            inventory.markDirty();
        }
    }

    private boolean canStore(InventoryPlayer inventory, int slot, ItemStack incoming) {
        ItemStack current = inventory.getStackInSlot(slot);
        return current != null && current.isStackable() && current.getItem() == incoming.getItem()
                && (!current.getHasSubtypes() || current.getMetadata() == incoming.getMetadata())
                && ItemStack.areItemStackTagsEqual(current,incoming)
                && current.stackSize < Math.min(current.getMaxStackSize(),inventory.getInventoryStackLimit());
    }

	private boolean tryRelease(EntityPlayer player, ItemStack stack, boolean includeName) {
		if(stack == null || stack.stackSize <= 0) return false;
		if(player.world.isRemote) {
			player.dropItem(stack.copy(),includeName);
			return true;
		}
		// Forge's legacy toss helper clears the caller's captured drops and
		// ignores spawn rejection. Preserve both ownership and the toss event.
		boolean capturing = player.captureDrops;
		EntityItem dropped;
		try {
			player.captureDrops = true;
			dropped = player.dropItem(stack.copy(),false,includeName);
			if(dropped != null) player.capturedDrops.remove(dropped);
		} finally {
			player.captureDrops = capturing;
		}
		if(dropped == null) return false;
		ItemTossEvent event = new ItemTossEvent(dropped,player);
		if(MinecraftForge.EVENT_BUS.post(event)) return false;
		if(capturing) {
			player.capturedDrops.add(event.getEntityItem());
			return true;
		}
		return player.world.spawnEntity(event.getEntityItem());
	}

	@Override
	protected boolean mergeItemStack(ItemStack stack, int start, int end, boolean reverse) {
		boolean changed = false;
		int step = reverse ? -1 : 1;
		for(int pass = 0; pass < 2 && stack.stackSize > 0; pass++) {
			for(int i = reverse ? end - 1 : start; i >= start && i < end && stack.stackSize > 0; i += step) {
				Slot slot = getSlot(i);
				if(!slot.isItemValid(stack)) continue;
				ItemStack current = slot.getStack();
				int limit = Math.min(slot.getItemStackLimit(stack),stack.getMaxStackSize());
				if(pass == 0 && stack.isStackable() && current != null
						&& ItemStack.areItemsEqual(stack,current) && ItemStack.areItemStackTagsEqual(stack,current)) {
					int amount = Math.min(stack.stackSize,Math.max(0,limit - current.stackSize));
					if(amount > 0) {
						current.stackSize += amount;
						stack.stackSize -= amount;
						slot.onSlotChanged();
						changed = true;
					}
				} else if(pass == 1 && current == null && limit > 0) {
					slot.putStack(stack.splitStack(Math.min(stack.stackSize,limit)));
					changed = true;
				}
			}
		}
		return changed;
	}


	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int slot) {
		int hostSize = targetInventory.getSizeInventory();
		ItemStack stack = null;
		Slot slotObject = (Slot) inventorySlots.get(slot);
		//null checks and checks if the item can be stacked (maxStackSize > 1)
		if (slotObject != null && slotObject.getHasStack()) {
			ItemStack stackInSlot = slotObject.getStack();
			stack = stackInSlot.copy();

			//merges the item into player inventory since its in the tileEntity
			if (slot < hostSize) {
				if (!this.mergeItemStack(stackInSlot, hostSize, 36+hostSize, true)) {
					return null;
				}
			}
			//places it into the tileEntity if possible since it's in the player inventory
			else if (!this.mergeItemStack(stackInSlot, 0, hostSize, false)) {
				return null;
			}

			if (stackInSlot.stackSize == 0) {
				slotObject.putStack(null);
			} else {
				slotObject.onSlotChanged();
			}

			if (stackInSlot.stackSize == stack.stackSize) {
				return null;
			}
			slotObject.onPickupFromSlot(player, stackInSlot);
		}
		return stack;
	}

	protected void bindPlayerInventory(InventoryPlayer playerInv, int yOffset) {
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlotToContainer(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, yOffset + row * 18));
			}
		}
		for (int i = 0; i < 9; i++) {
			addSlotToContainer(new Slot(playerInv, i, 8 + i * 18, yOffset+58));
		}
	}

}
