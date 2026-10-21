# Versions

Maintained releases use `Major.Minor.Bug.Target`, matching the MMD convention.
The first three numbers describe the functional release. Increase Major for a
breaking new generation, Minor for a substantial feature and Bug for a fix or
small improvement. Reset the numbers to the right when Major or Minor changes.

Allocate functional numbers across Lootable Bodies as a whole. Equivalent ports
may share a functional version; unrelated fixes must use a new number. A higher
version on another Minecraft branch does not imply that all fixes apply there.
Release notes identify the changes actually included. Historical versions retain
their original meaning.

Target encodes the Minecraft major number, two-digit minor number, two-digit
patch number and a loader digit: `1` for Forge, `2` for NeoForge. A missing
Minecraft patch is zero. Minecraft 1.10.2 Forge therefore has target `110021`.
This is a four-component MMD version, rather than strict three-component SemVer.

This branch uses `2.4.1.110021`. It follows the historical 2.4.0 allocation and
contains the maintained 1.10.2 inventory and saved-data fixes described in the
[changelog](../CHANGELOG.txt). Other Minecraft targets remain separate.

The stable artifact name is `LootableBodies`; main, sources and Javadoc jars
share the complete version. Maven coordinates are
`zone.moddev.mc.lootablebodies:LootableBodies:2.4.1.110021`.
These coordinates do not change the Java package `cyano.lootable`, mod ID
`lootablebodies`, resource namespace or saved corpse identity.

Ordinary builds prepare and validate the jars and Maven metadata without
uploading files. Releases use the confirmed MMD workflow described in
[releasing](RELEASING.md).
