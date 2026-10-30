package cyano.lootable.entities;

import static cyano.lootable.ItemStacks.emptyStack;
import cyano.lootable.upgrade.UpgradePreservation;
import cyano.lootable.upgrade.ItemMigration;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.EntityDamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import cyano.lootable.ModLog;
import cyano.lootable.graphics.GUIHandler;
import org.apache.commons.lang3.ObjectUtils;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;


public class EntityLootableBody extends Mob implements Container, net.minecraft.world.MenuProvider{


	public static final EquipmentSlot[] EQUIPMENT_SLOTS = new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

	final static byte VACUUM_TIMELIMIT = 20;
	final static int VACUUM_RADIUS = 3;

	private final NonNullList<ItemStack> mainInventory = NonNullList.withSize(6*7, ItemStack.EMPTY);
	private final Deque<ItemStack> auxInventory = new LinkedList<>();
	private static final String UNRESOLVED_ITEMS_TAG = "LootableBodiesUnresolved";
	private final List<CompoundTag> unresolvedInventory = new ArrayList<>();
	private CompoundTag sourceVersions = new CompoundTag();
	private CompoundTag inventoryVersions = new CompoundTag();
	private static final String INVALID_FIELDS_TAG = "LootableBodiesInvalidFields";
	// Evidence about metadata is separate from inventory and must not prevent decay.
	private final List<CompoundTag> invalidFields = new ArrayList<>();
	private final Map<String,Double> defaultAttributeBases = new HashMap<>();
	private final Map<String,List<AttributeModifier>> defaultAttributeModifiers = new HashMap<>();
	private byte vacuumTime = 0;
	private long deathTimestamp;
	private long decayTimestamp;

	private final AtomicReference<GameProfile> gpSwap = new AtomicReference<>(null) ; // for lazy-evaluation of player skins
	// Profile services can be slow or unavailable. A bounded daemon worker must
	// never hold up client ticks or prevent the default corpse from being drawn.
	private static final ThreadPoolExecutor PROFILE_LOOKUPS = new ThreadPoolExecutor(0, 1, 30L,
			TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64), task -> {
		Thread thread = new Thread(task,"Lootable Bodies skin lookup");
		thread.setDaemon(true);
		return thread;
	});
	private int terminate = -1;
	private static final int DEATH_COUNTDOWN = 10;


	public EntityLootableBody(Level w) {
		super(LootableBodies.CORPSE,w);
		// The native type map is registered once; retain the legacy constructor's
		// configured base and full initial health for each newly created corpse.
		getAttribute(Attributes.MAX_HEALTH).setBaseValue(LootableBodies.corpseHP);
		setHealth(getMaxHealth());
		// Keep MobEntity's actual defaults (including follow range 16 and
		// configured corpse health), rather than the generic attribute defaults.
		for(AttributeInstance attribute : corpseAttributes()) {
			String name = attribute.getAttribute().getRegistryName().toString();
			defaultAttributeBases.put(name,attribute.getBaseValue());
			defaultAttributeModifiers.put(name,new ArrayList<>(attribute.getModifiers()));
		}
		deathTimestamp = w.getGameTime();
		decayTimestamp = deathTimestamp;
        // Local display preferences must not become world/server entity state.
        // Existing saved visibility flags are still read unchanged by vanilla.
		this.setCustomNameVisible(true);
		// The registered EntityType supplies the unchanged corpse dimensions.



	}

	public EntityLootableBody(Player player){
		this(player.getCommandSenderWorld());
		this.setPos(player.getX(), player.getY()+0.5,  player.getZ());

		this.setDeltaMovement(player.getDeltaMovement().add(0D,0.0784000015258789D,0D));

		//this.newPosX = getPosX(); // no newPosX?
		//this.newPosY = getPosY(); // no newPosY?
		//this.newPosZ = getPosZ(); // no newPosZ?
		this.setChanged();
	}



	private void log(String format, Object... o){
		ModLog.info("%s: %s",getClass().getSimpleName(), String.format(format, o));
	}

	private void log(Object o){
		ModLog.info("%s: %s",getClass().getSimpleName(),String.valueOf(o));
	}

	private String oldName = null;
	@Override
	public void tick() {
		if(!getCommandSenderWorld().isClientSide) {
			// A Forge ticket can tick our centre chunk while neighbouring support
			// terrain is absent. Never treat those missing collision boxes as air.
			AABB bounds = getBoundingBox();
			BlockPos from = new BlockPos(bounds.minX + Math.min(0D,getDeltaMovement().x), 0,
					bounds.minZ + Math.min(0D,getDeltaMovement().z));
			BlockPos to = new BlockPos(bounds.maxX + Math.max(0D,getDeltaMovement().x), 0,
					bounds.maxZ + Math.max(0D,getDeltaMovement().z));
			if(!getCommandSenderWorld().hasChunksAt(from,to)) return;
		}
		super.tick();
	}

	@Override
	public void baseTick(){
		//log("position=%s; terminate=%s; time=%s; age=%s; deathTimestamp=%s; decayTimestamp=%s; Health=%s; isDead=%s" ,getPosition(),terminate,getEntityWorld().getTotalWorldTime(),getEntityWorld().getTotalWorldTime() - deathTimestamp, deathTimestamp, decayTimestamp, getHealth(), isDead);
		super.baseTick();
		final boolean isClient = getCommandSenderWorld().isClientSide;
		final boolean isServer = !isClient;

		if(isServer && terminate >= 0){
			// in death mode
			if(terminate == DEATH_COUNTDOWN && !dropAllItems()) {
				// Keep rejected stacks recoverable; vanilla must not remove their owner.
				cancelRemoval();
				return;
			}
			// handle self-removal from entity list in world
			if(terminate > 0) terminate--;
			if(terminate == 0){
				// needs to be manually deleted for some reason
				this.discard();
			}
			return;
		}
		if(isServer && terminate < 0 && (this.getHealth() <= 0 || this.isRemoved())){
			terminate = DEATH_COUNTDOWN;
		}

		// handle corpse name (and therefore appearance)
		String nameUpdate = this.getCustomNameTag();
		if(ObjectUtils.notEqual(oldName,nameUpdate)){
			oldName = nameUpdate;
			if(nameUpdate != null && nameUpdate.trim().length() > 0) {
				GameProfile gp = new GameProfile(null, nameUpdate);
				setGameProfile(gp);
				if((!LootableBodies.useLocalSkin) && isClient) resolveProfile(gp);
				this.setCustomNameTag(nameUpdate);
			} else {
				setGameProfile(null);
				this.setCustomNameTag("");
			}
		}

		// handle decay time
		final long currentTime = Math.max(0L,getCommandSenderWorld().getGameTime());
		if(isServer) {
			// A clock rollback must not suspend decay indefinitely or overflow age.
			deathTimestamp = checkedTimestamp("DeathTime",deathTimestamp,currentTime);
			decayTimestamp = checkedTimestamp("DecayTime",decayTimestamp,currentTime);
		}
		if(isServer && LootableBodies.allowCorpseDecay){
			if(LootableBodies.decayOnlyWhenEmpty && isEmpty() == false){
				deathTimestamp = currentTime;
			}
			long age = currentTime - deathTimestamp;
			if(age > LootableBodies.corpseDecayTime){
				this.kill();
			}
		}

		// handle item decay
		if(isServer && LootableBodies.ticksPerItemDecay > 0){
			long age = currentTime - decayTimestamp;
			if(age > LootableBodies.ticksPerItemDecay){
				long elapsed = age / LootableBodies.ticksPerItemDecay;
				// Consume the whole interval, even when damage exceeds an int.
				decayTimestamp = currentTime - age % LootableBodies.ticksPerItemDecay;
				decayItems((int)Math.min(elapsed,Integer.MAX_VALUE));
			}
		}


		// move items from buffer to main inventory
		if(isServer && !auxInventory.isEmpty()) {
			for (int slot = this.getContainerSize() - 1; slot >= EQUIPMENT_SLOTS.length; slot--) {
				if(emptyStack(this.getItem(slot))){
					if(emptyStack(mergeItem(slot,auxInventory.peekFirst()))) auxInventory.removeFirst();
					this.setChanged();
					break;
				}
			}
		}

		// handle item vacuuming
		if(isServer && vacuumTime < VACUUM_TIMELIMIT){
			vacuumTime++;
			List<ItemEntity> items = getCommandSenderWorld().getEntitiesOfClass(ItemEntity.class, new AABB(
					getX() - VACUUM_RADIUS, getY() - VACUUM_RADIUS, getZ() - VACUUM_RADIUS,
					getX() + VACUUM_RADIUS, getY() + VACUUM_RADIUS, getZ() + VACUUM_RADIUS));
			for(ItemEntity item : items){
				if(item.isRemoved()) continue;
				ItemStack stack = item.getItem();
				this.addItem(stack);
				item.discard();
			}
		}

		if(isServer && LootableBodies.corpseBuoyancy && vacuumTime >= VACUUM_TIMELIMIT && (this.wasTouchingWater || this.isInLava())) {
			this.setDeltaMovement(getDeltaMovement().x,(getDeltaMovement().y+0.025D)*0.9D,getDeltaMovement().z);
		}
		
		if(isServer && LootableBodies.voidPlatform && this.getY() < 0) {
			generateVoidPlatform();
			this.teleportTo((int)this.getX(), 1.5, (int)this.getZ());
		}

	}

	public boolean handleWaterMovement() {
		// Native 1.17 updates both water and lava in this entry point.
		// Keep its fluid heights, fire clearing and current pushing together.
		return super.updateInWaterStateAndDoFluidPushing();
	}
	
	private void generateVoidPlatform() {
		Level level = this.level;
		BlockPos testPos = new BlockPos((int)this.getX(), 0, (int)this.getZ());
		
		BlockState platformBlockstate = getPlatformBlock(level.dimension()==Level.NETHER?-1:level.dimension()==Level.END?1:0);
		
		for(int j = -1; j <= 1; j++) {
			for(int k = -1; k <= 1; k++)	{
				if(level.isEmptyBlock(testPos.offset(j,0,k)))
					level.setBlock(testPos.offset(j,0,k), platformBlockstate, 3);
			}
		}
	}
	
	private BlockState getPlatformBlock(int i) {
		switch(LootableBodies.dimensionSpecificPlatform ? i : 0) {
			case -1:
				return Blocks.NETHERRACK.defaultBlockState();
			case 1:
				return Blocks.END_STONE.defaultBlockState();
			default:
				return Blocks.COBBLESTONE.defaultBlockState();
		}
	}
	
	private void decayItems(int amount) {
		for(int slot = 0; slot < this.getContainerSize(); slot++){
			this.setItem(slot,this.decayItem(this.getItem(slot), amount));
		}
		for(ItemStack item : auxInventory){
			decayItem(item, amount);
		}
	}

	private ItemStack decayItem(ItemStack item, int amount) {
		if(!emptyStack(item)
				&& item.getCount() == 1
				&& item.isDamageableItem()
				&& item.getItem().isDamageable(item)){
			int durabilityRemaining = item.getMaxDamage() - item.getDamageValue() - 1;
			if(durabilityRemaining > 0){
				item.hurtAndBreak(Math.min(durabilityRemaining,amount),this,broken->{});
			}
		}
		return item;
	}

	public void addItem(ItemStack stack) {
		for(int slot = EQUIPMENT_SLOTS.length; slot < this.getContainerSize(); slot++){
			stack = mergeItem(slot,stack);
			if(emptyStack(stack)) return;
		}
		auxInventory.addLast(stack);
		setChanged();
	}

	/** Retain an item whose container release was rejected. Uses the existing Aux format. */
	public void retainItem(ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0) return;
		if(terminate >= 0 || !isAlive()) cancelRemoval();
		addItem(stack);
	}

	private void cancelRemoval() {
		terminate = -1;
		deathTime = 0;
		dead = false;
		if(isRemoved())revive();
		setHealth(getMaxHealth());
		vacuumTime = VACUUM_TIMELIMIT;
		setChanged();
	}


	public void initializeItems(ItemStack[] inv) {
        initializeItems(Arrays.asList(inv));
    }

    public void initializeItems(NonNullList<ItemStack> inv) {
        initializeItems((List<ItemStack>)inv);
    }

    public void initializeItems(List<ItemStack> inv) {
        mainInventory.clear();
        for(int i=0; i<inv.size(); i++) {
            ItemStack stack=inv.get(i);
            if(emptyStack(stack)) continue;
            if(i<mainInventory.size()) mainInventory.set(i,stack);
            else auxInventory.addLast(stack);
        }
        setChanged();
    }

	public ItemStack mergeItem(int slot, ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0) return ItemStack.EMPTY;
		ItemStack current = this.getItem(slot);
		final int inventorySizeLimit = Math.min(stack.getMaxStackSize(),this.getMaxStackSize());
		if(emptyStack(current)){
			int amount = Math.min(stack.getCount(),inventorySizeLimit);
			if(amount > 0) this.setItem(slot,stack.split(amount));
			return stack.getCount() > 0 ? stack : ItemStack.EMPTY;
		} else if(current.getCount() < inventorySizeLimit
				&& ItemStack.isSame(stack,current)
				&& ItemStack.tagMatches(stack,current)){
			int delta = Math.min(stack.getCount(),inventorySizeLimit - current.getCount());
			current.grow(delta);
			stack.shrink(delta);
			this.setItem(slot,current);
			if(stack.getCount() <= 0) return ItemStack.EMPTY;
			return stack;
		}
		return stack;
	}


	@Override
	protected void actuallyHurt(DamageSource src, float amount){
		String type = String.valueOf(src == null ? null : src.getMsgId()); // null protection
		if(src instanceof EntityDamageSource && src.getEntity() instanceof Player){
			Player player = (Player)src.getEntity();
			ItemStack item = player.getItemInHand(InteractionHand.MAIN_HAND);
			if(!emptyStack(item)) {
				if (item.getItem() instanceof ShovelItem || item.canPerformAction(net.minecraftforge.common.ToolActions.SHOVEL_DIG)) {
					this.kill();
				}
			}
		}
		if(type.equals(DamageSource.OUT_OF_WORLD.getMsgId())) {
			super.actuallyHurt(src,amount); // kill command and falling out of the world
			return;
		}

		if(type.equals(DamageSource.IN_WALL.getMsgId())){
			this.jumpOutOfWall();
		}

		// damage handling
		if(LootableBodies.completelyInvulnerable) return;

		if(src instanceof EntityDamageSource ){
			if(LootableBodies.hurtByAttacks) super.actuallyHurt(src,amount);
			return;
		}

		if(matchesAny(type, DamageSource.ANVIL, DamageSource.CACTUS, DamageSource.FALL, DamageSource.FALLING_BLOCK, DamageSource.FLY_INTO_WALL, DamageSource.IN_FIRE, DamageSource.LAVA, DamageSource.MAGIC, DamageSource.LIGHTNING_BOLT, DamageSource.ON_FIRE)){
			if(LootableBodies.hurtByEnvironment)super.actuallyHurt(src,amount);
			return;
		}

		if(LootableBodies.hurtByMisc){
			super.actuallyHurt(src,amount);
			return;
		}
	}



	public GameProfile getGameProfile(){
		return gpSwap.get();
	}

	public void setGameProfile(GameProfile gp){
		gpSwap.set(gp);
	}
    /** Refreshes an existing client body after enabling player skins in the GUI. */
    public String getCustomNameTag(){return getCustomName()==null?"":getCustomName().getString();}
    public void setCustomNameTag(String name){setCustomName(name==null || name.isEmpty()?null:new net.minecraft.network.chat.TextComponent(name));}

    public void refreshClientSkin() {
        GameProfile profile = gpSwap.get();
        if (getCommandSenderWorld().isClientSide && !LootableBodies.useLocalSkin && profile != null && profile.getId() == null)
            resolveProfile(profile);
    }
	private void resolveProfile(GameProfile initial) {
		final net.minecraft.server.players.GameProfileCache cache=SkullBlockEntity.profileCache;
		final com.mojang.authlib.minecraft.MinecraftSessionService service=SkullBlockEntity.sessionService;
		try {
			PROFILE_LOOKUPS.execute(() -> {
				// Native37's skull helper delegates to another asynchronous worker,
				// outside this exception boundary. Keep both native service calls
				// on our bounded worker, and let a newer name/profile win the CAS.
				GameProfile resolved=cyano.lootable.network.CorpseProfiles.resolve(initial,cache,service);
				if(resolved != null)gpSwap.compareAndSet(initial,resolved);
			});
		} catch(RejectedExecutionException busy) {
			// Keep the already installed default profile when the bounded queue is full.
			ModLog.fine("Corpse profile lookup busy; using default skin");
		}
	}
	public void setUserName(String name){
		this.setCustomNameTag(name);
	}

	private long getDeathTimestamp() {
		return deathTimestamp;
	}
	private void setDeathTimestamp(long timestamp) {
		deathTimestamp = timestamp;
	}


    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder corpseAttributeDefaults() {
        return net.minecraft.world.entity.Mob.createMobAttributes().add(Attributes.MAX_HEALTH,LootableBodies.corpseHP);
    }
    private java.util.List<AttributeInstance> corpseAttributes() {
        java.util.List<AttributeInstance> result=new java.util.ArrayList<>();
        for(net.minecraft.world.entity.ai.attributes.Attribute key:net.minecraft.core.Registry.ATTRIBUTE)
            if(getAttributes().hasAttribute(key))result.add(getAttribute(key));
        return result;
    }
    private AttributeInstance attributeNamed(String name) {
        switch(name) {
        case "generic.maxHealth":name="minecraft:generic.max_health";break;
        case "generic.followRange":name="minecraft:generic.follow_range";break;
        case "generic.knockbackResistance":name="minecraft:generic.knockback_resistance";break;
        case "generic.movementSpeed":name="minecraft:generic.movement_speed";break;
        case "generic.flyingSpeed":name="minecraft:generic.flying_speed";break;
        case "generic.attackDamage":name="minecraft:generic.attack_damage";break;
        case "generic.attackKnockback":name="minecraft:generic.attack_knockback";break;
        case "generic.attackSpeed":name="minecraft:generic.attack_speed";break;
        case "generic.armorToughness":name="minecraft:generic.armor_toughness";break;
        case "horse.jumpStrength":name="minecraft:horse.jump_strength";break;
        case "zombie.spawnReinforcements":name="minecraft:zombie.spawn_reinforcements";break;
        case "forge.swimSpeed":name="forge:swim_speed";break;
        case "forge.nameTagDistance":name="forge:nametag_distance";break;
        case "forge.entity_gravity":name="forge:entity_gravity";break;
        default:break;
        }
        net.minecraft.resources.ResourceLocation id=net.minecraft.resources.ResourceLocation.tryParse(name);
        net.minecraft.world.entity.ai.attributes.Attribute key=id==null?null:net.minecraft.core.Registry.ATTRIBUTE.getOptional(id).orElse(null);
        return key!=null && getAttributes().hasAttribute(key)?getAttribute(key):null;
    }

	public float getRotation(){
		return getYRot();
	}

	public void setRotation(float newRot){
		setYRot(newRot);
	}


	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	public void jumpOutOfWall(){
		if(level.isClientSide) return;
		AABB bounds = getBoundingBox();
		// Collision queries omit unloaded chunks. They cannot establish an exit
		// until the complete local terrain (including tall neighbour shapes) exists.
		if(!escapeTerrainLoaded(bounds) || level.noCollision(this,bounds)) return;
		BlockPos currentCoord = blockPosition();
		double x = currentCoord.getX()+0.5 - getX();
		double z = currentCoord.getZ()+0.5 - getZ();
		double length = Math.hypot(x,z);
		// At a block centre there is no horizontal direction to normalize.
		if(length > 0 && tryWallExit(getX()+x/length,getY(),getZ()+z/length)) return;

		// Retain the original adjacent-exit priority, but check the whole body.
		for(Direction direction : new Direction[]{Direction.UP,Direction.NORTH,
				Direction.EAST,Direction.SOUTH,Direction.WEST,Direction.DOWN}) {
			BlockPos exit = currentCoord.relative(direction);
			if(tryWallExit(exit.getX()+0.5,exit.getY()+0.015625,exit.getZ()+0.5)) return;
		}
		// A sealed corpse must remain recoverable here, rather than wander through
		// solid terrain or load chunks looking for an unchecked random destination.
	}

	private boolean escapeTerrainLoaded(AABB bounds) {
		return level.hasChunksAt(new BlockPos(bounds.minX-1,0,bounds.minZ-1),
				new BlockPos(bounds.maxX+1,0,bounds.maxZ+1));
	}

	private boolean tryWallExit(double x,double y,double z) {
		AABB bounds = getBoundingBox().move(x-getX(),y-getY(),z-getZ());
		if(bounds.minY < 0 || bounds.maxY > level.getHeight() ||
				!level.getWorldBorder().isWithinBounds(bounds) || !escapeTerrainLoaded(bounds) ||
				!level.noCollision(this,bounds)) return false;
		setPos(x,y,z);
		return true;
	}


	@Override
	public void kill() {
		if(terminate < 0) terminate = DEATH_COUNTDOWN;
		this.hurt(DamageSource.OUT_OF_WORLD, this.getMaxHealth());
		this.setChanged();
	}


	@Override
	public int getAmbientSoundInterval() {
		return 1200;
	}
	@Override
	public void playAmbientSound() {
		// do nothing
	}



	@Override
	protected int getExperienceReward(final Player p_getExperiencePoints_1_) {
		return 0;
	}


	@Override
	protected SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected ResourceLocation getDefaultLootTable() {
		return net.minecraft.world.level.storage.loot.BuiltInLootTables.EMPTY;
	}

    @Override
    protected void dropFromLootTable(DamageSource source,boolean recentlyHit) {
        // Native Mob.getLootTable is final and can select a saved LootTable.
        // Corpse contents are released only by the inventory recovery path.
    }

	@Override
	protected void dropCustomDeathLoot(DamageSource source, int lootingModifier, boolean wasRecentlyHit) {
		// Corpse equipment belongs to the inventory release path, not mob loot.
	}

	@Override
	public boolean canBeControlledByRider() {
		return false;
	}


	@Override
	public boolean canBeLeashed(Player player) {
		return false; // leashing not allowed
	}


	@Override
	public boolean canPickUpLoot() {
		return false; // picking up items done in special way
	}

	@Override
	public void setCanPickUpLoot(final boolean p_setCanPickUpLoot_1_) {
		// do nothing
	}


	@Override public boolean canBreatheUnderwater() {
		return true;
	}

	////////// IInventory //////////
	/**
	 * Returns the number of slots in the inventory.
	 */
	@Override
	public int getContainerSize() {
		return EQUIPMENT_SLOTS.length + mainInventory.size();
	}

	/**
	 * Returns the stack in the given slot.
	 *
	 * @param index
	 */
	@Override
	public ItemStack getItem(int index) {
		if(index < EQUIPMENT_SLOTS.length){
			return super.getItemBySlot(EQUIPMENT_SLOTS[index]);
		} else {
			return mainInventory.get(index - EQUIPMENT_SLOTS.length);
		}
	}

	/**
	 * Sets the given item stack to the specified slot in the inventory (can be crafting or armor sections).
	 *
	 * @param index
	 * @param stack
	 */
	@Override
	public void setItem(int index, ItemStack stack) {
        if(stack == null) stack = ItemStack.EMPTY;
		if(index < EQUIPMENT_SLOTS.length){
			super.setItemSlot(EQUIPMENT_SLOTS[index], stack);
		} else {
			mainInventory.set(index - EQUIPMENT_SLOTS.length, stack);
		}
		setChanged();
	}

	/**
	 * Removes a stack from the given slot and returns it.
	 *
	 * @param index
	 */
	@Override
	public ItemStack removeItemNoUpdate(int index) {
		ItemStack i = this.getItem(index);
		this.setItem(index,null);
		return i;
	}

	/**
	 * Removes up to a specified number of items from an inventory slot and returns them in a new stack.
	 *
	 * @param index
	 * @param count
	 */
	@Override
	public ItemStack removeItem(int index, int count) {
		ItemStack i = this.getItem(index);
		if(emptyStack(i) || count <= 0) return ItemStack.EMPTY;
		ItemStack result = i.split(count);
		if (i.getCount() <= 0) {
			i = ItemStack.EMPTY;
		}
		this.setItem(index, i);
		return result;
	}

	/**
	 * Returns the maximum stack size for a inventory slot. Seems to always be 64, possibly will be extended.
	 */
	@Override
	public int getMaxStackSize() {
		return 64;
	}

	/**
	 * For tile entities, ensures the chunk containing the tile entity is saved to disk later - the game won't think it
	 * hasn't changed and skip it.
	 */
	@Override
	public void setChanged() {
		if(level != null && !level.isClientSide && isAddedToWorld() && level.hasChunkAt(blockPosition())) {
			level.getChunkAt(blockPosition()).setUnsaved(true);
		}
	}

	/**
	 * Do not make give this method the name canInteractWith because it clashes with Container
	 *
	 * @param player
	 */
	@Override
	public boolean stillValid(Player player) {
		return player != null && player.isAlive() && this.isAlive()
				&& terminate < 0 && player.getCommandSenderWorld() == getCommandSenderWorld()
				&& player.blockPosition().distSqr(this.blockPosition()) < 25;
	}

	@Override
	public net.minecraft.world.InteractionResult mobInteract(Player player, InteractionHand hand){
		if(stillValid(player)) {
			GUIHandler.open(player,this);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return net.minecraft.world.InteractionResult.PASS;
	}

    @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int windowId, net.minecraft.world.entity.player.Inventory inventory, Player player){return new cyano.lootable.graphics.CorpseContainer(windowId,inventory,this);}
    public String getGuiID(){return "lootablebodies:corpse";}

	@Override
	public void startOpen(Player player) {
	}

	@Override
	public void stopOpen(Player player) {
	}

	/**
	 * Returns true if automation is allowed to insert the given stack (ignoring stack size) into the given slot.
	 *
	 * @param index
	 * @param stack
	 */
	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0) return false;
		if(index < 4){
			return stack.canEquip(EQUIPMENT_SLOTS[index], this);
		} else {
			return true;
		}
	}

	public int getField(int id) {
		return 0;
	}

	public void setField(int id, int value) {
		// do nothing
	}

	public int getFieldCount() {
		return 0;
	}

	@Override
	public void clearContent() {
		for(int i = 0; i < EQUIPMENT_SLOTS.length; i++){
			this.setItem(i,null);
		}
		mainInventory.clear();
		auxInventory.clear();
		unresolvedInventory.clear();
	}

	////////// End of IInventory //////////

	public boolean isEmpty(){
		if(!auxInventory.isEmpty() || !unresolvedInventory.isEmpty()) return false;
		for(int slot = 0; slot < this.getContainerSize(); slot++){
			if(!emptyStack(this.getItem(slot))) return false;
		}
		return true;
	}

	private boolean dropAllItems(){
		for(int slot = 0; slot < getContainerSize(); slot++){
			ItemStack stack = getItem(slot);
			if(!emptyStack(stack) && getCommandSenderWorld().addFreshEntity(
					new ItemEntity(getCommandSenderWorld(),getX(),getY(),getZ(),stack.copy()))) {
				setItem(slot,null);
			}
		}
		Iterator<ItemStack> iterator = auxInventory.iterator();
		while(iterator.hasNext()) {
			ItemStack stack = iterator.next();
			if(emptyStack(stack) || getCommandSenderWorld().addFreshEntity(
					new ItemEntity(getCommandSenderWorld(),getX(),getY(),getZ(),stack.copy()))) iterator.remove();
		}
		setChanged();
		return isEmpty();
	}

	private static boolean matchesAny(String damageType, DamageSource... list){
		for(int i = 0; i < list.length; i++){
			if(damageType.equalsIgnoreCase(list[i].getMsgId())) return true;
		}
		return false;
	}

	@Override
	public MobType getMobType()
	{
		return MobType.UNDEAD;
	}


	@Override
	public void addAdditionalSaveData(final CompoundTag root) {
		UpgradePreservation.clearTransient(root);
		root.putInt(UpgradePreservation.VERSION,UpgradePreservation.DATA_VERSION);
		if(sourceVersions.isEmpty()) root.remove(UpgradePreservation.SOURCES);
		else root.put(UpgradePreservation.SOURCES,sourceVersions.copy());
		root.remove("ActiveEffects");
		super.addAdditionalSaveData(root);
		final ListTag equipmentListTag = new ListTag();
		for (int i = 0; i < this.getContainerSize(); i++) {
			ItemStack item = this.getItem(i);
			if (!emptyStack(item)) {
				CompoundTag slotTag = new CompoundTag();
				slotTag.putByte("Slot", (byte)i);
				item.save(slotTag);
				equipmentListTag.add(slotTag);
			}
		}
		root.put("Equipment", equipmentListTag);

		root.remove("Aux");
		if(!this.auxInventory.isEmpty()){
			final ListTag nbtauxtaglist = new ListTag();
			Iterator<ItemStack> iter = this.auxInventory.iterator();
			while (iter.hasNext()) {
				ItemStack i = iter.next();
				if(emptyStack(i)) continue;
				final CompoundTag nbttagcompound1 = new CompoundTag();
				i.save(nbttagcompound1);
				nbtauxtaglist.add(nbttagcompound1);
			}
			root.put("Aux", nbtauxtaglist);
		}
		root.remove(UNRESOLVED_ITEMS_TAG);
		if(!unresolvedInventory.isEmpty()) {
			ListTag retained = new ListTag();
			for(CompoundTag record : unresolvedInventory) retained.add(record.copy());
			root.put(UNRESOLVED_ITEMS_TAG,retained);
		}
		root.remove(INVALID_FIELDS_TAG);
		if(!invalidFields.isEmpty()) {
			ListTag retained = new ListTag();
			for(CompoundTag record : invalidFields) retained.add(record.copy());
			root.put(INVALID_FIELDS_TAG,retained);
		}
		root.remove("Vac");
		if(vacuumTime < VACUUM_TIMELIMIT)root.putByte("Vac", vacuumTime);
		root.remove("Name");
		String name = getCustomNameTag();
		if(name != null && !name.trim().isEmpty()) root.putString("Name",name);
		else root.remove("CustomName");
		root.putLong("DeathTime", this.getDeathTimestamp());
		root.putLong("DecayTime",this.decayTimestamp);
	}


	@Override
	public void load(final CompoundTag supplied) {
		final CompoundTag root=UpgradePreservation.restore(supplied);
		// Entity reads geometry before our inventory helper. Sanitize a copy
		// before it builds bounding boxes or derives previous-tick positions.
		CompoundTag clean = root.copy();
		boolean[] badPosition = new boolean[4], badMotion = new boolean[4];
		boolean[] badRotation = new boolean[3];
		clean.put("Pos",readVector(root.get("Pos"),false,badPosition));
		clean.put("Motion",readVector(root.get("Motion"),true,badMotion));
		clean.put("Rotation",readRotation(root.get("Rotation"),badRotation));
		Float distance = finiteFloat(root.get("FallDistance"));
		boolean badFall = root.contains("FallDistance") && (distance == null || distance < 0F);
        clean.putFloat("FallDistance",distance != null && distance >= 0F ? distance : 0F);
        // Vanilla expects JSON here. Our attribution reader owns compatibility
        // and retains malformed input without sending it to vanilla's parser.
        clean.remove("CustomName");
        super.load(clean);
        String fallback=readName(root,"CustomName"),owner=readName(root,"Name");
        setUserName(owner!=null?owner:fallback!=null?fallback:"");
		// A repeated load must not interpolate from obsolete head/body angles.
		yRotO = getYRot(); xRotO = getXRot();
		setYHeadRot(getYRot()); yHeadRotO = getYRot();
		setYBodyRot(getYRot()); yBodyRotO = getYRot();
		if(hasInvalidComponent(badPosition) && root.contains("Pos")) retainInvalid("Pos",root.get("Pos"));
		if(hasInvalidComponent(badMotion) && root.contains("Motion")) retainInvalid("Motion",root.get("Motion"));
		if(hasInvalidComponent(badRotation) && root.contains("Rotation")) retainInvalid("Rotation",root.get("Rotation"));
		if(badFall) retainInvalid("FallDistance",root.get("FallDistance"));
	}

	private static Float finiteFloat(Tag raw) {
		Double number = finiteNumber(raw);
		if(number == null) return null;
		float value = number.floatValue();
		return Float.isFinite(value) && (number == 0D || value != 0F) ? value : null;
	}

	private static ListTag readRotation(Tag raw, boolean[] invalid) {
		ListTag list = raw instanceof ListTag ? (ListTag)raw : null;
		invalid[2] = list == null || list.size() != 2 || list.getElementType() < 1 || list.getElementType() > 6;
		ListTag clean = new ListTag();
		for(int axis = 0; axis < 2; axis++) {
			Float angle = list != null && axis < list.size() ? finiteFloat(list.get(axis)) : null;
			if(angle == null) { invalid[axis] = true; angle = 0F; }
			// Preserve the legacy float remainder and the corpse's existing
			// flat pitch before vanilla copies angles into head/render state.
			clean.add(net.minecraft.nbt.FloatTag.valueOf(axis == 0 ? angle % 360F : 0F));
		}
		return clean;
	}

	private static boolean hasInvalidComponent(boolean[] invalid) {
		for(boolean value : invalid) if(value) return true;
		return false;
	}

	private static ListTag readVector(Tag raw, boolean motion, boolean[] invalid) {
		ListTag list = raw instanceof ListTag ? (ListTag)raw : null;
		invalid[3] = list == null || list.size() != 3 || list.getElementType() < 1 || list.getElementType() > 6;
		ListTag clean = new ListTag();
		for(int axis = 0; axis < 3; axis++) {
			Double value = list != null && axis < list.size() ? finiteNumber(list.get(axis)) : null;
			if(value == null || (motion ? Math.abs(value)>10D : !cyano.lootable.upgrade.SavedCorpsePosition.isSafeCoordinate(axis,value))) {
				invalid[axis] = true;
				// A lost height must not put recoverable inventory in the void.
				value = !motion && axis == 1 ? 64D : 0D;
			}
			clean.add(net.minecraft.nbt.DoubleTag.valueOf(value));
		}
		return clean;
	}

	@Override
	public void readAdditionalSaveData(final CompoundTag supplied) {
		final CompoundTag root=UpgradePreservation.restore(supplied);
		sourceVersions=root.contains(UpgradePreservation.SOURCES,10) ? root.getCompound(UpgradePreservation.SOURCES).copy() : new CompoundTag();
		// Persisted Equipment/Aux are already current after the first save.
		// Only the transient protected original and retained raw records use old versions.
		inventoryVersions=root.getBoolean(UpgradePreservation.ITEMS_PENDING) ? sourceVersions : new CompoundTag();
		// Replace, rather than append to, an existing inventory on a repeated load.
		this.clearContent();
		// These flags are not saved. A reused entity must behave like a fresh
		// load; its new Health still decides whether normal death handling starts.
		terminate = -1;
		dead = false;
		if(isRemoved())revive();
		// Item readers may update their input tags; the caller's saved data is immutable.
		CompoundTag vanilla = root.copy();
		// Our historical DeathTime is a world timestamp, not vanilla's animation.
		vanilla.putShort("DeathTime",(short)0);
		invalidFields.clear();
		Tag metadata = root.get(INVALID_FIELDS_TAG);
		if(isInventoryList(metadata)) {
			ListTag records = (ListTag)metadata;
			for(int i = 0; i < records.size(); i++) invalidFields.add(records.getCompound(i).copy());
		} else if(metadata != null) retainInvalid("Recovery",metadata);
		// NBT replaces state. Clear the old map without effect callbacks: saved
		// attributes and absorption are restored below, just as on a fresh entity.
		getActiveEffects().clear();
		for(AttributeInstance attribute : corpseAttributes()) restoreAttribute(attribute);
		vanilla.put("Attributes",readAttributes(root.get("Attributes")));
		vanilla.put("ActiveEffects",readEffects(root.get("ActiveEffects")));
		vanilla.putShort("HurtTime",(short)readTimer(root,"HurtTime",Short.MAX_VALUE,0));
		// Health must be checked against the loaded, validated effective maximum.
		vanilla.remove("Health");
		vanilla.putFloat("AbsorptionAmount",readLivingFloat(root,"AbsorptionAmount",0F,Float.MAX_VALUE));
		super.readAdditionalSaveData(vanilla);
		updateInvisibilityStatus();
		for(AttributeInstance attribute : corpseAttributes()) {
			if(!Double.isFinite(attribute.getValue())) {
				// Also protect against engine ordering/parent-attribute arithmetic.
				if(root.contains("Attributes")) retainInvalid("Attributes",root.get("Attributes"));
				restoreAttribute(attribute);
			}
		}
		setHealth(readLivingFloat(root,"Health",getMaxHealth(),getMaxHealth()));
		Tag equipment = root.get("Equipment");
		if(isInventoryList(equipment)) {
			// Vanilla ArmorItems/HandItems mirror these six slots. The custom
			// list is authoritative when present, so do not load both copies.
			for(int i = 0; i < EQUIPMENT_SLOTS.length; i++) setItem(i,null);
		}
		readInventoryTag("Equipment",equipment);
		readInventoryTag("Aux",root.get("Aux"));
		Tag recovery = root.get(UNRESOLVED_ITEMS_TAG);
		if(isInventoryList(recovery)) {
			ListTag records = (ListTag)recovery;
			for(int i = 0; i < records.size(); i++) {
				CompoundTag record = records.getCompound(i);
				String source = record.getString("Source");
				if((record.getAllKeys().size() == 2 || (record.getAllKeys().size() == 3 && record.contains("SourceVersions",10)))
						&& record.contains("Source",8) && record.contains("Data",10)
						&& (source.equals("Equipment") || source.equals("Aux"))) {
					readInventoryEntry(source,record.getCompound("Data"),record);
				} else unresolvedInventory.add(record.copy());
			}
		} else if(recovery != null) retainRaw("Recovery",recovery);
		if(!unresolvedInventory.isEmpty()) ModLog.warning(
				"Lootable Bodies retained %s unreadable inventory records for later recovery",unresolvedInventory.size());

		long now = Math.max(0L,getCommandSenderWorld().getGameTime());
		this.vacuumTime = (byte)readTimer(root,"Vac",VACUUM_TIMELIMIT,VACUUM_TIMELIMIT);
		this.setDeathTimestamp(readTimer(root,"DeathTime",now,now));
		this.decayTimestamp = readTimer(root,"DecayTime",now,now);
		String fallback = readName(root,"CustomName");
		String name = readName(root,"Name");
		this.setUserName(name != null ? name : fallback != null ? fallback : "");
		this.setGameProfile(null);
		this.oldName = null;
		this.setRot(getYRot(), 0);
	}

	private void retainInvalid(String source, Tag data) {
		CompoundTag record = new CompoundTag();
		record.putString("Source",source);
		record.put("Data",data.copy());
		if(!invalidFields.contains(record)) invalidFields.add(record);
	}

	private void restoreAttribute(AttributeInstance attribute) {
		String name = attribute.getAttribute().getRegistryName().toString();
		for(AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) attribute.removeModifier(modifier);
		attribute.setBaseValue(defaultAttributeBases.getOrDefault(name,attribute.getAttribute().getDefaultValue()));
		List<AttributeModifier> defaults = defaultAttributeModifiers.get(name);
		if(defaults != null) for(AttributeModifier modifier : defaults) attribute.addPermanentModifier(modifier);
	}

	private ListTag readEffects(Tag raw) {
		ListTag clean = new ListTag();
		if(raw == null) return clean;
		if(!isInventoryList(raw)) { retainInvalid("ActiveEffects",raw); return clean; }
		Set<MobEffect> seen = new HashSet<>();
		ListTag list = (ListTag)raw;
		for(int i = 0; i < list.size(); i++) {
			CompoundTag record = list.getCompound(i);
			Tag rawId = record.get("Id"), rawAmplifier = record.get("Amplifier");
			Long id = integral(rawId);
			// IDs are unsigned bytes on disk. Amplifiers are read as signed
			// bytes by this engine; preserve all canonical byte encodings.
			if(rawId != null && rawId.getId() == 1) id = id & 255L;
			Long amplifier = rawAmplifier == null ? Long.valueOf(0) : integral(rawAmplifier);
			Long duration = record.contains("Duration") ? integral(record.get("Duration")) : Long.valueOf(0);
			MobEffect potion = id != null && id >= 0 && id <= 255 ? MobEffect.byId(id.intValue()) : null;
			if(potion == null || duration == null || duration < 0 || duration > Integer.MAX_VALUE
					|| amplifier == null || amplifier > 127 || (amplifier < 0 && rawAmplifier.getId() != 1)
					|| !seen.add(potion)) {
				retainInvalid("ActiveEffect",record); continue;
			}
			CompoundTag accepted = new CompoundTag();
			accepted.putByte("Id",id.byteValue()); accepted.putInt("Duration",duration.intValue());
			accepted.putByte("Amplifier",amplifier.byteValue());
			boolean changed = !Arrays.asList("Id","Duration","Amplifier","Ambient","ShowParticles","ShowIcon","CurativeItems").containsAll(record.getAllKeys());
			// Forge 1.11 persists curative stacks. Preserve their complete custom
			// data; an unreadable list uses Forge's defaults and stays recoverable.
			if(record.contains("CurativeItems")) {
				Tag rawCuratives = record.get("CurativeItems");
				boolean valid = isInventoryList(rawCuratives);
				if(valid) {
					ListTag items = (ListTag)rawCuratives;
					for(int c=0; c<items.size(); c++)
						if(decodeInventoryItem(items.getCompound(c)) == null) { valid=false; break; }
				}
				if(valid) accepted.put("CurativeItems",rawCuratives.copy());
				else changed = true;
			}
			for(String key : Arrays.asList("Ambient","ShowParticles","ShowIcon")) {
				boolean fallback = key.equals("ShowIcon") ? accepted.getBoolean("ShowParticles") : key.equals("ShowParticles");
				Long flag = integral(record.get(key));
				if(!record.contains(key)) accepted.putBoolean(key,fallback);
				else if(flag != null && flag >= 0 && flag <= 1) accepted.putBoolean(key,flag == 1);
				else { accepted.putBoolean(key,fallback); changed = true; }
			}
			if(changed) retainInvalid("ActiveEffect",record);
			clean.add(accepted);
		}
		return clean;
	}

	private static Double finiteNumber(Tag raw) {
		if(!(raw instanceof NumericTag) || raw.getId() < 1 || raw.getId() > 6) return null;
		double value = ((NumericTag)raw).getAsDouble();
		return Double.isFinite(value) ? value : null;
	}

	private float readLivingFloat(CompoundTag root, String key, float fallback, float maximum) {
		Tag raw = root.get(key);
		if(raw == null) return fallback;
		Double value = finiteNumber(raw);
		if(value != null && value >= 0 && value <= maximum) {
			float narrowed = value.floatValue();
			if(Float.isFinite(narrowed) && (value == 0 || narrowed > 0)) return narrowed;
		}
		retainInvalid(key,raw);
		return fallback;
	}

	private ListTag readAttributes(Tag raw) {
		ListTag clean = new ListTag();
		if(raw == null) return clean;
		if(!isInventoryList(raw)) { retainInvalid("Attributes",raw); return clean; }
		Set<String> seen = new HashSet<>();
		ListTag list = (ListTag)raw;
		for(int i = 0; i < list.size(); i++) {
			CompoundTag record = list.getCompound(i);
			AttributeInstance attribute = record.contains("Name",8)
					? attributeNamed(record.getString("Name")) : null;
			Double base = finiteNumber(record.get("Base"));
			if(attribute == null || base == null) { retainInvalid("Attribute",record); continue; }
			String name = attribute.getAttribute().getRegistryName().toString();
			// corpse_HP has always allowed 1..Short.MAX_VALUE. Preserve a base
			// saved under an older config, including vanilla's existing cap.
			boolean configuredHealth = attribute.getAttribute() == Attributes.MAX_HEALTH
					&& base >= 1D && base <= Short.MAX_VALUE;
            // Forge15 allowed this base up to Float.MAX_VALUE. Native16 caps
            // the effective distance at64; keep the valid saved base unchanged.
            boolean legacyNameDistance=attribute.getAttribute()==net.minecraftforge.common.ForgeMod.NAMETAG_DISTANCE.get()
                    && base>=0D && base<=Float.MAX_VALUE;
			if((attribute.getAttribute().sanitizeValue(base) != base
					&& !configuredHealth && !legacyNameDistance && !base.equals(defaultAttributeBases.get(name))) || !seen.add(name)) {
				retainInvalid("Attribute",record); continue;
			}
			boolean changed = !Arrays.asList("Name","Base","Modifiers").containsAll(record.getAllKeys());
			CompoundTag accepted = new CompoundTag();
			accepted.putString("Name",name); accepted.putDouble("Base",base);
			Tag rawModifiers = record.get("Modifiers");
			if(rawModifiers != null) {
				ListTag modifiers = new ListTag();
				if(!isInventoryList(rawModifiers)) changed = true;
				else {
					Set<UUID> ids = new HashSet<>();
					ListTag entries = (ListTag)rawModifiers;
					for(int m = 0; m < entries.size(); m++) {
						CompoundTag entry = entries.getCompound(m);
						Long operation = integral(entry.get("Operation"));
						UUID id=attributeUuid(entry);
						Double amount = finiteNumber(entry.get("Amount"));
						if(!entry.contains("Name",8) || entry.getString("Name").isEmpty() || operation == null
								|| operation < 0 || operation > 2 || id == null || amount == null) {
							changed = true; continue;
						}
						if(ids.contains(id)) { changed = true; continue; }
						CompoundTag modifier = new CompoundTag();
						modifier.putString("Name",entry.getString("Name")); modifier.putDouble("Amount",amount);
						modifier.putInt("Operation",operation.intValue()); modifier.putUUID("UUID",id);
						ListTag candidate = modifiers.copy(); candidate.add(modifier);
						if(!finiteAttributeValue(base,candidate)) { changed = true; continue; }
						ids.add(id); modifiers.add(modifier);
						if(!Arrays.asList("Name","Amount","Operation","UUIDMost","UUIDLeast","UUID").containsAll(entry.getAllKeys())) changed = true;
					}
				}
				accepted.put("Modifiers",modifiers);
			}
			if(changed) retainInvalid("Attribute",record);
			clean.add(accepted);
		}
		return clean;
	}

	private static UUID attributeUuid(CompoundTag entry) {
        UUID current=null,legacy=null;
        if(entry.contains("UUID")) {
            if(!entry.hasUUID("UUID"))return null;
            current=entry.getUUID("UUID");
        }
        if(entry.contains("UUIDMost") || entry.contains("UUIDLeast")) {
            Long most=integral(entry.get("UUIDMost")),least=integral(entry.get("UUIDLeast"));
            if(most==null || least==null)return null;
            legacy=new UUID(most,least);
        }
        return current!=null?(legacy!=null && !current.equals(legacy)?null:current):legacy;
    }
	private static boolean finiteAttributeValue(double base, ListTag modifiers) {
		double value = base;
		for(int i = 0; i < modifiers.size(); i++) {
			CompoundTag m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 0) value += m.getDouble("Amount");
		}
		if(!Double.isFinite(value)) return false;
		double additive = value;
		for(int i = 0; i < modifiers.size(); i++) {
			CompoundTag m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 1) value += additive * m.getDouble("Amount");
		}
		if(!Double.isFinite(value)) return false;
		for(int i = 0; i < modifiers.size(); i++) {
			CompoundTag m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 2) value *= 1D + m.getDouble("Amount");
		}
		return Double.isFinite(value);
	}

	private long readTimer(CompoundTag root, String key, long maximum, long fallback) {
		Tag raw = root.get(key);
		if(raw == null) return fallback;
		Long value = integral(raw);
		if(value != null && value >= 0 && value <= maximum) return value;
		retainInvalid(key,raw);
		return fallback;
	}

	private long checkedTimestamp(String key, long timestamp, long now) {
		if(timestamp >= 0 && timestamp <= now) return timestamp;
		retainInvalid(key,net.minecraft.nbt.LongTag.valueOf(timestamp));
		setChanged();
		return now;
	}

    private String readName(CompoundTag root,String key) {
        Tag raw=root.get(key);if(raw==null)return null;
        if(raw.getId()==8) {
            String name=root.getString(key);
            if(key.equals("CustomName") && root.getInt(UpgradePreservation.VERSION)>=3) {
                try {
                    net.minecraft.network.chat.Component text=net.minecraft.network.chat.Component.Serializer.fromJson(name);
                    if(text==null){retainInvalid(key,raw);return null;}name=text.getString();
                }catch(RuntimeException malformed){retainInvalid(key,raw);return null;}
            }
            String packet=net.minecraft.network.chat.Component.Serializer.toJson(new net.minecraft.network.chat.TextComponent(name));
            if(name.length()<=32767 && name.getBytes(StandardCharsets.UTF_8).length<=32767
                    && packet.getBytes(StandardCharsets.UTF_8).length<=32767)return name.trim().isEmpty()?"":name;
        }
        retainInvalid(key,raw);return null;
    }



	private static boolean isInventoryList(Tag data) {
		return data instanceof ListTag && (((ListTag)data).size() == 0 || ((ListTag)data).getElementType() == 10);
	}

	private void retainRaw(String source, Tag data) {
		CompoundTag record = new CompoundTag();
		record.putString("Source",source);
		record.put("Data",data.copy());
		if(!inventoryVersions.isEmpty()) record.put("SourceVersions",inventoryVersions.copy());
		unresolvedInventory.add(record);
	}

	private void readInventoryTag(String source, Tag data) {
		if(data == null) return;
		if(!isInventoryList(data)) { retainRaw(source + "List",data); return; }
		ListTag list = (ListTag)data;
		for(int i = 0; i < list.size(); i++) readInventoryEntry(source,list.getCompound(i),null);
	}

	private static Long integral(Tag data) {
		return data instanceof NumericTag && data.getId() >= 1 && data.getId() <= 4 ? ((NumericTag)data).getAsLong() : null;
	}

	private ItemStack decodeInventoryItem(CompoundTag raw) {
		// Ambiguous fields, unavailable IDs and unhandled capability data stay
		// verbatim. Never guess an item, truncate a count or erase custom data.
		for(String key : raw.getAllKeys()) if(!Arrays.asList("id","Count","Damage","tag","ForgeCaps","Slot").contains(key)) return null;
		if(!raw.contains("id",8)) return null;
		Long count = integral(raw.get("Count"));
		Long legacyDamage = raw.contains("Damage") ? integral(raw.get("Damage")) : Long.valueOf(0L);
        Long damage = raw.getCompound("tag").contains("Damage") ? integral(raw.getCompound("tag").get("Damage")) : legacyDamage;
        if(legacyDamage == null || legacyDamage < 0 || legacyDamage > 32767
                || (raw.contains("Damage") && (damage==null || legacyDamage.longValue()!=damage.longValue())))return null;
		if(count == null || count <= 0 || count > 127 || damage == null || damage < 0 || damage > 32767) return null;
		if((raw.contains("tag") && !raw.contains("tag",10)) || (raw.contains("ForgeCaps") && !raw.contains("ForgeCaps",10))) return null;
		try {
			ResourceLocation id = new ResourceLocation(raw.getString("id"));
			if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id)) return null;
			Item registered = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
			if(registered == null || Item.getId(registered) == 0) return null;
			CompoundTag input = raw.copy();
			input.putString("id",id.toString());
			input.putByte("Count",count.byteValue());
			if(raw.contains("Damage"))input.putShort("Damage",legacyDamage.shortValue());
			ItemStack stack = ItemStack.of(input);
			if(emptyStack(stack) || stack.getItem() != registered || stack.getCount() != count.intValue()
					|| stack.getDamageValue() != damage.intValue() || stack.getMaxStackSize() <= 0) return null;
			// Forge16 lazily echoes capability NBT before gathering providers.
			// Resolve them before comparing: unsupported payloads must remain raw,
			// rather than disappearing later during an inventory transaction.
			if(raw.contains("ForgeCaps"))
				stack.getCapability(net.minecraftforge.items.CapabilityItemHandler.ITEM_HANDLER_CAPABILITY);
			CompoundTag encoded = stack.save(new CompoundTag());
            Tag expectedTag=raw.get("tag");
            // Native16 writes an explicit zero for missing tool durability.
            // Allow only that canonical default, retaining every other byte.
            if(registered.isDamageable(stack) && !raw.getCompound("tag").contains("Damage") && damage==0L) {
                CompoundTag canonical=raw.getCompound("tag").copy();canonical.putInt("Damage",0);expectedTag=canonical;
            }
            if(!Objects.equals(expectedTag,encoded.get("tag"))
                    || !Objects.equals(raw.get("ForgeCaps"),encoded.get("ForgeCaps"))) return null;
			return stack;
		} catch(RuntimeException unreadable) { return null; }
	}

	private void readInventoryEntry(String source, CompoundTag raw, CompoundTag retained) {
		CompoundTag context=retained!=null && retained.contains("SourceVersions",10) ? retained.getCompound("SourceVersions") : inventoryVersions;
		CompoundTag converted=ItemMigration.convert(raw,context);
		ItemStack stack = converted==null ? null : decodeInventoryItem(converted);
		if(emptyStack(stack)) {
			if(retained == null) retainRaw(source,raw);
			else {
				CompoundTag copy=retained.copy();
				if(!copy.contains("SourceVersions") && !context.isEmpty()) copy.put("SourceVersions",context.copy());
				unresolvedInventory.add(copy);
			}
			return;
		}
		int limit = Math.min(stack.getMaxStackSize(),getMaxStackSize());
		if(source.equals("Equipment")) {
			Long number = integral(raw.get("Slot"));
			if(number != null && number >= 0 && number < getContainerSize()) {
				int slot = number.intValue();
				if(emptyStack(getItem(slot)) && canPlaceItem(slot,stack)) {
					setItem(slot,stack.split(Math.min(stack.getCount(),slot < 4 ? 1 : limit)));
				}
			}
		}
		// Keep each record, including identical duplicates. Existing overflow
		// handles later GUI recovery without overwriting any indexed slot.
		while(stack.getCount() > 0) auxInventory.addLast(stack.split(Math.min(stack.getCount(),limit)));
	}

	public boolean useThinArms() {
		GameProfile p = this.getGameProfile();
		if(p != null){
			if(p.isLegacy()) return false;
			UUID uid = p.getId();
			if(uid != null){
				return (uid.hashCode() & 1) == 1;
			}
		}
		return false;
	}
}
