# Releasing

Gameplay, graphical clients and world migration checks run locally before
publishing. Full Java/Python suites run locally. GitHub Actions runs production builds,
archive checks, security scans and small workflow/wrapper contracts. It does not install a separate game runtime or run the gameplay suite.

1. Qualify the packaged release locally, including the supported historical
   worlds, refusal cases, client/server packets, graphics, restart and IDE checks.
   Keep complete logs, diagnostics, source-world checksums and reviewed images.
   Record manual acceptance separately. Use two clean builds with the build cache
   disabled and compare all six release files.
2. Freeze the release files and evidence. Existing proof can be reused when its
   actual artifacts, runtime, fixtures and relevant code are unchanged. Record
   that reuse explicitly, and test changed inputs before accepting them. Keep
   failed runs; do not replace them with later results.
3. Review the diff and merge after local qualification and hosted build, archive
   and security checks pass. Recheck the exact merged source and all six file hashes. The independent
   artifact audit checks the uploaded files without rebuilding them.
4. Rehash the retained local evidence and supply its receipt to the guarded
   release workflow. For 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.20.6, 1.21.1 and 1.21.11 the receipt binds the exact source commit, version,
   six file hashes, evidence hash and manual acceptance. Publish the files already
   checked by CI, then independently download and compare the public GitHub files
   and CurseForge jar. An accepted upload alone does not complete the release.

Local evidence and test output stay outside the repository and release assets.
