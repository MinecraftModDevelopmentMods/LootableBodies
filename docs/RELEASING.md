# Releasing Lootable Bodies

Changes go to the maintainer's fork first, then to the matching MMD branch by
pull request. Merge after the build, security and wrapper checks pass.

For Minecraft 1.10.2, open **Release Lootable Bodies** in MMD's Actions tab.
Run it from the default branch, enter the complete version from
`gradle.properties`, choose the CurseForge release level and confirm publication.
The workflow selects the `1.10.2` branch for versions ending in `110021`.

The workflow requires green checks on the selected commit, builds and audits
the release bundle, then creates the exact version tag. It publishes the same
files to Maven, CurseForge and GitHub, in that order. Missing files or release
credentials stop the release. Ordinary local builds cannot publish.

Keep the run ID, commit and checksums when investigating a failed release.
Inspect completed upload steps before retrying. Do not start another upload
while a CurseForge file is pending review. An accepted upload does not prove
that the public download is available yet.
