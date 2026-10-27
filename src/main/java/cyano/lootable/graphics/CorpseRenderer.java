package cyano.lootable.graphics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.matrix.MatrixStack;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.Vector3f;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.LivingRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BipedArmorLayer;
import net.minecraft.client.renderer.entity.layers.HeadLayer;
import net.minecraft.client.renderer.entity.layers.HeldItemLayer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Corpse renderer, originally created by Chris on 4/10/2016. */
@OnlyIn(Dist.CLIENT)
public class CorpseRenderer extends LivingRenderer<EntityLootableBody, PlayerModel<EntityLootableBody>> {
    private static final ResourceLocation localSkin = new ResourceLocation(LootableBodies.MODID,
            "textures/entity/corpse/corpse.png");
    protected final PlayerModel<EntityLootableBody> thickArmsModel;
    protected final PlayerModel<EntityLootableBody> thinArmsModel;

    public CorpseRenderer(EntityRendererManager manager) {
        super(manager, new FrozenPlayerModel(true), 0.5F);
        thinArmsModel = entityModel;
        thickArmsModel = new FrozenPlayerModel(false);
        addLayer(new BipedArmorLayer<>(this, new FrozenArmorModel(0.5F), new FrozenArmorModel(1F)));
        addLayer(new HeldItemLayer<>(this));
        addLayer(new ArrowLayer<>(this));
        addLayer(new HeadLayer<>(this));
    }

    public PlayerModel<EntityLootableBody> getMainModel() { return getEntityModel(); }
    public void setModel(boolean thinArms) { entityModel = thinArms ? thinArmsModel : thickArmsModel; }

    @Override public ResourceLocation getEntityTexture(EntityLootableBody entity) {
        if (LootableBodies.useLocalSkin) return localSkin;
        GameProfile profile = entity.getGameProfile();
        return profile != null && profile.getId() != null ? getSkin(profile) : DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    public static ResourceLocation getSkin(GameProfile profile) {
        ResourceLocation fallback = profile == null || profile.getId() == null
                ? DefaultPlayerSkin.getDefaultSkinLegacy() : DefaultPlayerSkin.getDefaultSkin(profile.getId());
        if (profile == null || profile.getId() == null) return fallback;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = minecraft.getSkinManager().loadSkinFromCache(profile);
            return textures.containsKey(MinecraftProfileTexture.Type.SKIN)
                    ? minecraft.getSkinManager().loadSkin(textures.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN)
                    : fallback;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    @Override public void render(EntityLootableBody entity, float yaw, float partialTicks,
            MatrixStack matrices, IRenderTypeBuffer buffers, int light) {
        setModel(entity.useThinArms());
        try {
            super.render(entity, 0F, partialTicks, matrices, buffers, light);
        } finally {
            setModel(true);
        }
    }

    @Override protected void applyRotations(EntityLootableBody entity, MatrixStack matrices,
            float age, float yaw, float partialTicks) {
        matrices.rotate(Vector3f.XP.rotationDegrees(90F));
        matrices.rotate(Vector3f.ZP.rotationDegrees(entity.getRotation()));
        matrices.translate(0D, -0.85D, -0.125D);
        super.applyRotations(entity, matrices, 0F, 0F, partialTicks);
    }

    @Override protected float getSwingProgress(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected float handleRotationFloat(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected boolean canRenderName(EntityLootableBody entity) {
        return LootableBodies.displayNameTag && super.canRenderName(entity);
    }
    @Override protected void renderName(EntityLootableBody entity, String name, MatrixStack matrices,
            IRenderTypeBuffer buffers, int light) {
        if (LootableBodies.displayNameTag) super.renderName(entity, name, matrices, buffers, light);
    }

    private static final class FrozenPlayerModel extends PlayerModel<EntityLootableBody> {
        FrozenPlayerModel(boolean thin) { super(0F, thin); }
        @Override public void setLivingAnimations(EntityLootableBody body, float swing, float amount, float partial) {
            super.setLivingAnimations(body, 0F, 0F, 0F);
        }
        @Override public void setRotationAngles(EntityLootableBody body, float swing, float amount,
                float age, float yaw, float pitch) {
            super.setRotationAngles(body, 0F, 0F, 0F, 0F, 0F);
        }
    }
    private static final class FrozenArmorModel extends BipedModel<EntityLootableBody> {
        FrozenArmorModel(float scale) { super(scale); }
        @Override public void setLivingAnimations(EntityLootableBody body, float swing, float amount, float partial) {
            super.setLivingAnimations(body, 0F, 0F, 0F);
        }
        @Override public void setRotationAngles(EntityLootableBody body, float swing, float amount,
                float age, float yaw, float pitch) {
            super.setRotationAngles(body, 0F, 0F, 0F, 0F, 0F);
        }
    }
}
