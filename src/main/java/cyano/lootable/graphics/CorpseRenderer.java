package cyano.lootable.graphics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.math.Vector3f;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Corpse renderer, originally created by Chris on 4/10/2016. */
@OnlyIn(Dist.CLIENT)
public class CorpseRenderer extends LivingEntityRenderer<EntityLootableBody, PlayerModel<EntityLootableBody>> {
    private static final ResourceLocation localSkin = new ResourceLocation(LootableBodies.MODID,
            "textures/entity/corpse/corpse.png");
    protected final PlayerModel<EntityLootableBody> thickArmsModel;
    protected final PlayerModel<EntityLootableBody> thinArmsModel;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context, new FrozenPlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM),true), 0.5F);
        thinArmsModel = model;
        thickArmsModel = new FrozenPlayerModel(context.bakeLayer(ModelLayers.PLAYER),false);
        addLayer(new HumanoidArmorLayer<>(this,
                new FrozenArmorModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new FrozenArmorModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR))));
        addLayer(new ItemInHandLayer<>(this));
        addLayer(new ArrowLayer<>(context,this));
        addLayer(new CustomHeadLayer<>(this,context.getModelSet()));
    }

    public PlayerModel<EntityLootableBody> getMainModel() { return getModel(); }
    public void setModel(boolean thinArms) { model = thinArms ? thinArmsModel : thickArmsModel; }

    @Override public ResourceLocation getTextureLocation(EntityLootableBody entity) {
        if (LootableBodies.useLocalSkin) return localSkin;
        GameProfile profile = entity.getGameProfile();
        return profile != null && profile.getId() != null ? getSkin(profile) : DefaultPlayerSkin.getDefaultSkin();
    }

    public static ResourceLocation getSkin(GameProfile profile) {
        ResourceLocation fallback = profile == null || profile.getId() == null
                ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(profile.getId());
        if (profile == null || profile.getId() == null) return fallback;
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = minecraft.getSkinManager().getInsecureSkinInformation(profile);
            return textures.containsKey(MinecraftProfileTexture.Type.SKIN)
                    ? minecraft.getSkinManager().registerTexture(textures.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN)
                    : fallback;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    @Override public void render(EntityLootableBody entity, float yaw, float partialTicks,
            PoseStack matrices, MultiBufferSource buffers, int light) {
        setModel(entity.useThinArms());
        try {
            super.render(entity, 0F, partialTicks, matrices, buffers, light);
        } finally {
            setModel(true);
        }
    }

    @Override protected void setupRotations(EntityLootableBody entity, PoseStack matrices,
            float age, float yaw, float partialTicks) {
        matrices.mulPose(Vector3f.XP.rotationDegrees(90F));
        matrices.mulPose(Vector3f.ZP.rotationDegrees(entity.getRotation()));
        matrices.translate(0D, -0.85D, -0.125D);
        super.setupRotations(entity, matrices, 0F, 0F, partialTicks);
    }

    @Override protected float getAttackAnim(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected float getBob(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected boolean shouldShowName(EntityLootableBody entity) {
        return LootableBodies.displayNameTag && super.shouldShowName(entity);
    }
    @Override protected void renderNameTag(EntityLootableBody entity, net.minecraft.network.chat.Component name, PoseStack matrices,
            MultiBufferSource buffers, int light) {
        if (LootableBodies.displayNameTag) super.renderNameTag(entity, name, matrices, buffers, light);
    }

    private static final class FrozenPlayerModel extends PlayerModel<EntityLootableBody> {
        FrozenPlayerModel(ModelPart root,boolean thin) { super(root, thin); }
        @Override public void prepareMobModel(EntityLootableBody body, float swing, float amount, float partial) {
            super.prepareMobModel(body, 0F, 0F, 0F);
        }
        @Override public void setupAnim(EntityLootableBody body, float swing, float amount,
                float age, float yaw, float pitch) {
            super.setupAnim(body, 0F, 0F, 0F, 0F, 0F);
        }
    }
    private static final class FrozenArmorModel extends HumanoidModel<EntityLootableBody> {
        FrozenArmorModel(ModelPart root) { super(root); }
        @Override public void prepareMobModel(EntityLootableBody body, float swing, float amount, float partial) {
            super.prepareMobModel(body, 0F, 0F, 0F);
        }
        @Override public void setupAnim(EntityLootableBody body, float swing, float amount,
                float age, float yaw, float pitch) {
            super.setupAnim(body, 0F, 0F, 0F, 0F, 0F);
        }
    }
}
