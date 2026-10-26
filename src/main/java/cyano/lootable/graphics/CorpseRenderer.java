package cyano.lootable.graphics;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import cyano.lootable.LootableBodies;
import cyano.lootable.entities.EntityLootableBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GLX;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.LivingRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.PigZombieRenderer;
import net.minecraft.client.renderer.entity.PlayerRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BipedArmorLayer;
import net.minecraft.client.renderer.entity.layers.HeadLayer;
import net.minecraft.client.renderer.entity.layers.HeldItemLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import cyano.lootable.ModLog;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.Level;

import java.util.Map;

/**
 * Created by Chris on 4/10/2016.
 */


@OnlyIn(Dist.CLIENT)
public class CorpseRenderer extends LivingRenderer<EntityLootableBody,PlayerModel<EntityLootableBody>> {

	private static final ResourceLocation localSkin = new ResourceLocation(LootableBodies.MODID+":textures/entity/corpse/corpse.png");

	protected final PlayerModel<EntityLootableBody> thickArmsModel;
	protected final PlayerModel<EntityLootableBody> thinArmsModel;

	public CorpseRenderer(EntityRendererManager renderManagerIn) {
		super(renderManagerIn,  new PlayerModel(0.0F, true), 0.5F);
		thinArmsModel = (PlayerModel)this.entityModel;
		thickArmsModel =  new PlayerModel(0.0F, false);
		//
		this.addLayer(new BipedArmorLayer<>(this,new BipedModel<>(0.5F),new BipedModel<>(1.0F)));
		this.addLayer(new HeldItemLayer(this));
		this.addLayer(new ArrowLayer(this));
		this.addLayer(new HeadLayer<>(this));
		PlayerRenderer k;
		PigZombieRenderer j;
	}

	public PlayerModel getMainModel()
	{
		return (PlayerModel)super.getEntityModel();
	}

	public void setModel(boolean thinArms){
		if(thinArms){
			this.entityModel = this.thinArmsModel;
		} else {
			this.entityModel = this.thickArmsModel;
		}
	}
	/**
	 * Returns the location of an entity's texture. Doesn't seem to be called unless you call EntityRenderer.bindEntityTexture.
	 *
	 * @param entity The entity to be rendered
	 */
	@Override
	protected ResourceLocation getEntityTexture(EntityLootableBody entity) {
		if(LootableBodies.useLocalSkin) return localSkin;

		GameProfile profile = entity.getGameProfile();
		if (profile != null && profile.getId() != null) {
			return getSkin(profile);
		}

		return DefaultPlayerSkin.getDefaultSkinLegacy();
	}

	public static ResourceLocation getSkin(GameProfile profile) {
		ResourceLocation fallback = profile == null || profile.getId() == null
				? DefaultPlayerSkin.getDefaultSkinLegacy() : DefaultPlayerSkin.getDefaultSkin(profile.getId());
		if(profile == null || profile.getId() == null) return fallback;
		try {
		final Minecraft minecraft = Minecraft.getInstance();
		final Map loadSkinFromCache = minecraft.getSkinManager().loadSkinFromCache(profile); // returned map may or may not be typed
		if (loadSkinFromCache.containsKey(MinecraftProfileTexture.Type.SKIN)) {
			ResourceLocation skin = minecraft.getSkinManager().loadSkin((MinecraftProfileTexture) loadSkinFromCache.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN);
			return skin;
		} else {
			return fallback;
		}
		} catch(RuntimeException unavailable) {
			// A service/cache failure must not abort renderModel and hide the body.
			return fallback;
		}
	}


	@Override
	public void doRender(EntityLootableBody entity, double x, double y, double z, float yaw, float partialTick) {
		if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.RenderLivingEvent.Pre<>(entity, this, partialTick, x, y, z)))
			return;

		this.setModel(entity.useThinArms());

		// render the model
		GlStateManager.pushMatrix();
		GlStateManager.disableCull();
		this.entityModel.swingProgress = 0;
		boolean shouldSit = entity.isPassenger() && (entity.getRidingEntity() != null && entity.getRidingEntity().shouldRiderSit());
		this.entityModel.isSitting = shouldSit;
		this.entityModel.isChild = entity.isChild();

		try {
			float rotationInterpolation = 0F;
			float headYaw = 0;


			float headPitch = 0;
			this.renderLivingAt(entity, x, y, z);
			float age = 0.0F;
			this.applyRotations(entity, age, rotationInterpolation, partialTick);
			float scale = this.prepareScale(entity, 0F);
			float armSwingAmount = 0.0F;
			float armSwing = 0.0F;


			GlStateManager.enableAlphaTest();
			this.entityModel.setLivingAnimations(entity, armSwing, armSwingAmount, 0);
			this.entityModel.setRotationAngles(entity, armSwing, armSwingAmount, age, headYaw, headPitch, scale);
			// sigh, unable to pose the model

			if (this.renderOutlines) {
				boolean flag1 = this.setScoreTeamColor(entity);
				GlStateManager.enableColorMaterial();
				GlStateManager.setupSolidRenderingTextureCombine(this.getTeamColor(entity));

				if (!this.renderMarker) {
					this.renderModel(entity, armSwing, armSwingAmount, age, headYaw, headPitch, scale);
				}


				this.renderLayers(entity, armSwing, armSwingAmount, partialTick, age, headYaw, headPitch, scale);


				GlStateManager.tearDownSolidRenderingTextureCombine();
				GlStateManager.disableColorMaterial();

				if (flag1) {
					this.unsetScoreTeamColor();
				}
			} else {
				boolean flag = this.setDoRenderBrightness(entity, partialTick);
				this.renderModel(entity, armSwing, armSwingAmount, age, headYaw, headPitch, scale);

				if (flag) {
					this.unsetBrightness();
				}

				GlStateManager.depthMask(true);


				this.renderLayers(entity, armSwing, armSwingAmount, partialTick, age, headYaw, headPitch, scale);

			}

			GlStateManager.disableRescaleNormal();
		} catch (Exception ex) {
			ModLog.log(Level.ERROR, ex, "Couldn\'t render entity");
		}

		GlStateManager.activeTexture(GLX.GL_TEXTURE1);
		GlStateManager.enableTexture();
		GlStateManager.activeTexture(GLX.GL_TEXTURE0);
		GlStateManager.enableCull();
		GlStateManager.popMatrix();
		if (!this.renderOutlines && LootableBodies.displayNameTag) {
			this.renderName(entity, x, y, z);
		}

		net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.client.event.RenderLivingEvent.Post<>(entity, this, partialTick, x, y, z));

		this.setModel(true);
	}


	@Override
	protected void renderLivingAt(EntityLootableBody e, double x, double y, double z)
	{
		super.renderLivingAt(e,x,y,z); // translation
		GlStateManager.rotatef(90,1F,0F,0F); // face-down
		GlStateManager.rotatef(e.getRotation(),0F,0F,1F); // turn
		GlStateManager.translatef(0F, -0.85F, -0.125F); // center
	}

}
