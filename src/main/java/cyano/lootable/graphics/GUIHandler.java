package cyano.lootable.graphics;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.*;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.fml.network.NetworkHooks;
/** Native Forge container opening, with the historical local helper kept internal. */
public final class GUIHandler {
    private static final GUIHandler INSTANCE=new GUIHandler();
    private GUIHandler() { }
    public static GUIHandler getInstance(){return INSTANCE;}
    public static void open(EntityPlayer player,EntityLootableBody corpse){if(player instanceof EntityPlayerMP)NetworkHooks.openGui((EntityPlayerMP)player,corpse,buf->buf.writeVarInt(corpse.getEntityId()));}
    public Object getServerGuiElement(int id,EntityPlayer player,World world,int x,int y,int z){Entity entity=world.getEntityByID(x);return id==0 && entity instanceof EntityLootableBody?new CorpseContainer(player.inventory,(EntityLootableBody)entity):null;}
    @OnlyIn(Dist.CLIENT) public Object getClientGuiElement(int id,EntityPlayer player,World world,int x,int y,int z){Entity entity=world.getEntityByID(x);return id==0 && entity instanceof EntityLootableBody?new CorpseGUIContainer(player.inventory,(EntityLootableBody)entity):null;}
}
