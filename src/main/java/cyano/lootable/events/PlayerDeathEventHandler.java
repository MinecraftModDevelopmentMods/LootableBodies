package cyano.lootable.events;

import static cyano.lootable.ItemStacks.emptyStack;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.command.server.CommandSummon;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PlayerDeathEventHandler {

	private static final Map<EntityPlayer,Map<ItemStack,EntityEquipmentSlot>> equipmentCache = new WeakHashMap<>();

	@SubscribeEvent(priority= EventPriority.HIGHEST)
	public void corpseEnteringChunk(EntityEvent.EnteringChunk e) {
		if(e.getEntity() instanceof EntityLootableBody && !e.getEntity().getEntityWorld().isRemote)
			((EntityLootableBody)e.getEntity()).recoverPositionInChunk(e.getNewChunkX(),e.getNewChunkZ());
	}

	@SubscribeEvent(priority= EventPriority.LOW, receiveCanceled = true)
	public void entityDeathEvent(LivingDeathEvent e){
		if(e.getEntity() instanceof EntityPlayer
				&& !e.getEntity().getEntityWorld().isRemote) {
			final EntityPlayer player = (EntityPlayer)e.getEntity();
			equipmentCache.remove(player);
			if(e.isCanceled() || e.getResult() == Event.Result.DENY || player.isSpectator()
					|| player.getEntityWorld().getGameRules().getBoolean("keepInventory")) return;
			Map<ItemStack,EntityEquipmentSlot> cache = new IdentityHashMap<>();
			for(EntityEquipmentSlot slot : EntityLootableBody.EQUIPMENT_SLOTS){
				ItemStack stack = player.getItemStackFromSlot(slot);
				if(!emptyStack(stack)) cache.put(stack,slot);
			}

			if(player.getPrimaryHand() == EnumHandSide.LEFT){
				// swap main and off hand items (easier than messing with the rendering code)
				cache.put(player.getItemStackFromSlot(EntityEquipmentSlot.MAINHAND),EntityEquipmentSlot.OFFHAND);
				cache.put(player.getItemStackFromSlot(EntityEquipmentSlot.OFFHAND),EntityEquipmentSlot.MAINHAND);
			}
			equipmentCache.put(player,cache);
		}
	}

	@SubscribeEvent(priority= EventPriority.LOWEST, receiveCanceled = true)
	public void entityDropEvent(LivingDropsEvent e){
		if(e.getEntity() instanceof EntityPlayer
				&& !e.getEntity().getEntityWorld().isRemote) {
			final EntityPlayer player = (EntityPlayer)e.getEntity();
			Map<ItemStack,EntityEquipmentSlot> cache = equipmentCache.remove(player);
			if(e.isCanceled() || e.getResult() == Event.Result.DENY || player.isSpectator()
					|| player.getEntityWorld().getGameRules().getBoolean("keepInventory")) return;
			if(cache == null) cache = Collections.emptyMap();
			final World w = player.getEntityWorld();

			EntityLootableBody corpse = new EntityLootableBody(player);
			CommandSummon k;
			corpse.setUserName(player.getName());
			corpse.setRotation(player.rotationYaw);

			List<ItemStack> items = new ArrayList<>();
			Set<EntityEquipmentSlot> assignedSlots = EnumSet.noneOf(EntityEquipmentSlot.class);
			for (EntityItem itemEntity : e.getDrops()) {
				ItemStack item = itemEntity.getItem();
				if(emptyStack(item) || item.getCount() <= 0) continue;
				EntityEquipmentSlot slot = cache.get(item);
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

			if(w.spawnEntity(corpse)) {
				e.getDrops().clear();
			} else {
				FMLLog.warning("%s: Corpse spawn rejected; original death drops retained", LootableBodies.MODID);
			}
		}
	}


	private static void log(String s, Object... o){
		FMLLog.info("%s: %s", LootableBodies.MODID,String.format(s,o));
	}
	private static void log(Object o){
		FMLLog.info("%s: %s", LootableBodies.MODID,String.valueOf(o));
	}
}
