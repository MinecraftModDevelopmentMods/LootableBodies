package cyano.lootable.buildtest;

import net.minecraft.world.item.Item;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge 1.12 registers controlled dependencies during their registry events. */
public final class TestRegistries {
    @SubscribeEvent public void items(RegistryEvent.Register<Item> event) {NbtWorldChecks.registerItem();}
    @SubscribeEvent public void potions(RegistryEvent.Register<MobEffect> event) {EffectHurtChecks.registerPotion();}
}
