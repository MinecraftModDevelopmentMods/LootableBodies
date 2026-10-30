package cyano.lootable;
import net.minecraft.world.item.ItemStack;
/** Shared guard for legacy integrations and the native empty-stack sentinel. */
public final class ItemStacks {
    private ItemStacks() { }
    public static boolean emptyStack(ItemStack stack) { return stack == null || stack.isEmpty(); }
}
