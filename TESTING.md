# Testing

Run qualification for Forge 1.21.11 locally with Forge 61.2.4 and Java 21.
Qualification runs locally on this branch.

During development, reproduce the changed behaviour first, then batch the cheap
Java and Python checks. Reserve the full runtime suite for a frozen candidate.
`build`/`check` no longer starts Minecraft implicitly. The mapped
fresh/reload/deferred gate remains available through `localRuntimeVerification`
and is still required by complete local qualification.

For a frozen candidate:

1. Run the full Java suite once and the full Python suite once for their exact
   input identities. Complete archive, metadata, licence and isolation checks.
2. Make two clean uncached production builds and compare all six files. The
   second build compiles and audits production outputs; it does not repeat the
   unchanged unit/game suites. Seal packaged probes, native controls and logging
   inputs before a later `clean` removes their build outputs.
3. Prepare and seal genuine historical writers/worlds once. Run the known
   recovery/terrain/entity-region failure origins first and count these runs in
   the full matrix. Stop later work after a genuine failure; do not hunt green.
4. Complete the current 68-origin, 136-case, ten-phase history contract, all
   refusals, packaged/development launches, five graphical modes, configuration
   restart, rejected viewer, actual two-client inventory packets, 13 visibility
   checks and 64 respawns. Keep both 6,200 ticks and 310 seconds and the connected
   restart. Keep the real native controls, Eclipse and Java 21 Tooling checks.
5. Independently check complete logs, crashes, images, diagnostics, receipts,
   original-world seals and every published byte. Missing, changed or unreadable
   evidence blocks reuse and publication. Reused proof keeps its original run
   identity; a different commit or event does not turn it into a fresh run.

Unchanged mod-free resource/protocol controls can be reused only with the same
control, SDK, JDK, flags, resources and auditor contract. Changes to those inputs
require new control qualification. Use `tools/fixtures/native12111-forge-only-flat-level.dat`
for the Forge-only respawn control so it starts with Forge's own saved registry.
Changes confined to a test fixture require
the affected proof and tool contracts, not an automatic replay of unrelated
passed groups. A changed production migration path still requires its full
applicable migration coverage.

Two cold-restart consolidations are under review. They remain **disabled**:
recovering near inventory during the first cold near reload, and removing the
empty corpse during the all-wave return. Neither may change the ten-phase
acceptance contract until real disk-equivalence and deliberate
loss/duplication/resurrection/deferred-chunk controls pass on a later authorised
run. Do not shorten waits, remove origins or reduce refusals to save time.

`tools/qualification-policy.json` records the policy. Validate changes to the
hosted guards, workflow definitions and release contracts locally before pushing
or publishing. Keep the accepted files and their original evidence identities.
