package cyano.lootable.buildtest;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;

final class VisibilitySkinClientChecks {
    private int stage,frames;
    private Field skinField;
    private SkinManager original;
    void tick(Minecraft mc) throws Exception {
        if(stage==0 && mc.screen instanceof TitleScreen) {
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.options.renderDistance().set(4);
            FixtureApi.ClientWorlds.create(mc,"skin-fixture","Skin fixture",net.minecraft.world.level.GameType.SURVIVAL,true);stage=1;
        } else if(stage==1 && mc.level!=null && mc.player!=null && ++frames>60) {
            VisibilitySkinChecks.run(mc);
            for(Field field:Minecraft.class.getDeclaredFields())if(field.getType()==SkinManager.class){skinField=field;skinField.setAccessible(true);}
            original=(SkinManager)skinField.get(mc);
            skinField.set(mc,new SkinManager(new File(mc.gameDirectory,"skin-fixture").toPath(),mc.services(),
                    new net.minecraft.client.renderer.texture.SkinTextureDownloader(mc.getProxy(),mc.getTextureManager(),mc),mc) {
                @Override public java.util.function.Supplier<net.minecraft.world.entity.player.PlayerSkin> createLookup(GameProfile p, boolean secure){throw new IllegalStateException("injected render skin cache failure");}
            });
            Panel panel=new Panel(mc);
            panel.verifyExtractedTextures(mc);
            mc.setScreen(panel);stage=2;frames=0;
        } else if(stage==2 && ++frames>30) {
            Panel panel=(Panel)mc.screen;
            String name=new String[]{"skin-fallback-wide.png","skin-fallback-slim.png","skin-local.png"}[panel.preview];
            Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,message->System.out.println(message.getString()));
            if(panel.preview+1<panel.bodies.length) {panel.preview++;frames=0;return;}
            skinField.set(mc,original);new File(mc.gameDirectory,"client.pass").createNewFile();
            System.out.println("LOOTABLE_BODIES_VISIBILITY_SKIN_PASS");mc.stop();stage=3;
        }
    }
    private static final class Panel extends Screen {
        private final EntityLootableBody[] bodies=new EntityLootableBody[3];
        private int preview;
        Panel(Minecraft mc) {
            super(net.minecraft.network.chat.Component.literal("Skin visibility fixture"));
            for(int i=0;i<bodies.length;i++) bodies[i]=new EntityLootableBody(mc.level);
            for(int i=0;i<2;i++) {
                var model=i==0?net.minecraft.world.entity.player.PlayerModelType.WIDE:net.minecraft.world.entity.player.PlayerModelType.SLIM;
                GameProfile profile=java.util.stream.LongStream.range(512,640).mapToObj(n->new GameProfile(new UUID(0,n),"VisibilityModel"))
                        .filter(candidate->net.minecraft.client.resources.DefaultPlayerSkin.get(candidate).model()==model)
                        .findFirst().orElseThrow();
                bodies[i].setGameProfile(profile);
            }
            bodies[2].setGameProfile(bodies[0].getGameProfile());
        }

        void verifyExtractedTextures(Minecraft mc) throws java.io.IOException {
            var renderer=(cyano.lootable.graphics.CorpseRenderer)mc.getEntityRenderDispatcher().getRenderer(bodies[0]);
            var wide=renderer.createRenderState();
            var slim=renderer.createRenderState();
            var local=renderer.createRenderState();
            boolean configured=LootableBodies.useLocalSkin;
            try {
                LootableBodies.useLocalSkin=false;
                renderer.extractRenderState(bodies[0],wide,0);
                renderer.extractRenderState(bodies[1],slim,0);
                LootableBodies.useLocalSkin=true;
                renderer.extractRenderState(bodies[2],local,0);
            } finally {LootableBodies.useLocalSkin=configured;}
            RuntimeChecks.require(wide.skin.model()==net.minecraft.world.entity.player.PlayerModelType.WIDE &&
                    slim.skin.model()==net.minecraft.world.entity.player.PlayerModelType.SLIM,"Failed skins did not keep both native arm models");
            RuntimeChecks.require(!renderer.getTextureLocation(wide).equals(renderer.getTextureLocation(slim)) &&
                    renderer.getTextureLocation(local).equals(net.minecraft.resources.Identifier.parse("lootablebodies:textures/entity/corpse/corpse.png")),
                    "Queued corpse renders lost their distinct textures");
            java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("skin-checks.txt"),
                    "PASS extracted_state_preserves_distinct_wide_slim_and_local_textures\n",
                    java.nio.file.StandardOpenOption.APPEND);
        }
        @Override public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial) {
            graphics.fill(0,0,width,height,0xff304050);
            String[] names={"Failed skin: wide arms","Failed skin: slim arms","Local texture"};boolean local=LootableBodies.useLocalSkin;
            // The native picture-in-picture renderer reuses one texture per
            // render-state type. Capture one body per frame to inspect each skin.
            try {
                LootableBodies.useLocalSkin=preview==2;
                int x=width/2;
                graphics.centeredText(font,names[preview],x,35,0xffffffff);
                InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,x-75,65,x+75,height-40,65,0.0625F,x+20,height*2/3-20,bodies[preview]);
            } finally {LootableBodies.useLocalSkin=local;}
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
