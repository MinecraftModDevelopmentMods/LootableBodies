package cyano.lootable.buildtest;

import cyano.lootable.entities.EntityLootableBody;
import java.util.UUID;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/** Reads real server readiness without changing the client/server protocol. */
final class NativeClientInteractionGate {
    private record State(boolean loaded,boolean admitted,boolean valid,String menu) { }
    private MinecraftServer observedServer;
    private UUID observedEntity;
    private Future<State> pending;
    private String previous;

    boolean ready(Minecraft mc,Entity target,String phase) throws Exception {
        MinecraftServer server=mc.getSingleplayerServer();
        UUID playerId=mc.player.getUUID(),id=target.getUUID();int packetId=target.getId();
        if(server!=observedServer || !id.equals(observedEntity)) {
            observedServer=server;observedEntity=id;pending=null;previous=null;
        }
        if(pending==null) {
            pending=server.submit(()-> {
                var player=server.getPlayerList().getPlayer(playerId);
                Entity actual=player==null?null:player.level().getEntityOrPart(packetId);
                boolean admitted=actual instanceof EntityLootableBody && actual.getUUID().equals(id);
                return new State(player!=null&&player.connection.hasClientLoaded(),admitted,
                        admitted&&((EntityLootableBody)actual).stillValid(player),player==null?"missing player":player.containerMenu.getClass().getName());
            });
            return false;
        }
        if(!pending.isDone())return false;
        State state=pending.get();pending=null;
        String receipt=phase+" packetId="+packetId+" "+state;
        if(!receipt.equals(previous)){System.out.println("NATIVE_GUI_INTERACTION_READINESS "+receipt);previous=receipt;}
        return state.loaded() && state.admitted() && state.valid();
    }
}
