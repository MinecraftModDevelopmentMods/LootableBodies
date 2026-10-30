package cyano.lootable.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.minecraft.server.players.GameProfileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Native117ProfileLookupTest {
    @TempDir Path directory;
    final GameProfile initial=new GameProfile(null,"Owner");
    GameProfile cached=new GameProfile(UUID.fromString("00000000-0000-4000-8000-000000000201"),"Owner");
    GameProfile added;
    GameProfileCache cache(boolean broken) {
        return new GameProfileCache(null,directory.resolve("unused.json").toFile()) {
            @Override public Optional<GameProfile> get(String name) {
                if(broken)throw new IllegalStateException("injected cache failure");
                return Optional.of(cached);
            }
            @Override public void add(GameProfile value) {added=value;}
        };
    }
    MinecraftSessionService service(UnaryOperator<GameProfile> action) {
        return (MinecraftSessionService)Proxy.newProxyInstance(MinecraftSessionService.class.getClassLoader(),new Class<?>[]{MinecraftSessionService.class},
                (proxy,method,args)->{
                    if(method.getName().equals("fillProfileProperties"))return action.apply((GameProfile)args[0]);
                    throw new AssertionError("Unexpected native service "+method.getName());
                });
    }
    @Test void cacheFailureKeepsTheOriginalFallback() {
        assertSame(initial,CorpseProfiles.resolve(initial,cache(true),service(profile->{throw new AssertionError("Service must not run");})));
    }
    @Test void sessionFailureKeepsTheOriginalFallback() {
        assertSame(initial,CorpseProfiles.resolve(initial,cache(false),service(profile->{throw new IllegalStateException("injected service failure");})));
        assertNull(added);
    }
    @Test void resolvedProfileKeepsNativeIdentityAndTexturesWithoutMutatingCachedInput() {
        GameProfile resolved=CorpseProfiles.resolve(initial,cache(false),service(profile->{
            profile.getProperties().put("textures",new Property("textures","native-fixture"));return profile;
        }));
        assertEquals(cached.getId(),resolved.getId());assertEquals("Owner",resolved.getName());
        assertEquals("native-fixture",resolved.getProperties().get("textures").iterator().next().getValue());
        assertFalse(cached.getProperties().containsKey("textures"));assertSame(resolved,added);
    }
    @Test void warmTexturesSkipTheServiceAndMissingServicesKeepFallback() {
        cached.getProperties().put("textures",new Property("textures","cached"));AtomicInteger requests=new AtomicInteger();
        GameProfile resolved=CorpseProfiles.resolve(initial,cache(false),service(profile->{requests.incrementAndGet();return profile;}));
        assertEquals(0,requests.get());assertEquals(cached.getId(),resolved.getId());
        assertSame(initial,CorpseProfiles.resolve(initial,null,null));
    }
}
