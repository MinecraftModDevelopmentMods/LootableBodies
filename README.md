# Lootable Bodies for Minecraft 1.16.5

Your items stay on a corpse when you die. Right-click the body to recover them.

Use Forge 36.2.42 and Java 8. Install `LootableBodies-2.4.7.116051.jar`
in the client and server mods folders. Back up your world before upgrading.

Created by Cyanobacterium (DrCyano), maintained by SkyBlade1978.
Licensed under LGPLv2.1; the original CC0 dedication is included in LICENSE-CC0.

Open Mods, select Lootable Bodies and click Config to change its settings.
Appearance changes apply straight away on your client. World and server rules
take effect after restarting Minecraft or the server.

Existing 1.10.2, 1.11.2, 1.12.2, 1.13.2, 1.14.4 and 1.15.2 corpses are preserved when upgrading,
including bodies in chunks you visit later. Keep the whole world backup,
including its `data` folder. If preservation cannot finish safely, world loading
stops with an explanation in the log. Items from missing mods are retained for
recovery when those mods return.

It cannot recover items an older build already lost. Downgrading an upgraded
world is unsupported.

The existing float-in-fluids and void-platform settings are also available under
Config. Their defaults are unchanged.
