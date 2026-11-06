package cyano.lootable.buildtest;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.world.level.block.entity.SkullBlockEntity;

/** An owned test cache at the actual native profile lookup seam. */
final class FixtureProfileCache {
    private FixtureProfileCache() { }

    static AutoCloseable install(Function<String,CompletableFuture<Optional<GameProfile>>> lookup) {
        try {
            var field=SkullBlockEntity.class.getDeclaredField("profileCacheByName");
            field.setAccessible(true);
            Object previous=field.get(null);
            field.set(null,CacheBuilder.newBuilder().maximumSize(64).build(
                    new CacheLoader<String,CompletableFuture<Optional<GameProfile>>>() {
                        @Override public CompletableFuture<Optional<GameProfile>> load(String name) {
                            return lookup.apply(name);
                        }
                    }));
            return ()->field.set(null,previous);
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot bind the native fixture profile cache",failure);
        }
    }
}
