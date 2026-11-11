package cyano.lootable.graphics;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.network.CorpseProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/** Corpse renderer, originally created by Chris on 4/10/2016. */
public class CorpseRenderer extends LivingEntityRenderer<EntityLootableBody, AvatarRenderState, PlayerModel> {
    private static final Identifier LOCAL_SKIN = Identifier.fromNamespaceAndPath(LootableBodies.MODID,
            "textures/entity/corpse/corpse.png");

    private static final class CorpseRenderState extends AvatarRenderState {
        private boolean localSkin;
    }

    private final PlayerModel thickArmsModel;
    private final PlayerModel thinArmsModel;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
        thinArmsModel = model;
        thickArmsModel = new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false);
        addLayer(new HumanoidArmorLayer<>(this, ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR,
                context.getModelSet(), part -> new PlayerModel(part, false)), context.getEquipmentRenderer()));
        addLayer(new ItemInHandLayer<>(this));
        addLayer(new ArrowLayer<>(this, context));
        addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getPlayerSkinRenderCache()));
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new CorpseRenderState();
    }

    @Override
    public void extractRenderState(EntityLootableBody body, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(body, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(body, state, partialTicks, itemModelResolver);
        CorpseRenderState corpseState = (CorpseRenderState) state;
        corpseState.localSkin = LootableBodies.useLocalSkin;
        state.skin = corpseState.localSkin ? DefaultPlayerSkin.getDefaultSkin() : playerSkin(body.getGameProfile());
        state.id = body.getId();
        state.arrowCount = body.getArrowCount();
        state.bodyRot = body.getRotation();
        state.xRot = 0F;
        state.yRot = 0F;
        state.walkAnimationPos = 0F;
        state.walkAnimationSpeed = 0F;
        state.ageInTicks = 0F;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return ((CorpseRenderState) state).localSkin ? LOCAL_SKIN : state.skin.body().texturePath();
    }

    public static Identifier getSkin(GameProfile profile) {
        return playerSkin(profile).body().texturePath();
    }

    private static PlayerSkin playerSkin(GameProfile profile) {
        if (!CorpseProfiles.isResolved(profile)) return DefaultPlayerSkin.getDefaultSkin();
        try {
            return Minecraft.getInstance().getSkinManager().createLookup(profile, false).get();
        } catch (RuntimeException unavailable) {
            return DefaultPlayerSkin.get(profile);
        }
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack matrices, SubmitNodeCollector collector, CameraRenderState camera) {
        model = !((CorpseRenderState) state).localSkin && state.skin.model() == PlayerModelType.SLIM ? thinArmsModel : thickArmsModel;
        try {
            super.submit(state, matrices, collector, camera);
        } finally {
            model = thinArmsModel;
        }
    }

    @Override
    protected void setupRotations(AvatarRenderState state, PoseStack matrices, float bodyYaw, float scale) {
        matrices.mulPose(Axis.XP.rotationDegrees(90F));
        matrices.mulPose(Axis.ZP.rotationDegrees(state.bodyRot));
        matrices.translate(0D, -0.85D, -0.125D);
        super.setupRotations(state, matrices, 0F, scale);
    }

    @Override
    protected boolean shouldShowName(EntityLootableBody body, double distanceSquared) {
        return LootableBodies.displayNameTag && super.shouldShowName(body, distanceSquared);
    }

    @Override
    protected void submitNameDisplay(AvatarRenderState state, PoseStack matrices,
            SubmitNodeCollector collector, CameraRenderState camera) {
        if (LootableBodies.displayNameTag) super.submitNameDisplay(state, matrices, collector, camera);
    }
}
