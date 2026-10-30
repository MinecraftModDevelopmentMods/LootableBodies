package cyano.lootable.graphics;

import static cyano.lootable.ItemStacks.emptyStack;

import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemTossEvent;

/**
 * Created by Chris on 4/12/2016.
 */
public class CorpseContainer extends net.minecraft.world.inventory.AbstractContainerMenu {

	private static final EquipmentSlot[] EQUIPMENT_SLOTS = EntityLootableBody.EQUIPMENT_SLOTS;

	private final Container targetInventory;
	private boolean dragging;
	public CorpseContainer(Inventory playerItems, Container entity){this(0,playerItems,entity);}
	public CorpseContainer(int windowId, Inventory playerItems, Container entity){
        super(cyano.lootable.LootableBodies.CORPSE_CONTAINER,windowId);
		this.targetInventory = entity;
		int index = 0;
		while(index < 4) {
			// armor
			final int i = index;
			this.addSlot(new net.minecraft.world.inventory.Slot(targetInventory, i, 8, 8 + 18*i) {
				@Override
				public int getMaxStackSize() {
					return 1;
				}

				@Override
				public boolean mayPlace(ItemStack item) {
					return !emptyStack(item) && item.getCount() > 0 && super.mayPlace(item) && item.canEquip(EQUIPMENT_SLOTS[i], (Entity) targetInventory);
				}
				@OnlyIn(Dist.CLIENT)
				public String getSlotTexture()
				{
					return new String[]{"item/empty_armor_slot_helmet","item/empty_armor_slot_chestplate","item/empty_armor_slot_leggings","item/empty_armor_slot_boots"}[i];
				}
			});
			index++;
		}
		while(index < EQUIPMENT_SLOTS.length){
			// held items
			final int i = index;
			this.addSlot(new net.minecraft.world.inventory.Slot(targetInventory, i, 8 + 18 * (i - 4), 98 ) {
				@OnlyIn(Dist.CLIENT)
				public String getSlotTexture()
				{
					return "item/empty_armor_slot_shield";
				}
			});
			index++;
		}

		while(index < targetInventory.getContainerSize()){
			int n = index - EQUIPMENT_SLOTS.length;
			int x = n % 6;
			int y = n / 6;

			this.addSlot(new Slot(targetInventory, index, 62 + x * 18, 8 + y * 18));

			index++;
		}

		bindPlayerInventory(playerItems, 140);
	}



	@Override
	public boolean stillValid(Player entityplayer) {
		return targetInventory.stillValid(entityplayer);
	}

	@Override
	public void clicked(int slotId, int button, ClickType type, Player player) {
		// A click can arrive before the next player tick closes a stale window.
		if(!stillValid(player)) {
			resetQuickCraft();
			return;
		}
		if(type != ClickType.QUICK_CRAFT && dragging) {
			resetQuickCraft();
			return;
		}
		if(type == ClickType.QUICK_CRAFT && getQuickcraftHeader(button) == 0
				&& !emptyStack(getCarried()) && isValidQuickcraftType(getQuickcraftType(button),player)) dragging = true;
		if(type == ClickType.THROW && emptyStack(getCarried())
				&& slotId >= 0 && slotId < slots.size()) {
			Slot slot = getSlot(slotId);
			if(slot.hasItem() && slot.mayPickup(player)) {
				ItemStack original = slot.getItem().copy();
				ItemStack removed = slot.remove(button == 0 ? 1 : original.getCount());
				slot.onTake(player,removed);
				if(!tryRelease(player,removed,true)) {
					slot.set(original);
					resync(player);
				}
			}
			return;
		}
		if((type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && slotId == -999 && (button == 0 || button == 1)) {
			ItemStack cursor = getCarried();
			if(!emptyStack(cursor)) {
				ItemStack original = cursor.copy();
				ItemStack removed = cursor.split(button == 0 ? cursor.getCount() : 1);
				if(cursor.getCount() == 0) setCarried(ItemStack.EMPTY);
				if(!tryRelease(player,removed,true)) {
					setCarried(original);
					resync(player);
				}
			}
			return;
		}
		if(type == ClickType.SWAP && button >= 0 && button < 9
				&& slotId >= 0 && slotId < targetInventory.getContainerSize()) {
			Slot slot = getSlot(slotId);
			ItemStack incoming = player.getInventory().getItem(button);
			ItemStack displaced = slot.getItem();
			if(!emptyStack(incoming) && !emptyStack(displaced) && slot.mayPickup(player) && slot.mayPlace(incoming)) {
				int limit = Math.min(slot.getMaxStackSize(incoming),incoming.getMaxStackSize());
				if(limit > 0 && incoming.getCount() > limit) {
					slot.set(incoming.split(limit));
					slot.onTake(player,displaced);
					storeInPlayerInventory(player.getInventory(),displaced);
					if(displaced.getCount() > 0) releaseOrRetain(player,displaced);
					return;
				}
			}
		}
		super.clicked(slotId,button,type,player);
	}

	@Override
	public void removed(Player player) {
		ItemStack cursor = getCarried();
		if(!emptyStack(cursor)) {
			releaseOrRetain(player,cursor);
			setCarried(ItemStack.EMPTY);
		}
	}

	@Override
	protected void resetQuickCraft() {
		dragging = false;
		super.resetQuickCraft();
	}

	private void resync(Player player) {
		// Accepted transaction replies assume client prediction succeeded. A
		// rejected toss needs an explicit source/cursor correction as well.
		if(player instanceof ServerPlayer) broadcastFullState();
	}

	private void releaseOrRetain(Player player, ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0 || tryRelease(player,stack,false)) return;
		ItemStack remainder = stack.copy();
		// PlayerInventory discards overflow in creative mode. Retain real
		// corpse/cursor items even when every player slot is full.
		storeInPlayerInventory(player.getInventory(),remainder);
		if(remainder.getCount() > 0) ((EntityLootableBody)targetInventory).retainItem(remainder);
	}

    private void storeInPlayerInventory(Inventory inventory, ItemStack remainder) {
        // Keep vanilla's selected-slot, off-hand, main-slot and first-empty
        // priority, without PlayerInventory's creative overflow discard.
        while(remainder.getCount() > 0) {
            int destination = -1;
            if(!remainder.isDamaged()) {
                if(canStore(inventory,inventory.selected,remainder)) destination = inventory.selected;
                else if(canStore(inventory,40,remainder)) destination = 40;
                else for(int i = 0; i < inventory.items.size(); i++) {
                    if(canStore(inventory,i,remainder)) { destination = i; break; }
                }
            }
            if(destination < 0) destination = inventory.getFreeSlot();
            if(destination < 0) return;
            ItemStack current = inventory.getItem(destination);
            int limit = Math.min(remainder.getMaxStackSize(),inventory.getMaxStackSize());
            int amount = Math.min(remainder.getCount(),Math.max(0,limit - (emptyStack(current) ? 0 : current.getCount())));
            if(amount == 0) return;
            if(emptyStack(current)) {
                inventory.setItem(destination,remainder.split(amount));
                current = inventory.getItem(destination);
            } else {
                current.grow(amount);
                remainder.shrink(amount);
            }
            current.setPopTime(5);
            inventory.setChanged();
        }
    }

    private boolean canStore(Inventory inventory, int slot, ItemStack incoming) {
        ItemStack current = inventory.getItem(slot);
        return !emptyStack(current) && current.isStackable() && current.getItem() == incoming.getItem()
                && ItemStack.tagMatches(current,incoming)
                && current.getCount() < Math.min(current.getMaxStackSize(),inventory.getMaxStackSize());
    }

	private boolean tryRelease(Player player, ItemStack stack, boolean includeName) {
		if(emptyStack(stack) || stack.getCount() <= 0) return false;
		if(player.level.isClientSide) {
			player.drop(stack.copy(),includeName);
			return true;
		}
		// Forge's legacy toss helper clears the caller's captured drops and
		// ignores spawn rejection. Preserve both ownership and the toss event.
		java.util.Collection<ItemEntity> capturing = player.captureDrops(new java.util.ArrayList<>());
		ItemEntity dropped;
		try {
			dropped = player.drop(stack.copy(),false,includeName);
			if(dropped != null) player.captureDrops().remove(dropped);
		} finally {
			player.captureDrops(capturing);
		}
		if(dropped == null) return false;
		ItemTossEvent event = new ItemTossEvent(dropped,player);
		if(MinecraftForge.EVENT_BUS.post(event)) return false;
		if(capturing != null) {
			capturing.add(event.getEntityItem());
			return true;
		}
		return player.level.addFreshEntity(event.getEntityItem());
	}

	@Override
	protected boolean moveItemStackTo(ItemStack stack, int start, int end, boolean reverse) {
		boolean changed = false;
		int step = reverse ? -1 : 1;
		for(int pass = 0; pass < 2 && stack.getCount() > 0; pass++) {
			for(int i = reverse ? end - 1 : start; i >= start && i < end && stack.getCount() > 0; i += step) {
				Slot slot = getSlot(i);
				if(!slot.mayPlace(stack)) continue;
				ItemStack current = slot.getItem();
				int limit = Math.min(slot.getMaxStackSize(stack),stack.getMaxStackSize());
				if(pass == 0 && stack.isStackable() && !emptyStack(current)
						&& ItemStack.isSame(stack,current) && ItemStack.tagMatches(stack,current)) {
					int amount = Math.min(stack.getCount(),Math.max(0,limit - current.getCount()));
					if(amount > 0) {
						current.grow(amount);
						stack.shrink(amount);
						slot.setChanged();
						changed = true;
					}
				} else if(pass == 1 && emptyStack(current) && limit > 0) {
					slot.set(stack.split(Math.min(stack.getCount(),limit)));
					changed = true;
				}
			}
		}
		return changed;
	}


	@Override
	public ItemStack quickMoveStack(Player player, int slot) {
		int hostSize = targetInventory.getContainerSize();
		ItemStack stack = ItemStack.EMPTY;
		Slot slotObject = (Slot) slots.get(slot);
		//null checks and checks if the item can be stacked (maxStackSize > 1)
		if (slotObject != null && slotObject.hasItem()) {
			ItemStack stackInSlot = slotObject.getItem();
			stack = stackInSlot.copy();

			//merges the item into player inventory since its in the tileEntity
			if (slot < hostSize) {
				if (!this.moveItemStackTo(stackInSlot, hostSize, 36+hostSize, true)) {
					return ItemStack.EMPTY;
				}
			}
			//places it into the tileEntity if possible since it's in the player inventory
			else if (!this.moveItemStackTo(stackInSlot, 0, hostSize, false)) {
				return ItemStack.EMPTY;
			}

			if (stackInSlot.getCount() == 0) {
				slotObject.set(ItemStack.EMPTY);
			} else {
				slotObject.setChanged();
			}

			if (stackInSlot.getCount() == stack.getCount()) {
				return ItemStack.EMPTY;
			}
			slotObject.onTake(player, stackInSlot);
		}
		return stack;
	}

	protected void bindPlayerInventory(Inventory playerInv, int yOffset) {
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, yOffset + row * 18));
			}
		}
		for (int i = 0; i < 9; i++) {
			addSlot(new Slot(playerInv, i, 8 + i * 18, yOffset+58));
		}
	}

}
