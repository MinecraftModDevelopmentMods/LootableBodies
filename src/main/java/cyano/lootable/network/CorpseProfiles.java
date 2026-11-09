package cyano.lootable.network;

import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Uses the native profile cache without waiting on the client thread. */
public final class CorpseProfiles {
    private static final UUID UNRESOLVED = new UUID(0,0);

    private CorpseProfiles() { }

    public static GameProfile placeholder(String name) {
        return new GameProfile(UNRESOLVED,name);
    }

    public static boolean isResolved(GameProfile profile) {
        return profile!=null && !UNRESOLVED.equals(profile.id());
    }

    public static CompletableFuture<GameProfile> resolve(GameProfile initial,
            Function<String,CompletableFuture<Optional<GameProfile>>> lookup) {
        try {
            return lookup.apply(initial.name()).handle((value,failure) ->
                    failure==null?value.filter(CorpseProfiles::isResolved).orElse(initial):initial);
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(initial);
        }
    }
}
