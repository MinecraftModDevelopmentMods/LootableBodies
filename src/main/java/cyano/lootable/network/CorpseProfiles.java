package cyano.lootable.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.server.players.GameProfileCache;

/** Synchronous native services; callers own the bounded background worker. */
public final class CorpseProfiles {
    private CorpseProfiles() { }
    public static GameProfile resolve(GameProfile initial,GameProfileCache cache,MinecraftSessionService service) {
        if(initial==null || cache==null || service==null)return initial;
        try {
            GameProfile cached=cache.get(initial.getName()).orElse(initial);
            if(cached.getId()==null)return initial;
            GameProfile resolved=new GameProfile(cached.getId(),cached.getName());
            resolved.getProperties().putAll(cached.getProperties());
            if(!resolved.getProperties().containsKey("textures"))resolved=service.fillProfileProperties(resolved,true);
            if(resolved==null)return initial;
            cache.add(resolved);
            return resolved;
        }catch(RuntimeException unavailable) {
            cyano.lootable.ModLog.fine("Corpse profile lookup unavailable; using default skin");
            return initial;
        }
    }
}
