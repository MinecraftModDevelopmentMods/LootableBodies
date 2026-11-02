package cyano.lootable.upgrade;

/** Direct header units have no transformed ModLauncher class layer.
 * Actual engine/attestation contracts are tested separately before runtime proof.
 * This fixture models completed serial activation only; production has no bypass.
 */
final class Native118UnitLifecycle {
    private Native118UnitLifecycle() { }
    static void activate()throws Exception {
        java.lang.reflect.Field installed=EarlyWorldPreservation.class.getDeclaredField("installed");
        installed.setAccessible(true);installed.setBoolean(null,true);
    }
}
