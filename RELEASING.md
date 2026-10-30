# Releasing

Gameplay, graphical clients and world migration checks run locally before
publishing. GitHub Actions runs the unit tests, build, archive checks and security
checks. It does not install a separate game runtime or run the gameplay suite.

1. Qualify the packaged release locally, including the supported historical
   worlds, refusal cases, client/server packets, graphics, restart and IDE checks.
   Keep complete logs, diagnostics, source-world checksums and reviewed images.
   Record manual acceptance separately. Use two clean builds with the build cache
   disabled and compare all six release files.
2. Freeze the release files and evidence. Existing proof can be reused when its
   actual artifacts, runtime, fixtures and relevant code are unchanged. Record
   that reuse explicitly, and test changed inputs before accepting them. Keep
   failed runs; do not replace them with later results.
3. Review the diff and merge after the unit, build, archive and security checks
   pass. Recheck the exact merged source and all six file hashes. The independent
   artifact audit checks the uploaded files without rebuilding them.
4. Rehash the retained local evidence and supply its receipt to the guarded
   release workflow. For 1.17.1 the receipt binds the exact source commit, version,
   six file hashes, evidence hash and manual acceptance. Publish the files already
   checked by CI, then independently download and compare the public GitHub files
   and CurseForge jar. An accepted upload alone does not complete the release.

Local evidence and test output stay outside the repository and release assets.
