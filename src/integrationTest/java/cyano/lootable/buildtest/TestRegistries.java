package cyano.lootable.buildtest;

import net.minecraft.item.Item;
import net.minecraft.potion.Effect;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge 1.12 registers controlled dependencies during their registry events. */
public final class TestRegistries {
    @SubscribeEvent public void items(RegistryEvent.Register<Item> event) {NbtWorldChecks.registerItem();}
    @SubscribeEvent public void potions(RegistryEvent.Register<Effect> event) {EffectHurtChecks.registerPotion();}
}
