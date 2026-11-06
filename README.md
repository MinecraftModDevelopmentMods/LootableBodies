# Lootable Bodies for Minecraft 1.20.6

Your items stay on a corpse when you die. Right-click the body to recover them.

Use Forge 50.2.14 and Java 21. Install `LootableBodies-2.4.12.120061.jar`
in the client and server mods folders. Back up your world before upgrading.

Created by Cyanobacterium (DrCyano), maintained by SkyBlade1978.
Licensed under LGPLv2.1. The original CC0 dedication is included in LICENSE-CC0.

Open Mods, select Lootable Bodies and click Config to change its settings.
Appearance changes apply straight away on your client. World and server rules
take effect after restarting Minecraft or the server.

Existing 1.10.2, 1.11.2, 1.12.2, 1.13.2, 1.14.4, 1.15.2, 1.16.5, 1.17.1, 1.18.2, 1.19.4 and 1.20.1 corpses are preserved when upgrading,
including bodies in chunks you visit later. Keep the whole world backup,
including its `data` folder. If preservation cannot finish safely, world loading
stops with an explanation in the log. Items from missing mods are retained for
recovery when those mods return.

It cannot recover items an older build already lost. Downgrading an upgraded
world is unsupported.

The existing float-in-fluids and void-platform settings are also available under
Config. Their defaults are unchanged.
