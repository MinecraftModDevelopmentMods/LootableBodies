package cyano.lootable.buildtest;

import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

/** Observe the native respawn record without changing the decoded packet. */
final class NativeRespawnDescription {
    private NativeRespawnDescription() { }

    static String describe(ClientboundRespawnPacket packet) {
        var spawn=packet.commonPlayerSpawnInfo();
        return "RESPAWN dimension="+spawn.dimension().location()+" hashedSeed="+spawn.seed();
    }
}
