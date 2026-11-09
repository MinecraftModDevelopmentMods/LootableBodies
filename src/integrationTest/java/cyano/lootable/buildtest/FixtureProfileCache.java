package cyano.lootable.buildtest;

import net.minecraft.client.Minecraft;
import net.minecraft.server.Services;
import net.minecraft.server.players.ProfileResolver;
import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** An owned test cache at the actual native profile lookup seam. */
final class FixtureProfileCache {
    static com.mojang.authlib.GameProfile withTexture(java.util.UUID id, String name, String texture) {
        var entry = new com.mojang.authlib.properties.Property("textures", texture);
        return new com.mojang.authlib.GameProfile(id, name, new com.mojang.authlib.properties.PropertyMap(
                com.google.common.collect.ImmutableMultimap.of("textures", entry)));
    }

    private FixtureProfileCache() { }

    static AutoCloseable install(Function<String,CompletableFuture<Optional<GameProfile>>> lookup) {
        try {
            Minecraft client = Minecraft.getInstance();
            var field = Minecraft.class.getDeclaredField("services");
            field.setAccessible(true);
            Services previous = client.services();
            ProfileResolver resolver = new ProfileResolver() {
                @Override public Optional<GameProfile> fetchByName(String name) {
                    return lookup.apply(name).join();
                }

                @Override public Optional<GameProfile> fetchById(java.util.UUID id) {
                    throw new AssertionError("Unexpected UUID-only fixture profile lookup");
                }
            };
            Services owned = new Services(previous.sessionService(), previous.servicesKeySet(),
                    previous.profileRepository(), previous.nameToIdCache(), resolver);
            field.set(client, owned);
            return () -> field.set(client, previous);
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot bind the native fixture profile cache",failure);
        }
    }
}
