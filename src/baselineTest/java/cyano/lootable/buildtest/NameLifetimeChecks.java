package cyano.lootable.buildtest;
import net.minecraft.nbt.NBTBase;
final class NameLifetimeChecks { static boolean same(NBTBase a,NBTBase b){return TypedNbt.canonical(a).equals(TypedNbt.canonical(b));}}
