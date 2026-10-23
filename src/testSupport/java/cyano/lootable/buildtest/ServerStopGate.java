package cyano.lootable.buildtest;

/** Shared by unit tests and the build-only graphical verifier. */
final class ServerStopGate {
    private ServerStopGate() {}

    static boolean ready(boolean worldUnloaded, boolean serverReportedRunning, Thread retiringServer) {
        return worldUnloaded && !serverReportedRunning &&
                (retiringServer == null || !retiringServer.isAlive());
    }
}
