package cyano.lootable.buildtest;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseConfigGui;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;
import net.minecraftforge.fml.client.gui.GuiModList;
/** Real native Mods button and widgets, without operating the user's desktop. */
final class ConfigClientChecks {
    private int stage,frames,step;private File report;private GuiModList mods;private CorpseConfigGui root;private Future<?> pending;
    void tick(Minecraft mc)throws Exception {
        if(++frames<20)return;frames=0;
        if(report==null)report=new File(mc.gameDir,"config-checks.txt");
        if(stage==0 && mc.currentScreen instanceof GuiMainMenu) {
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.limitFramerate=30;mc.gameSettings.renderDistanceChunks=4;
            if(Boolean.getBoolean("lootablebodies.configUiReload")) {
                check(LootableBodies.corpseHP==31 && LootableBodies.corpseDecayTime==200 && LootableBodies.ticksPerItemDecay==0,"pending_rules_apply_after_process_restart");
                check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"appearance_survives_restart");new File(mc.gameDir,"config-restart.pass").createNewFile();finish(mc);stage=5;return;
            }
            check(LootableBodies.corpseHP==20 && LootableBodies.ticksPerItemDecay==6000,"startup_defaults");openMods(mc);stage=1;
        } else if(stage==1) {
            screenshot(mc,"01-mods-config-button.png");openConfig(mc);stage=2;
        } else if(stage==2) {
            if(step==0){screenshot(mc,"02-config-root.png");click(root,1);step=1;return;}
            if(step==1){screenshot(mc,"03-appearance.png");CorpseConfigGui appearance=(CorpseConfigGui)mc.currentScreen;
            click(appearance,20);click(appearance,21);click(appearance,201);click(root,200);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"child_cancel_discards_appearance");
            openConfig(mc);click(root,1);appearance=(CorpseConfigGui)mc.currentScreen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,201);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"root_cancel_discards_staged_child_changes");
            openConfig(mc);click(root,1);appearance=(CorpseConfigGui)mc.currentScreen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,200);
            check(LootableBodies.useLocalSkin && !LootableBodies.displayNameTag,"appearance_done_applies_immediately");
            openConfig(mc);click(root,2);step=2;return;}
            CorpseConfigGui rules=(CorpseConfigGui)mc.currentScreen;
            if(step==2){screenshot(mc,"04-world-rules-page-one.png");
            text(rules,"corpse_HP","31");click(rules,21);
            check(field(rules,"corpse_HP").getText().equals("31"),"boolean_toggle_retains_other_pending_text");
            click(rules,5);check(field(rules,"corpse_HP").getText().equals("20.0"),"defaults_restore_health");
            text(rules,"corpse_HP","NaN");click(rules,200);check(mc.currentScreen==rules,"nonfinite_health_is_not_saved");text(rules,"corpse_HP","31");text(rules,"item_decay_rate","0");
            for(String invalid:Arrays.asList("bad","1:-2","29826:9:43","1:2:3:4")) {
                text(rules,"corpse_decay_time",invalid);click(rules,200);check(mc.currentScreen==rules,"invalid_time_not_saved_"+invalid);
            }
            text(rules,"corpse_decay_time","0:00:10");step=3;return;}
            screenshot(mc,"04-world-rules-page-two.png");click(rules,200);click(root,200);
            check(LootableBodies.corpseHP==20 && LootableBodies.corpseDecayTime==72000 && LootableBodies.ticksPerItemDecay==6000,"gameplay_unchanged_until_restart");
            mc.displayGuiScreen(null);mc.launchIntegratedServer("config-fixture","Config fixture",new WorldSettings(4267321,GameType.CREATIVE,false,false,WorldType.FLAT));stage=3;
        } else if(stage==3 && mc.world!=null && mc.player!=null) {
            pending=mc.getIntegratedServer().addScheduledTask(()->{EntityLootableBody body=RuntimeChecks.fixture(mc.getIntegratedServer().getWorld(net.minecraft.world.dimension.DimensionType.OVERWORLD),UUID.fromString("00000000-0000-4000-8000-000000000941"),8,true);body.setCustomNameVisible(false);try{RuntimeChecks.saveExpected(mc.getIntegratedServer().getWorld(net.minecraft.world.dimension.DimensionType.OVERWORLD).getSaveHandler().getWorldDirectory(),"config-body",body);}catch(Exception failure){throw new RuntimeException(failure);}});stage=4;
        } else if(stage==4 && pending.isDone()) {
            pending.get();openMods(mc);openConfig(mc);check(!button(root,2).enabled,"world_rules_disabled_while_world_open");stage=6;
        } else if(stage==6) {
            screenshot(mc,"05-world-rules-disabled.png");click(root,1);CorpseConfigGui appearance=(CorpseConfigGui)mc.currentScreen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,200);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"appearance_changes_while_world_open");
            FixtureConnections.disconnect(mc);stage=5;
        } else if(stage==5 && mc.world==null && !mc.isIntegratedServerRunning())finish(mc);
    }
    private void openMods(Minecraft mc)throws Exception {
        mods=new GuiModList(new GuiMainMenu());mc.displayGuiScreen(mods);List<?> list=(List<?>)get(mods,"mods");
        for(int i=0;i<list.size();i++)if(((net.minecraftforge.forgespi.language.IModInfo)list.get(i)).getModId().equals("lootablebodies")){mods.selectModIndex(i);net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre(mods,0,0,0F));check(button(mods,20).enabled,"mods_config_button_enabled");return;}
        throw new AssertionError("Lootable Bodies missing from Mods list");
    }
    private void openConfig(Minecraft mc)throws Exception {net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre(mods,0,0,0F));click(mods,20);check(mc.currentScreen instanceof CorpseConfigGui,"mods_button_opens_regular_config");root=(CorpseConfigGui)mc.currentScreen;}
    private static Object get(Object target,String name)throws Exception {for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}
    private static GuiButton button(GuiScreen screen,int id)throws Exception {for(Object value:screen.getChildren())if(value instanceof GuiButton && ((GuiButton)value).id==id)return (GuiButton)value;throw new AssertionError("Missing GUI button "+id);}
    private static void click(GuiScreen screen,int id)throws Exception {GuiButton button=button(screen,id);RuntimeChecks.require(button.enabled,"Disabled GUI button "+id);button.onClick(button.x+1,button.y+1);}
    @SuppressWarnings("unchecked") private static GuiTextField field(CorpseConfigGui screen,String key)throws Exception {
        for(int attempt=0;attempt<10;attempt++){GuiTextField field=((Map<String,GuiTextField>)get(screen,"fields")).get(key);if(field!=null)return field;int page=(Integer)get(screen,"page");try{click(screen,4);}catch(AssertionError end){while((Integer)get(screen,"page")>0)click(screen,3);}if(page==(Integer)get(screen,"page") && attempt>0)break;}
        throw new AssertionError("Missing config text field "+key);
    }
    private static void text(CorpseConfigGui screen,String key,String value)throws Exception {field(screen,key).setText(value);}
    private void check(boolean condition,String label)throws Exception {RuntimeChecks.require(condition,label);try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+label+"\n");}System.out.println("CONFIG_CHECK_PASS "+label);}
    private static void screenshot(Minecraft mc,String name){ScreenShotHelper.saveScreenshot(mc.gameDir,name,mc.mainWindow.getFramebufferWidth(),mc.mainWindow.getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));}
    private static void finish(Minecraft mc)throws Exception {new File(mc.gameDir,"client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_CONFIG_CLIENT_PASS");mc.shutdown();}
}
