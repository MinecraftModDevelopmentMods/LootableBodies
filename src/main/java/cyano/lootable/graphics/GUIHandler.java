package cyano.lootable.graphics;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.fmllegacy.network.NetworkHooks;
/** Native Forge container opening, with the historical local helper kept internal. */
public final class GUIHandler {
    private static final GUIHandler INSTANCE=new GUIHandler();
    private GUIHandler() { }
    public static GUIHandler getInstance(){return INSTANCE;}
    public static void open(Player player,EntityLootableBody corpse){if(player instanceof ServerPlayer)NetworkHooks.openGui((ServerPlayer)player,corpse,buf->buf.writeVarInt(corpse.getId()));}
    public Object getServerGuiElement(int id,Player player,Level world,int x,int y,int z){Entity entity=world.getEntity(x);return id==0 && entity instanceof EntityLootableBody?new CorpseContainer(player.getInventory(),(EntityLootableBody)entity):null;}
    @OnlyIn(Dist.CLIENT) public Object getClientGuiElement(int id,Player player,Level world,int x,int y,int z){Entity entity=world.getEntity(x);return id==0 && entity instanceof EntityLootableBody?new CorpseGUIContainer(player.getInventory(),(EntityLootableBody)entity):null;}
}
