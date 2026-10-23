package cyano.lootable.buildtest;

import net.minecraft.item.Item;
import net.minecraft.potion.Potion;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Forge 1.12 registers controlled dependencies during their registry events. */
public final class TestRegistries {
    @SubscribeEvent public void items(RegistryEvent.Register<Item> event) {NbtWorldChecks.registerItem();}
    @SubscribeEvent public void potions(RegistryEvent.Register<Potion> event) {EffectHurtChecks.registerPotion();}
}
