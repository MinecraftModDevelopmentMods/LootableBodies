# DrCyano's Lootable Bodies
A death-chest mod for Minecraft

## Requirements
This version is for Minecraft 1.10.2, Forge 12.18.3.2511 and Java 8.

## Installing
Install Forge, then place `LootableBodies-2.4.1.110021.jar` in the
`mods` folder on the client and server. Keep a backup before changing an
existing modpack or world.

When a player dies, a corpse holds their dropped items. Right-click the corpse
to recover them. The configuration file controls corpse damage, decay, skins
and the extra bones and rotten flesh.

Open **Mods**, select **DrCyano's Lootable Bodies**, then choose **Config**.
Appearance settings apply locally when you save with Done. World/server rules
require a full Minecraft restart and cannot be edited while a world is open.
They change this installation's file, not a remote server's rules. Dedicated
server owners should edit their server's configuration file and restart it.

If a corpse cannot spawn, the player's ordinary item drops remain available.
If a corpse cannot release an item, it keeps that item for recovery or a later
retry. Inventory changes are saved with the corpse's chunk.
Corpses stay in place while nearby terrain is unloaded.
If a player skin cannot be loaded, the corpse uses a default skin.

Unreadable item records stay with the corpse. Restore missing mods before
reloading to retry recovery. An apparently empty corpse can still hold saved
item data. Keep using this build when saving those corpses; older builds do not
retain its recovery records.

Damaged corpse records keep recoverable items and the original data for repair.
Existing corpse slots and configuration defaults are preserved.

## Credits and licence

Created by Cyanobacterium (DrCyano, also known as Synechocystis).
Maintained by [SkyBlade1978](https://github.com/SkyBlade1978) and Minecraft Mod Development.
This maintained release uses [LGPLv2.1](LICENSE). The original
[CC0 dedication](LICENSE-CC0) and [attribution](NOTICE) are retained.

Version `2.4.1.110021` is the maintained Minecraft 1.10.2 Forge release.
See [changes](CHANGELOG.txt) and the [versioning policy](docs/VERSIONS.md).

