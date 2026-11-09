package cyano.lootable.buildtest;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

/** Controlled dependency used only by sealed disposable upgrade worlds. */
public final class FixtureItems {
    public static Item keepsake;
    public static void register(net.minecraftforge.eventbus.api.bus.BusGroup bus) {
        net.minecraftforge.registries.RegisterEvent.getBus(bus).addListener(new RegistryItems()::register);
    }
    public static final class RegistryItems {
        public void register(net.minecraftforge.registries.RegisterEvent event) {
            if(!event.getRegistryKey().equals(net.minecraft.core.registries.Registries.ITEM))return;
            // Native18 creates an intrusive holder inside Item's constructor;
            // construction belongs to the same open registration event.
            keepsake=new Item(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.ITEM, Identifier.parse("lootablebodiesbuildtest:keepsake")))) {
                @Override public ICapabilityProvider getCapabilityProvider(ItemStack stack){return new Stored();}
            };
            event.register(net.minecraft.core.registries.Registries.ITEM,Identifier.parse("lootablebodiesbuildtest:keepsake"),()->keepsake);
        }
    }
    private static final class Stored implements ICapabilitySerializable<CompoundTag> {
        private CompoundTag data=new CompoundTag();
        Stored(){data.putString("owner","Disposable fixture");data.putLong("number",9876543210123L);data.putByteArray("opaque",new byte[]{0,1,-1,17});}
        public boolean hasCapability(Capability<?> capability,Direction side){return false;}
        public <T>net.minecraftforge.common.util.LazyOptional<T> getCapability(Capability<T> capability,Direction side){return net.minecraftforge.common.util.LazyOptional.empty();}
        public CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider registries){return data.copy();}
        public void deserializeNBT(net.minecraft.core.HolderLookup.Provider registries,CompoundTag nbt){data=nbt.copy();}
    }
}
