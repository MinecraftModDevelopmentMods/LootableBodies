package cyano.lootable.buildtest;
import net.minecraft.client.gui.screen.MainMenuScreen;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.*;

final class VisibilitySkinClientChecks {
    private int stage,frames;
    private Field skinField;
    private SkinManager original;
    void tick(Minecraft mc) throws Exception {
        if(stage==0 && mc.currentScreen instanceof MainMenuScreen) {
            mc.gameSettings.pauseOnLostFocus=false;mc.gameSettings.framerateLimit=30;mc.gameSettings.renderDistanceChunks=4;
            FixtureApi.ClientWorlds.create(mc,"skin-fixture","Skin fixture",net.minecraft.world.GameType.SURVIVAL,true);stage=1;
        } else if(stage==1 && mc.world!=null && mc.player!=null && ++frames>60) {
            VisibilitySkinChecks.run(mc);
            for(Field field:Minecraft.class.getDeclaredFields())if(field.getType()==SkinManager.class){skinField=field;skinField.setAccessible(true);}
            original=(SkinManager)skinField.get(mc);
            skinField.set(mc,new SkinManager(mc.getTextureManager(),new File(mc.gameDir,"skin-fixture"),mc.getSessionService()) {
                @Override public Map<MinecraftProfileTexture.Type,MinecraftProfileTexture> loadSkinFromCache(GameProfile p){throw new IllegalStateException("injected render skin cache failure");}
            });
            mc.displayGuiScreen(new Panel(mc));stage=2;frames=0;
        } else if(stage==2 && ++frames>30) {
            ScreenShotHelper.saveScreenshot(mc.gameDir,"skin-fallback-models.png",mc.getMainWindow().getFramebufferWidth(),mc.getMainWindow().getFramebufferHeight(),mc.getFramebuffer(),message->System.out.println(message.getString()));
            skinField.set(mc,original);new File(mc.gameDir,"client.pass").createNewFile();
            System.out.println("LOOTABLE_BODIES_VISIBILITY_SKIN_PASS");mc.shutdown();stage=3;
        }
    }
    private static final class Panel extends Screen {
        private final EntityLootableBody[] bodies=new EntityLootableBody[3];
        Panel(Minecraft mc){super(new net.minecraft.util.text.StringTextComponent("Skin visibility fixture"));for(int i=0;i<3;i++){bodies[i]=new EntityLootableBody(mc.world);bodies[i].setGameProfile(new GameProfile(new UUID(0,512+i),"VisibilityModel"));}}
        @Override public void render(com.mojang.blaze3d.matrix.MatrixStack matrices,int mouseX,int mouseY,float partial) {
            fill(matrices,0,0,width,height,0xff304050);
            String[] names={"Failed skin: wide arms","Failed skin: slim arms","Local texture"};boolean local=LootableBodies.useLocalSkin;
            try {for(int i=0;i<3;i++) {
                LootableBodies.useLocalSkin=i==2;int x=(2*i+1)*width/6;
                drawCenteredString(matrices,font,names[i],x,35,0xffffff);
                InventoryScreen.drawEntityOnScreen(x,height*2/3,65,20,-20,bodies[i]);
            }} finally {LootableBodies.useLocalSkin=local;}
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
