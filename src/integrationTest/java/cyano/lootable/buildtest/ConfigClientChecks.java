package cyano.lootable.buildtest;
import net.minecraft.client.gui.screens.TitleScreen;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.graphics.CorpseConfigGui;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fmlclient.gui.screen.ModListScreen;
/** Real native Mods button and widgets, without operating the user's desktop. */
final class ConfigClientChecks {
    private int stage,frames,step,tooltipsChecked;private File report;private ModListScreen mods;private CorpseConfigGui root;private Future<?> pending;
    void tooltip(net.minecraftforge.client.event.RenderTooltipEvent.Color event) {
        Screen screen=Minecraft.getInstance().screen;
        if(!(screen instanceof CorpseConfigGui))return;
        int width=0,height=event.getComponents().size()==1?-2:0;
        for(net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent line:event.getComponents()) {
            width=Math.max(width,line.getWidth(event.getFontRenderer()));height+=line.getHeight();
        }
        RuntimeChecks.require(event.getX()>=3 && event.getX()+width+3<=screen.width && event.getY()>=4 && event.getY()+height+4<=screen.height,
                "Native configuration tooltip clipped: "+event.getX()+","+event.getY()+" "+width+"x"+height+" screen="+screen.width+"x"+screen.height);
        tooltipsChecked++;
    }
    void tick(Minecraft mc)throws Exception {
        if(++frames<20)return;frames=0;
        if(report==null)report=new File(mc.gameDirectory,"config-checks.txt");
        if(stage==0 && mc.screen instanceof TitleScreen) {
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit=30;mc.options.renderDistance=4;
            if(Boolean.getBoolean("lootablebodies.configUiReload")) {
                check(LootableBodies.corpseHP==31 && LootableBodies.corpseDecayTime==200 && LootableBodies.ticksPerItemDecay==0,"pending_rules_apply_after_process_restart");
                check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"appearance_survives_restart");new File(mc.gameDirectory,"config-restart.pass").createNewFile();finish(mc);stage=5;return;
            }
            check(LootableBodies.corpseHP==20 && LootableBodies.ticksPerItemDecay==6000,"startup_defaults");openMods(mc);stage=1;
        } else if(stage==1) {
            screenshot(mc,"01-mods-config-button.png");openConfig(mc);stage=2;
        } else if(stage==2) {
            if(step==0){screenshot(mc,"02-config-root.png");click(root,1);step=1;return;}
            if(step==1){screenshot(mc,"03-appearance.png");CorpseConfigGui appearance=(CorpseConfigGui)mc.screen;
            click(appearance,20);click(appearance,21);click(appearance,201);click(root,200);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"child_cancel_discards_appearance");
            openConfig(mc);click(root,1);appearance=(CorpseConfigGui)mc.screen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,201);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"root_cancel_discards_staged_child_changes");
            openConfig(mc);click(root,1);appearance=(CorpseConfigGui)mc.screen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,200);
            check(LootableBodies.useLocalSkin && !LootableBodies.displayNameTag,"appearance_done_applies_immediately");
            openConfig(mc);click(root,2);step=2;return;}
            CorpseConfigGui rules=(CorpseConfigGui)mc.screen;
            if(step==2){screenshot(mc,"04-world-rules-page-one.png");
            text(rules,"corpse_HP","31");click(rules,21);
            check(field(rules,"corpse_HP").getValue().equals("31"),"boolean_toggle_retains_other_pending_text");
            click(rules,5);check(field(rules,"corpse_HP").getValue().equals("20.0"),"defaults_restore_health");
            text(rules,"corpse_HP","NaN");click(rules,200);check(mc.screen==rules,"nonfinite_health_is_not_saved");text(rules,"corpse_HP","31");text(rules,"item_decay_rate","0");
            for(String invalid:Arrays.asList("bad","1:-2","29826:9:43","1:2:3:4")) {
                text(rules,"corpse_decay_time",invalid);click(rules,200);check(mc.screen==rules,"invalid_time_not_saved_"+invalid);
            }
            text(rules,"corpse_decay_time","0:00:10");step=3;return;}
            screenshot(mc,"04-world-rules-page-two.png");click(rules,200);click(root,200);
            check(LootableBodies.corpseHP==20 && LootableBodies.corpseDecayTime==72000 && LootableBodies.ticksPerItemDecay==6000,"gameplay_unchanged_until_restart");
            mc.setScreen(null);FixtureApi.ClientWorlds.create(mc,"config-fixture","Config fixture",net.minecraft.world.level.GameType.CREATIVE,false);stage=3;
        } else if(stage==3 && mc.level!=null && mc.player!=null) {
            pending=mc.getSingleplayerServer().submitAsync(()->{EntityLootableBody body=RuntimeChecks.fixture(mc.getSingleplayerServer().getLevel(net.minecraft.world.level.Level.OVERWORLD),UUID.fromString("00000000-0000-4000-8000-000000000941"),8,true);body.setCustomNameVisible(false);try{RuntimeChecks.saveExpected(mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toFile(),"config-body",body);}catch(Exception failure){throw new RuntimeException(failure);}});stage=4;
        } else if(stage==4 && pending.isDone()) {
            pending.get();openMods(mc);openConfig(mc);check(!button(root,2).active,"world_rules_disabled_while_world_open");stage=6;
        } else if(stage==6) {
            screenshot(mc,"05-world-rules-disabled.png");click(root,1);CorpseConfigGui appearance=(CorpseConfigGui)mc.screen;click(appearance,20);click(appearance,21);click(appearance,200);click(root,200);
            check(!LootableBodies.useLocalSkin && LootableBodies.displayNameTag,"appearance_changes_while_world_open");
            FixtureConnections.disconnect(mc);stage=5;
        } else if(stage==5 && FixtureConnections.stopped(mc)) {
            check(tooltipsChecked>0,"native_tooltip_bounds_checked_at_left_centre_right");finish(mc);
        }
    }
    private void openMods(Minecraft mc)throws Exception {
        mods=new ModListScreen(new TitleScreen());mc.setScreen(mods);List<?> list=(List<?>)get(mods,"mods");
        for(int i=0;i<list.size();i++)if(((net.minecraftforge.forgespi.language.IModInfo)list.get(i)).getModId().equals("lootablebodies")){
            net.minecraftforge.fmlclient.gui.widget.ModListWidget entries=(net.minecraftforge.fmlclient.gui.widget.ModListWidget)get(mods,"modList");
            mods.setSelected(entries.children().get(i));check(button(mods,20).active,"mods_config_button_enabled");return;
        }
        throw new AssertionError("Lootable Bodies missing from Mods list");
    }
    private void openConfig(Minecraft mc)throws Exception {click(mods,20);check(mc.screen instanceof CorpseConfigGui,"mods_button_opens_regular_config");root=(CorpseConfigGui)mc.screen;}
    private static Object get(Object target,String name)throws Exception {for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}
    private static Button button(Screen screen,int id)throws Exception {if(screen instanceof ModListScreen && id==20)return (Button)get(screen,"configButton");for(Object value:screen.children())if(value instanceof CorpseConfigGui.SettingButton && ((CorpseConfigGui.SettingButton)value).id==id)return (CorpseConfigGui.SettingButton)value;throw new AssertionError("Missing GUI button "+id);}
    private static void click(Screen screen,int id)throws Exception {Button button=button(screen,id);RuntimeChecks.require(button.active,"Disabled GUI button "+id);button.onClick(button.x+1,button.y+1);}
    @SuppressWarnings("unchecked") private static EditBox field(CorpseConfigGui screen,String key)throws Exception {
        for(int attempt=0;attempt<10;attempt++){EditBox field=((Map<String,EditBox>)get(screen,"fields")).get(key);if(field!=null)return field;int page=(Integer)get(screen,"page");try{click(screen,4);}catch(AssertionError end){while((Integer)get(screen,"page")>0)click(screen,3);}if(page==(Integer)get(screen,"page") && attempt>0)break;}
        throw new AssertionError("Missing config text field "+key);
    }
    private static void text(CorpseConfigGui screen,String key,String value)throws Exception {field(screen,key).setValue(value);}
    private void check(boolean condition,String label)throws Exception {RuntimeChecks.require(condition,label);try(FileWriter out=new FileWriter(report,true)){out.write("PASS "+label+"\n");}System.out.println("CONFIG_CHECK_PASS "+label);}
    @SuppressWarnings("unchecked") private static void screenshot(Minecraft mc,String name)throws Exception {
        if(mc.screen instanceof CorpseConfigGui) {
            Screen screen=mc.screen;Map<String,Integer> rows=(Map<String,Integer>)get(screen,"rows");
            Collection<Integer> positions=rows.isEmpty()?java.util.Collections.singletonList(100):rows.values();
            for(int y:positions)for(int x:new int[]{screen.width/2-150,screen.width/2,screen.width/2+150})
                screen.render(new com.mojang.blaze3d.vertex.PoseStack(),x,y+5,0);
        }
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->System.out.println(message.getString()));
    }
    private static void finish(Minecraft mc)throws Exception {new File(mc.gameDirectory,"client.pass").createNewFile();System.out.println("LOOTABLE_BODIES_CONFIG_CLIENT_PASS");mc.stop();}
}
