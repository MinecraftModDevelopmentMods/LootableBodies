package cyano.lootable.events;

import static cyano.lootable.ItemStacks.emptyStack;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;

import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HandSide;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import cyano.lootable.ModLog;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PlayerDeathEventHandler {

	private static final Map<PlayerEntity,Map<ItemStack,EquipmentSlotType>> equipmentCache = new WeakHashMap<>();

	@SubscribeEvent(priority= EventPriority.HIGHEST)
	public void corpseEnteringChunk(EntityEvent.EnteringChunk e) {
		if(e.getEntity() instanceof EntityLootableBody && !e.getEntity().getEntityWorld().isRemote)
			((EntityLootableBody)e.getEntity()).recoverPositionInChunk(e.getNewChunkX(),e.getNewChunkZ());
	}

	@SubscribeEvent(priority= EventPriority.LOW, receiveCanceled = true)
	public void entityDeathEvent(LivingDeathEvent e){
		if(e.getEntity() instanceof PlayerEntity
				&& !e.getEntity().getEntityWorld().isRemote) {
			final PlayerEntity player = (PlayerEntity)e.getEntity();
			equipmentCache.remove(player);
			if(e.isCanceled() || e.getResult() == Event.Result.DENY || player.isSpectator()
					|| player.getEntityWorld().getGameRules().getBoolean(net.minecraft.world.GameRules.KEEP_INVENTORY)) return;
			Map<ItemStack,EquipmentSlotType> cache = new IdentityHashMap<>();
			for(EquipmentSlotType slot : EntityLootableBody.EQUIPMENT_SLOTS){
				ItemStack stack = player.getItemStackFromSlot(slot);
				if(!emptyStack(stack)) cache.put(stack,slot);
			}

			if(player.getPrimaryHand() == HandSide.LEFT){
				// swap main and off hand items (easier than messing with the rendering code)
				cache.put(player.getItemStackFromSlot(EquipmentSlotType.MAINHAND),EquipmentSlotType.OFFHAND);
				cache.put(player.getItemStackFromSlot(EquipmentSlotType.OFFHAND),EquipmentSlotType.MAINHAND);
			}
			equipmentCache.put(player,cache);
		}
	}

	@SubscribeEvent(priority= EventPriority.LOWEST, receiveCanceled = true)
	public void entityDropEvent(LivingDropsEvent e){
		if(e.getEntity() instanceof PlayerEntity
				&& !e.getEntity().getEntityWorld().isRemote) {
			final PlayerEntity player = (PlayerEntity)e.getEntity();
			Map<ItemStack,EquipmentSlotType> cache = equipmentCache.remove(player);
			if(e.isCanceled() || e.getResult() == Event.Result.DENY || player.isSpectator()
					|| player.getEntityWorld().getGameRules().getBoolean(net.minecraft.world.GameRules.KEEP_INVENTORY)) return;
			if(cache == null) cache = Collections.emptyMap();
			final World w = player.getEntityWorld();

			EntityLootableBody corpse = new EntityLootableBody(player);
			corpse.setUserName(player.getGameProfile().getName());
			corpse.setRotation(player.rotationYaw);

			List<ItemStack> items = new ArrayList<>();
			Set<EquipmentSlotType> assignedSlots = EnumSet.noneOf(EquipmentSlotType.class);
			for (ItemEntity itemEntity : e.getDrops()) {
				ItemStack item = itemEntity.getItem();
				if(emptyStack(item) || item.getCount() <= 0) continue;
				EquipmentSlotType slot = cache.get(item);
				// Stage independent stacks so a rejected spawn cannot mutate vanilla drops.
				if (slot != null && assignedSlots.add(slot)) {
					corpse.setItemStackToSlot(slot,item.copy());
				} else {
					items.add(item.copy());
				}
			}
			corpse.initializeItems(items.toArray(new ItemStack[0]));

			if(LootableBodies.addBonesToCorpse){
				corpse.addItem(new ItemStack(Items.BONE,1+w.rand.nextInt(3)));
				corpse.addItem(new ItemStack(Items.ROTTEN_FLESH,1+w.rand.nextInt(3)));
			}

			if(w.addEntity(corpse)) {
				e.getDrops().clear();
			} else {
				ModLog.warning("%s: Corpse spawn rejected; original death drops retained", LootableBodies.MODID);
			}
		}
	}


	private static void log(String s, Object... o){
		ModLog.info("%s: %s", LootableBodies.MODID,String.format(s,o));
	}
	private static void log(Object o){
		ModLog.info("%s: %s", LootableBodies.MODID,String.valueOf(o));
	}
}
