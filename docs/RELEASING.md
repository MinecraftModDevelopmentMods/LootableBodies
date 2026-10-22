# Releasing Lootable Bodies

Push changes to the maintainer's fork, then open a pull request against MMD's
matching Minecraft branch. Merge after the build, world-upgrade, security and
wrapper checks pass.

Run **Release Lootable Bodies** from MMD's default branch. Enter the complete
version from `gradle.properties`, choose the CurseForge release level and confirm
publication. Versions ending in `111021` select `1.11.2-dev`; versions ending in
`110021` select `1.10.2`.

The workflow checks the exact commit, builds and audits one release bundle,
then publishes those same files to Maven, CurseForge and GitHub. Ordinary local
builds cannot publish.

Keep the run ID, commit and checksums when investigating a failed release.
Inspect completed upload steps before retrying. Do not upload again while a
CurseForge file is pending review.
