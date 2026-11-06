package cyano.lootable.network;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Native117ProfileLookupTest {
    private final GameProfile placeholder=CorpseProfiles.placeholder("TestName");
    private final GameProfile found=new GameProfile(new UUID(1,2),"TestName");

    @Test void unresolvedProfileUsesAValidAuthlibIdentity() {
        assertEquals("TestName",placeholder.getName());
        assertNotNull(placeholder.getId());
        assertFalse(CorpseProfiles.isResolved(placeholder));
    }

    @Test void absentLookupKeepsTheOriginalPlaceholder() {
        assertSame(placeholder,CorpseProfiles.resolve(placeholder,
                name->CompletableFuture.completedFuture(Optional.empty())).join());
    }

    @Test void asyncAndImmediateFailuresKeepTheOriginalPlaceholder() {
        assertSame(placeholder,CorpseProfiles.resolve(placeholder,
                name->CompletableFuture.failedFuture(new IllegalStateException("fixture"))).join());
        assertSame(placeholder,CorpseProfiles.resolve(placeholder,
                name->{throw new IllegalStateException("fixture");}).join());
    }

    @Test void lookupDoesNotWaitAndKeepsTheResolvedTexture() {
        CompletableFuture<Optional<GameProfile>> pending=new CompletableFuture<>();
        CompletableFuture<GameProfile> result=CorpseProfiles.resolve(placeholder,name->{
            assertEquals("TestName",name);
            return pending;
        });
        assertFalse(result.isDone());
        found.getProperties().put("textures",new Property("textures","fixture-native-texture"));
        pending.complete(Optional.of(found));
        assertSame(found,result.join());
        assertTrue(CorpseProfiles.isResolved(result.join()));
        assertEquals("fixture-native-texture",result.join().getProperties().get("textures").iterator().next().value());
    }
}
