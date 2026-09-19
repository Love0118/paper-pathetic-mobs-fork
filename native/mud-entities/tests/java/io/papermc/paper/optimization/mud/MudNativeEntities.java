package io.papermc.paper.optimization.mud;

/** Standalone JNI contract test, in a separate JVM from the server implementation. */
public final class MudNativeEntities {
    private static native int init0(Class<?> type);
    private static native boolean move0(Fixture[] entities, double[] xyz, int count);
    public static class Fixture {
        double x, y, z; float yaw, head, pitch; int calls;
        public void setPos(double x, double y, double z) { this.x=x; this.y=y; this.z=z; calls++; }
        public void setYRot(float v) { yaw=v; }
        public void setYHeadRot(float v) { head=v; }
        public void setXRot(float v) { pitch=v; }
    }
    public static final class Rejected extends Fixture {
        @Override public void setPos(double x, double y, double z) { throw new IllegalStateException("expected"); }
    }
    public static void main(String[] args) {
        System.load(java.nio.file.Path.of(args[0]).toAbsolutePath().toString());
        if (init0(Fixture.class)!=1) throw new AssertionError("ABI init");
        Fixture first = new Fixture(), second = new Fixture();
        double[] positions = {1,2,3,4,5, 6,7,8,9,10};
        for (int i=0;i<10_000;i++) {
            if (!move0(new Fixture[]{first,second},positions,2)) throw new AssertionError("batch");
        }
        if (first.calls!=10_000 || second.x!=6 || first.yaw!=4 || second.head!=9 || second.pitch!=10) throw new AssertionError("mutations");
        if (move0(new Fixture[]{first},positions,2)) throw new AssertionError("length validation");
        if (move0(new Fixture[]{first},new double[]{Double.NaN,0,0,0,0},1)) throw new AssertionError("finite validation");
        try { move0(new Fixture[]{new Rejected()},positions,1); throw new AssertionError("exception lost"); }
        catch (IllegalStateException expected) { if (!expected.getMessage().equals("expected")) throw expected; }
        System.gc();
        if (!move0(new Fixture[]{first},positions,1)) throw new AssertionError("GC reference lifetime");
        System.out.println("JNI mutations, bounds, exception propagation and GC lifetime: passed");
    }
}
