package cyano.lootable.buildtest;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

/** Controlled dependency written only by the public17 mod/native37 fixture. */
public final class FixtureItems {
    public static Item keepsake;
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus){bus.register(new RegistryItems());}
    public static final class RegistryItems {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void register(net.minecraftforge.event.RegistryEvent.Register<Item> event){
            keepsake=new Item(new Item.Properties()) {
                @Override public ICapabilityProvider initCapabilities(ItemStack stack,CompoundTag nbt){return new Stored();}
            }.setRegistryName("lootablebodiesbuildtest:keepsake");
            event.getRegistry().register(keepsake);
        }
    }
    private static final class Stored implements ICapabilitySerializable<CompoundTag> {
        private CompoundTag data=new CompoundTag();
        Stored(){data.putString("owner","Disposable fixture");data.putLong("number",9876543210123L);data.putByteArray("opaque",new byte[]{0,1,-1,17});}
        public <T>net.minecraftforge.common.util.LazyOptional<T> getCapability(Capability<T> capability,Direction side){return net.minecraftforge.common.util.LazyOptional.empty();}
        public CompoundTag serializeNBT(){return data.copy();}
        public void deserializeNBT(CompoundTag nbt){data=nbt.copy();}
    }
}
