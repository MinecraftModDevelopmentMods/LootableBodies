package cyano.lootable.buildtest;

import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.*;
import net.minecraftforge.fml.client.config.*;
import net.minecraftforge.fml.client.config.GuiConfigEntries.*;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.*;

/** Exercises the actual Forge Config button and widgets without OS input control. */
final class ConfigClientChecks {
    private int stage, frames;
    private GuiModList mods;
    private GuiConfig root, appearance, rules;
    private Future<?> pending;
    private final UUID bodyId = UUID.fromString("00000000-0000-4000-8000-000000000941");
    private File report;

    void tick(Minecraft mc) throws Exception {
        if (++frames < 20) return;
        frames = 0;
        if (report == null) report = new File(mc.mcDataDir, "config-checks.txt");
        if (stage == 0 && mc.currentScreen instanceof GuiMainMenu) {
            mc.gameSettings.pauseOnLostFocus = false; mc.gameSettings.limitFramerate = 30;
            mc.gameSettings.renderDistanceChunks = 4;
            if (Boolean.getBoolean("lootablebodies.configUiReload")) {
                check(LootableBodies.corpseHP == 31 && LootableBodies.corpseDecayTime == 200
                        && LootableBodies.ticksPerItemDecay == 0, "pending_rules_apply_after_process_restart");
                check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag, "appearance_survives_restart");
                finish(mc); return;
            }
            check(LootableBodies.corpseHP == 20 && LootableBodies.ticksPerItemDecay == 6000, "startup_defaults");
            openMods(mc); stage = 1;
        } else if (stage == 1) {
            screenshot(mc, "01-mods-config-button.png");
            openConfig(mc); stage = 2;
        } else if (stage == 2) {
            check(root.configElements.size() == 2, "appearance_and_rules_pages");
            screenshot(mc, "02-config-root.png");
            appearance = category(root, 0); mc.displayGuiScreen(appearance); stage = 3;
        } else if (stage == 3) {
            screenshot(mc, "03-appearance.png");
            toggle(appearance, 0); toggle(appearance, 1);
            click(appearance, 2000); // child Done stages edits; root Escape discards them.
            mc.displayGuiScreen(mods);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag, "leaving_without_done_discards_changes");
            openConfig(mc); appearance = category(root, 0); mc.displayGuiScreen(appearance);
            toggle(appearance, 0); toggle(appearance, 1); click(appearance, 2000); click(root, 2000);
            check(LootableBodies.useLocalSkin && !LootableBodies.displayNameTag, "appearance_done_applies_immediately");
            openConfig(mc); rules = category(root, 1); mc.displayGuiScreen(rules); stage = 4;
        } else if (stage == 4) {
            screenshot(mc, "04-world-rules.png");
            for (IConfigEntry entry : rules.entryList.listEntries)
                check(entry.enabled() && entry.getConfigElement().requiresMcRestart(), "rule_restart_policy_" + entry.getConfigElement().getName());
            IConfigEntry health = rules.entryList.listEntries.get(0);
            text(health, "31"); health.undoChanges();
            check(health.getCurrentValue().toString().equals("20.0"), "undo_restores_health");
            text(health, "31"); health.setToDefault();
            check(health.getCurrentValue().toString().equals("20.0"), "defaults_restore_health");
            text(health, "31"); text(rules.entryList.listEntries.get(2), "0");
            IConfigEntry lifetime = rules.entryList.listEntries.get(8);
            for (String invalid : Arrays.asList("bad", "1:-2", "29826:9:43", "1:2:3:4")) {
                text(lifetime, invalid); lifetime.saveConfigElement();
                check(lifetime.getConfigElement().get().equals("1:00:00"), "invalid_time_not_saved_" + invalid);
            }
            text(lifetime, "0:00:10"); click(rules, 2000); click(root, 2000);
            check(mc.currentScreen instanceof GuiMessageDialog, "restart_dialog_shown");
            check(LootableBodies.corpseHP == 20 && LootableBodies.corpseDecayTime == 72000
                    && LootableBodies.ticksPerItemDecay == 6000, "gameplay_unchanged_until_restart");
            stage = 5;
        } else if (stage == 5) {
            screenshot(mc, "05-restart-notice.png");
            mc.displayGuiScreen(null);
            mc.launchIntegratedServer("config-fixture", "Config fixture",
                    new WorldSettings(4267321, GameType.CREATIVE, false, false, WorldType.FLAT)); stage = 6;
        } else if (stage == 6 && mc.world != null && mc.player != null) {
            pending = mc.getIntegratedServer().addScheduledTask(() -> {
                try {
                    // Freeze only the fixture lifetime while comparing exact saved timer fields.
                    LootableBodies.allowCorpseDecay = false;
                    EntityLootableBody body = RuntimeChecks.fixture(mc.getIntegratedServer().worldServerForDimension(0), bodyId, 8, true);
                    RuntimeChecks.require(body.getAlwaysRenderNameTag(), "Local appearance preference leaked into saved corpse metadata");
                    // Simulate an old corpse whose saved visibility flag was false.
                    body.setAlwaysRenderNameTag(false);
                    RuntimeChecks.saveExpected(mc.getIntegratedServer().worldServerForDimension(0).getSaveHandler().getWorldDirectory(), "config-body", body);
                    net.minecraft.entity.player.EntityPlayerMP player = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
                    player.capabilities.isFlying = true; player.sendPlayerAbilities();
                    player.connection.setPlayerLocation(8.5, 80, 7.5, 0, 0);
                } catch (Exception error) { throw new RuntimeException(error); }
            }); stage = 7;
        } else if (stage == 7 && pending.isDone()) {
            pending.get(); openMods(mc); openConfig(mc);
            rules = category(root, 1); mc.displayGuiScreen(rules); stage = 8;
        } else if (stage == 8) {
            screenshot(mc, "06-rules-disabled-in-world.png");
            for (IConfigEntry entry : rules.entryList.listEntries) check(!entry.enabled(), "rule_disabled_in_world_" + entry.getConfigElement().getName());
            click(rules, 2000); appearance = category(root, 0); mc.displayGuiScreen(appearance);
            for (IConfigEntry entry : appearance.entryList.listEntries) check(entry.enabled(), "appearance_enabled_in_world_" + entry.getConfigElement().getName());
            // Cache a synthetic profile: switching skins needs no external account service.
            net.minecraft.tileentity.TileEntitySkull.setProfileCache(new net.minecraft.server.management.PlayerProfileCache(null,
                    new File(mc.mcDataDir, "synthetic-cache.json")) {
                @Override public com.mojang.authlib.GameProfile getGameProfileForUsername(String name) {
                    com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(bodyId, name);
                    profile.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures", "eyJ0ZXh0dXJlcyI6e319")); return profile;
                }
            });
            toggle(appearance, 0); toggle(appearance, 1); click(appearance, 2000); click(root, 2000);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag, "existing_body_appearance_changes_live");
            // Another mod's event cannot change our settings.
            LootableBodies.getConfiguration().getCategory("options").get("local_texture").set(true);
            MinecraftForge.EVENT_BUS.post(new ConfigChangedEvent.OnConfigChangedEvent("anothermod", null, true, false));
            check(!LootableBodies.useLocalSkin, "other_mod_config_event_ignored");
            LootableBodies.getConfiguration().getCategory("options").get("local_texture").set(false);
            pending = mc.getIntegratedServer().addScheduledTask(() -> {
                try {
                    WorldServer world = mc.getIntegratedServer().worldServerForDimension(0);
                    EntityLootableBody body = RuntimeChecks.find(world, bodyId);
                    RuntimeChecks.verifyExpected(world.getSaveHandler().getWorldDirectory(), "config-body", body);
                    RuntimeChecks.require(LootableBodies.corpseHP == 20 && LootableBodies.ticksPerItemDecay == 6000, "GUI changed server rules");
                    world.saveAllChunks(true, null);
                } catch (Exception error) { throw new RuntimeException(error); }
            }); stage = 9;
        } else if (stage == 9 && pending.isDone()) {
            pending.get();
            EntityLootableBody body = null;
            for (Entity entity : mc.world.loadedEntityList) if (entity.getUniqueID().equals(bodyId)) body = (EntityLootableBody) entity;
            check(body != null && body.getGameProfile() != null && body.getGameProfile().getId() != null, "existing_body_skin_refreshes_without_rename");
            check(!body.getAlwaysRenderNameTag(), "appearance_did_not_rewrite_synced_name_flag");
            mc.player.closeScreen(); mc.world.sendQuittingDisconnectingPacket(); mc.loadWorld(null);
            mc.displayGuiScreen(new GuiMainMenu()); stage = 10;
        } else if (stage == 10 && mc.world == null && !mc.isIntegratedServerRunning()) {
            mc.launchIntegratedServer("config-fixture", "Config fixture", null); stage = 11;
        } else if (stage == 11 && mc.world != null && mc.player != null) {
            pending = mc.getIntegratedServer().addScheduledTask(() -> {
                try {
                    WorldServer world = mc.getIntegratedServer().worldServerForDimension(0); world.getChunkFromChunkCoords(0, 0);
                    EntityLootableBody body = RuntimeChecks.find(world, bodyId);
                    RuntimeChecks.verifyExpected(world.getSaveHandler().getWorldDirectory(), "config-body", body);
                    RuntimeChecks.require(!body.getAlwaysRenderNameTag(), "Saved legacy visibility flag changed on reload");
                    RuntimeChecks.require(body.getMaxHealth() == 20, "Pending GUI health rewrote existing corpse attributes");
                } catch (Exception error) { throw new RuntimeException(error); }
            }); stage = 12;
        } else if (stage == 12 && pending.isDone()) {
            pending.get(); check(true, "corpse_inventory_and_nbt_survive_save_reload"); finish(mc);
        }
    }

    private void openMods(Minecraft mc) throws Exception {
        mods = new GuiModList(new GuiMainMenu()); mc.displayGuiScreen(mods);
        Field list = GuiModList.class.getDeclaredField("mods"); list.setAccessible(true);
        List<?> containers = (List<?>) list.get(mods);
        for (int i = 0; i < containers.size(); i++) if (((ModContainer) containers.get(i)).getModId().equals(LootableBodies.MODID)) mods.selectModIndex(i);
    }
    private void openConfig(Minecraft mc) throws Exception {
        Field field = GuiModList.class.getDeclaredField("configModButton"); field.setAccessible(true);
        GuiButton button = (GuiButton) field.get(mods);
        check(button.enabled && button.visible, "mods_list_config_button_enabled");
        invokeButton(mods, button); root = (GuiConfig) mc.currentScreen;
        check(root.getClass().getName().equals("cyano.lootable.graphics.CorpseConfigGui"), "mods_button_opens_our_screen");
    }
    private GuiConfig category(GuiConfig screen, int index) throws Exception {
        Field field = CategoryEntry.class.getDeclaredField("childScreen"); field.setAccessible(true);
        return (GuiConfig) field.get(screen.entryList.listEntries.get(index));
    }
    private void toggle(GuiConfig screen, int index) { ((BooleanEntry) screen.entryList.listEntries.get(index)).valueButtonPressed(index); }
    private void text(IConfigEntry entry, String value) throws Exception {
        Field field = StringEntry.class.getDeclaredField("textFieldValue"); field.setAccessible(true);
        ((GuiTextField) field.get(entry)).setText(value); entry.keyTyped((char) 0, org.lwjgl.input.Keyboard.KEY_HOME);
    }
    private void click(GuiConfig screen, int id) throws Exception { invokeButton(screen, new GuiButton(id, 0, 0, "fixture")); }
    private void invokeButton(GuiScreen screen, GuiButton button) throws Exception {
        Class<?> type = screen.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) if (Arrays.equals(method.getParameterTypes(), new Class<?>[] {GuiButton.class})) {
                method.setAccessible(true); method.invoke(screen, button); return;
            }
            type = type.getSuperclass();
        }
        throw new AssertionError("No button action");
    }
    private void screenshot(Minecraft mc, String name) { ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer()); }
    private void check(boolean value, String name) throws Exception {
        RuntimeChecks.require(value, name); try (FileWriter writer = new FileWriter(report, true)) { writer.write("PASS " + name + "\n"); }
    }
    private void finish(Minecraft mc) throws Exception {
        new File(mc.mcDataDir, Boolean.getBoolean("lootablebodies.configUiReload") ? "config-restart.pass" : "client.pass").createNewFile();
        System.out.println("LOOTABLE_BODIES_CONFIG_UI_PASS restart=" + Boolean.getBoolean("lootablebodies.configUiReload")); mc.shutdown(); stage = 99;
    }
}
