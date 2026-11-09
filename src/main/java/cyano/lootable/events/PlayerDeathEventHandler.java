package cyano.lootable.events;

import static cyano.lootable.ItemStacks.emptyStack;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import cyano.lootable.ModLog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PlayerDeathEventHandler {

	private static final Map<Player,Map<ItemStack,EquipmentSlot>> equipmentCache = new WeakHashMap<>();

    public Runnable register() {
        var death = LivingDeathEvent.BUS.addListener(Priority.LOW, this::entityDeathEvent);
        var drops = LivingDropsEvent.BUS.addListener(Priority.LOWEST, this::entityDropEvent);
        var deathCleanup = LivingDeathEvent.BUS.addListener((event, cancelled) -> {
            if (cancelled) equipmentCache.remove(event.getEntity());
        });
        var dropCleanup = LivingDropsEvent.BUS.addListener((event, cancelled) -> {
            if (cancelled) equipmentCache.remove(event.getEntity());
        });
        return () -> {
            LivingDeathEvent.BUS.removeListener(deathCleanup);
            LivingDeathEvent.BUS.removeListener(death);
            LivingDropsEvent.BUS.removeListener(drops);
            LivingDropsEvent.BUS.removeListener(dropCleanup);
        };
    }

	public void entityDeathEvent(LivingDeathEvent e){
		if (e.getEntity() instanceof Player player && player.level() instanceof ServerLevel world) {
			if (player.isSpectator() || world.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
			Map<ItemStack,EquipmentSlot> cache = new IdentityHashMap<>();
			for(EquipmentSlot slot : EntityLootableBody.EQUIPMENT_SLOTS){
				ItemStack stack = player.getItemBySlot(slot);
				if(!emptyStack(stack)) cache.put(stack,slot);
			}

			if(player.getMainArm() == HumanoidArm.LEFT){
				// swap main and off hand items (easier than messing with the rendering code)
				cache.put(player.getItemBySlot(EquipmentSlot.MAINHAND),EquipmentSlot.OFFHAND);
				cache.put(player.getItemBySlot(EquipmentSlot.OFFHAND),EquipmentSlot.MAINHAND);
			}
			equipmentCache.put(player,cache);
		}
	}

	public void entityDropEvent(LivingDropsEvent e){
		if (e.getEntity() instanceof Player player && player.level() instanceof ServerLevel world) {
			Map<ItemStack,EquipmentSlot> cache = equipmentCache.remove(player);
			if (player.isSpectator() || world.getGameRules().get(GameRules.KEEP_INVENTORY)) return;
			if(cache == null) cache = Collections.emptyMap();

			EntityLootableBody corpse = new EntityLootableBody(player);
			corpse.setUserName(player.getGameProfile().name());
			corpse.setRotation(player.getYRot());

			List<ItemStack> items = new ArrayList<>();
			Set<EquipmentSlot> assignedSlots = EnumSet.noneOf(EquipmentSlot.class);
			for (ItemEntity itemEntity : e.getDrops()) {
				ItemStack item = itemEntity.getItem();
				if(emptyStack(item) || item.getCount() <= 0) continue;
				EquipmentSlot slot = cache.get(item);
				// Stage independent stacks so a rejected spawn cannot mutate vanilla drops.
				if (slot != null && assignedSlots.add(slot)) {
					corpse.setItemSlot(slot,item.copy());
				} else {
					items.add(item.copy());
				}
			}
			corpse.initializeItems(items.toArray(new ItemStack[0]));

			if(LootableBodies.addBonesToCorpse){
				corpse.addItem(new ItemStack(Items.BONE,1+world.random.nextInt(3)));
				corpse.addItem(new ItemStack(Items.ROTTEN_FLESH,1+world.random.nextInt(3)));
			}

			if(world.addFreshEntity(corpse)) {
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
