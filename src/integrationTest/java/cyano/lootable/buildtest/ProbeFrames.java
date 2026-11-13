package cyano.lootable.buildtest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;

/** Native culling and completed frame observations, confined to the disposable probe. */
public final class ProbeFrames {
    public record Frame(Camera camera, Frustum frustum, long tick) { }
    private static final List<Consumer<Frame>> observers = new ArrayList<>();
    private static Frame extracted;

    private ProbeFrames() { }
    static void observe(Consumer<Frame> observer) { observers.add(observer); }

    public static void capture(Camera camera,Frustum frustum,long tick) {
        extracted=new Frame(camera,new Frustum(frustum),tick);
    }
    public static void complete() {
        Frame frame=extracted;extracted=null;
        if (observers.isEmpty() || frame==null) return;
        for (Consumer<Frame> observer : observers) observer.accept(frame);
    }
}
