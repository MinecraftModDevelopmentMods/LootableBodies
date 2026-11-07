package cyano.lootable.graphics;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.math.Axis;
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
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Corpse renderer, originally created by Chris on 4/10/2016. */
@OnlyIn(Dist.CLIENT)
public class CorpseRenderer extends LivingEntityRenderer<EntityLootableBody, PlayerModel<EntityLootableBody>> {
    private static final ResourceLocation localSkin = ResourceLocation.fromNamespaceAndPath(LootableBodies.MODID,
            "textures/entity/corpse/corpse.png");
    protected final PlayerModel<EntityLootableBody> thickArmsModel;
    protected final PlayerModel<EntityLootableBody> thinArmsModel;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context, new FrozenPlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM),true), 0.5F);
        thinArmsModel = model;
        thickArmsModel = new FrozenPlayerModel(context.bakeLayer(ModelLayers.PLAYER),false);
        addLayer(new HumanoidArmorLayer<>(this,
                new FrozenArmorModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new FrozenArmorModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),context.getModelManager()));
        addLayer(new ItemInHandLayer<>(this,context.getItemInHandRenderer()));
        addLayer(new ArrowLayer<>(context,this));
        addLayer(new CustomHeadLayer<>(this,context.getModelSet(),context.getItemInHandRenderer()));
    }

    public PlayerModel<EntityLootableBody> getMainModel() { return getModel(); }
    public void setModel(boolean thinArms) { model = thinArms ? thinArmsModel : thickArmsModel; }

    @Override public ResourceLocation getTextureLocation(EntityLootableBody entity) {
        if (LootableBodies.useLocalSkin) return localSkin;
        return getSkin(entity.getGameProfile());
    }

    public static ResourceLocation getSkin(GameProfile profile) {
        PlayerSkin skin=playerSkin(profile);
        return skin==null?DefaultPlayerSkin.getDefaultTexture():skin.texture();
    }

    private static PlayerSkin playerSkin(GameProfile profile) {
        if (!cyano.lootable.network.CorpseProfiles.isResolved(profile)) return null;
        try {
            return Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
        } catch (RuntimeException unavailable) {
            return DefaultPlayerSkin.get(profile);
        }
    }

    @Override public void render(EntityLootableBody entity, float yaw, float partialTicks,
            PoseStack matrices, MultiBufferSource buffers, int light) {
        PlayerSkin skin=LootableBodies.useLocalSkin?null:playerSkin(entity.getGameProfile());
        setModel(skin!=null && skin.model()==PlayerSkin.Model.SLIM);
        try {
            super.render(entity, 0F, partialTicks, matrices, buffers, light);
        } finally {
            setModel(true);
        }
    }

    @Override protected void setupRotations(EntityLootableBody entity, PoseStack matrices,
            float age, float yaw, float partialTicks, float scale) {
        matrices.mulPose(Axis.XP.rotationDegrees(90F));
        matrices.mulPose(Axis.ZP.rotationDegrees(entity.getRotation()));
        matrices.translate(0D, -0.85D, -0.125D);
        super.setupRotations(entity, matrices, 0F, 0F, partialTicks, scale);
    }

    @Override protected float getAttackAnim(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected float getBob(EntityLootableBody entity, float partialTicks) { return 0F; }
    @Override protected boolean shouldShowName(EntityLootableBody entity) {
        return LootableBodies.displayNameTag && super.shouldShowName(entity);
    }
    @Override protected void renderNameTag(EntityLootableBody entity, net.minecraft.network.chat.Component name, PoseStack matrices,
            MultiBufferSource buffers, int light, float partialTicks) {
        if (LootableBodies.displayNameTag) super.renderNameTag(entity, name, matrices, buffers, light, partialTicks);
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
