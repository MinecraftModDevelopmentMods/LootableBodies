package cyano.lootable.buildtest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;

/** Native culling and completed frame observations, confined to the disposable probe. */
public final class ProbeFrames {
    public record Frame(Camera camera, Frustum frustum, int tick) { }
    private static final List<Consumer<Frame>> observers = new ArrayList<>();

    private ProbeFrames() { }
    static void observe(Consumer<Frame> observer) { observers.add(observer); }

    public static void afterRender(Camera camera, Frustum frustum, int tick) {
        if (observers.isEmpty()) return;
        Frame frame = new Frame(camera, frustum, tick);
        for (Consumer<Frame> observer : observers) observer.accept(frame);
    }
}
