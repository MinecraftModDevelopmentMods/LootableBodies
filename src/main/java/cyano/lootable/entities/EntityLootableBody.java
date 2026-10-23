package cyano.lootable.entities;

import static cyano.lootable.ItemStacks.emptyStack;
import cyano.lootable.upgrade.UpgradePreservation;
import cyano.lootable.upgrade.ItemMigration;

import com.mojang.authlib.GameProfile;
import cyano.lootable.LootableBodies;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.CreatureAttribute;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.INBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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


public class EntityLootableBody extends EntityLiving implements IInventory, net.minecraft.world.IInteractionObject{


	public static final EntityEquipmentSlot[] EQUIPMENT_SLOTS = new EntityEquipmentSlot[] {EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET, EntityEquipmentSlot.MAINHAND, EntityEquipmentSlot.OFFHAND};

	final static byte VACUUM_TIMELIMIT = 20;
	final static int VACUUM_RADIUS = 3;

	private final NonNullList<ItemStack> mainInventory = NonNullList.withSize(6*7, ItemStack.EMPTY);
	private final Deque<ItemStack> auxInventory = new LinkedList<>();
	private static final String UNRESOLVED_ITEMS_TAG = "LootableBodiesUnresolved";
	private final List<NBTTagCompound> unresolvedInventory = new ArrayList<>();
	private NBTTagCompound sourceVersions = new NBTTagCompound();
	private NBTTagCompound inventoryVersions = new NBTTagCompound();
	private static final String INVALID_FIELDS_TAG = "LootableBodiesInvalidFields";
	// Evidence about metadata is separate from inventory and must not prevent decay.
	private final List<NBTTagCompound> invalidFields = new ArrayList<>();
	private final Map<String,Double> defaultAttributeBases = new HashMap<>();
	private final Map<String,List<AttributeModifier>> defaultAttributeModifiers = new HashMap<>();
	private byte vacuumTime = 0;
	private boolean positionNeedsChunk;
	private boolean malformedPosition;
	private INBTBase loadedPosition;
	private boolean missingPositionX, missingPositionZ;
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


	public EntityLootableBody(World w) {
		super(LootableBodies.CORPSE,w);
		// Keep EntityLiving's actual defaults (including follow range 16 and
		// configured corpse health), rather than the generic attribute defaults.
		for(IAttributeInstance attribute : getAttributeMap().getAllAttributes()) {
			String name = attribute.getAttribute().getName();
			defaultAttributeBases.put(name,attribute.getBaseValue());
			defaultAttributeModifiers.put(name,new ArrayList<>(attribute.getModifiers()));
		}
		deathTimestamp = w.getGameTime();
		decayTimestamp = deathTimestamp;
        // Local display preferences must not become world/server entity state.
        // Existing saved visibility flags are still read unchanged by vanilla.
		this.setCustomNameVisible(true);
		this.setSize(0.85F, 0.75F);

		this.isImmuneToFire = LootableBodies.completelyInvulnerable || (!LootableBodies.hurtByEnvironment);

	}

	public EntityLootableBody(EntityPlayer player){
		this(player.getEntityWorld());
		this.setPosition(player.posX, player.posY+0.5,  player.posZ);

		this.motionX = player.motionX;
		this.motionY = player.motionY+0.0784000015258789;
		this.motionZ = player.motionZ;

		//this.newPosX = posX; // no newPosX?
		//this.newPosY = posY; // no newPosY?
		//this.newPosZ = posZ; // no newPosZ?
		this.markDirty();
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
		if(!getEntityWorld().isRemote) {
			// A Forge ticket can tick our centre chunk while neighbouring support
			// terrain is absent. Never treat those missing collision boxes as air.
			AxisAlignedBB bounds = getBoundingBox();
			BlockPos from = new BlockPos(bounds.minX + Math.min(0D,motionX), 0,
					bounds.minZ + Math.min(0D,motionZ));
			BlockPos to = new BlockPos(bounds.maxX + Math.max(0D,motionX), 0,
					bounds.maxZ + Math.max(0D,motionZ));
			if(!getEntityWorld().isAreaLoaded(from,to)) return;
		}
		super.tick();
	}

	@Override
	public void baseTick(){
		//log("position=%s; terminate=%s; time=%s; age=%s; deathTimestamp=%s; decayTimestamp=%s; Health=%s; isDead=%s" ,getPosition(),terminate,getEntityWorld().getTotalWorldTime(),getEntityWorld().getTotalWorldTime() - deathTimestamp, deathTimestamp, decayTimestamp, getHealth(), isDead);
		super.baseTick();
		final boolean isClient = getEntityWorld().isRemote;
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
				getEntityWorld().removeEntity(this);
			}
			return;
		}
		if(isServer && terminate < 0 && (this.getHealth() <= 0 || this.removed)){
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
		final long currentTime = Math.max(0L,getEntityWorld().getGameTime());
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
				this.onKillCommand();
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
			for (int slot = this.getSizeInventory() - 1; slot >= EQUIPMENT_SLOTS.length; slot--) {
				if(emptyStack(this.getStackInSlot(slot))){
					if(emptyStack(mergeItem(slot,auxInventory.peekFirst()))) auxInventory.removeFirst();
					this.markDirty();
					break;
				}
			}
		}

		// handle item vacuuming
		if(isServer && vacuumTime < VACUUM_TIMELIMIT){
			vacuumTime++;
			List<EntityItem> items = getEntityWorld().getEntitiesWithinAABB(EntityItem.class, new AxisAlignedBB(
					posX - VACUUM_RADIUS, posY - VACUUM_RADIUS, posZ - VACUUM_RADIUS,
					posX + VACUUM_RADIUS, posY + VACUUM_RADIUS, posZ + VACUUM_RADIUS));
			for(EntityItem item : items){
				if(item.removed) continue;
				ItemStack stack = item.getItem();
				this.addItem(stack);
				getEntityWorld().removeEntity(item);
			}
		}

		if(isServer && LootableBodies.corpseBuoyancy && vacuumTime >= VACUUM_TIMELIMIT && (this.inWater || this.isInLava())) {
			this.motionY += 0.025D;
			this.motionY *= 0.9;
		}
		
		if(isServer && LootableBodies.voidPlatform && this.posY < 0) {
			generateVoidPlatform();
			this.setPositionAndUpdate((int)this.posX, 1.5, (int)this.posZ);
		}

	}

	@Override
	public boolean handleWaterMovement() {
		if (this.handleFluidAcceleration(net.minecraft.tags.FluidTags.WATER)) 		{
			this.fallDistance = 0.0F;
			this.inWater = true;
			this.extinguish();
		} else {
			this.inWater = false;
		}
		
		return this.inWater;
	}
	
	private void generateVoidPlatform() {
		World world = this.world;
		BlockPos testPos = new BlockPos((int)this.posX, 0, (int)this.posZ);
		
		IBlockState platformBlockstate = getPlatformBlock(this.dimension.getId());
		
		for(int j = -1; j <= 1; j++) {
			for(int k = -1; k <= 1; k++)	{
				if(world.isAirBlock(testPos.add(j,0,k)))
					world.setBlockState(testPos.add(j,0,k), platformBlockstate, 3);
			}
		}
	}
	
	private IBlockState getPlatformBlock(int i) {
		switch(LootableBodies.dimensionSpecificPlatform ? i : 0) {
			case -1:
				return Blocks.NETHERRACK.getDefaultState();
			case 1:
				return Blocks.END_STONE.getDefaultState();
			default:
				return Blocks.COBBLESTONE.getDefaultState();
		}
	}
	
	private void decayItems(int amount) {
		for(int slot = 0; slot < this.getSizeInventory(); slot++){
			this.setInventorySlotContents(slot,this.decayItem(this.getStackInSlot(slot), amount));
		}
		for(ItemStack item : auxInventory){
			decayItem(item, amount);
		}
	}

	private ItemStack decayItem(ItemStack item, int amount) {
		if(!emptyStack(item)
				&& item.getCount() == 1
				&& item.isDamageable()
				&& item.getItem().isDamageable()){
			int durabilityRemaining = item.getMaxDamage() - item.getDamage() - 1;
			if(durabilityRemaining > 0){
				item.damageItem(Math.min(durabilityRemaining,amount),this);
			}
		}
		return item;
	}

	public void addItem(ItemStack stack) {
		for(int slot = EQUIPMENT_SLOTS.length; slot < this.getSizeInventory(); slot++){
			stack = mergeItem(slot,stack);
			if(emptyStack(stack)) return;
		}
		auxInventory.addLast(stack);
		markDirty();
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
		removed = false;
		setHealth(getMaxHealth());
		vacuumTime = VACUUM_TIMELIMIT;
		markDirty();
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
        markDirty();
    }

	public ItemStack mergeItem(int slot, ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0) return ItemStack.EMPTY;
		ItemStack current = this.getStackInSlot(slot);
		final int inventorySizeLimit = Math.min(stack.getMaxStackSize(),this.getInventoryStackLimit());
		if(emptyStack(current)){
			int amount = Math.min(stack.getCount(),inventorySizeLimit);
			if(amount > 0) this.setInventorySlotContents(slot,stack.split(amount));
			return stack.getCount() > 0 ? stack : ItemStack.EMPTY;
		} else if(current.getCount() < inventorySizeLimit
				&& ItemStack.areItemsEqual(stack,current)
				&& ItemStack.areItemStackTagsEqual(stack,current)){
			int delta = Math.min(stack.getCount(),inventorySizeLimit - current.getCount());
			current.grow(delta);
			stack.shrink(delta);
			this.setInventorySlotContents(slot,current);
			if(stack.getCount() <= 0) return ItemStack.EMPTY;
			return stack;
		}
		return stack;
	}


	@Override
	protected void damageEntity(DamageSource src, float amount){
		String type = String.valueOf(src == null ? null : src.getDamageType()); // null protection
		if(src instanceof EntityDamageSource && src.getTrueSource() instanceof EntityPlayer){
			EntityPlayer player = (EntityPlayer)src.getTrueSource();
			ItemStack item = player.getHeldItem(EnumHand.MAIN_HAND);
			if(!emptyStack(item)) {
				if (item.getItem() instanceof ItemSpade || item.getItem().getToolTypes(item).contains(net.minecraftforge.common.ToolType.SHOVEL)) {
					this.onKillCommand();
				}
			}
		}
		if(type.equals(DamageSource.OUT_OF_WORLD.getDamageType())) {
			super.damageEntity(src,amount); // kill command and falling out of the world
			return;
		}

		if(type.equals(DamageSource.IN_WALL.getDamageType())){
			this.jumpOutOfWall();
		}

		// damage handling
		if(LootableBodies.completelyInvulnerable) return;

		if(src instanceof EntityDamageSource ){
			if(LootableBodies.hurtByAttacks) super.damageEntity(src,amount);
			return;
		}

		if(matchesAny(type, DamageSource.ANVIL, DamageSource.CACTUS, DamageSource.FALL, DamageSource.FALLING_BLOCK, DamageSource.FLY_INTO_WALL, DamageSource.IN_FIRE, DamageSource.LAVA, DamageSource.MAGIC, DamageSource.LIGHTNING_BOLT, DamageSource.ON_FIRE)){
			if(LootableBodies.hurtByEnvironment)super.damageEntity(src,amount);
			return;
		}

		if(LootableBodies.hurtByMisc){
			super.damageEntity(src,amount);
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
    public void setCustomNameTag(String name){setCustomName(name==null || name.isEmpty()?null:new net.minecraft.util.text.TextComponentString(name));}

    public void refreshClientSkin() {
        GameProfile profile = gpSwap.get();
        if (getEntityWorld().isRemote && !LootableBodies.useLocalSkin && profile != null && profile.getId() == null)
            resolveProfile(profile);
    }
	private void resolveProfile(GameProfile initial) {
		try {
			PROFILE_LOOKUPS.execute(() -> {
				try {
					GameProfile resolved = TileEntitySkull.updateGameProfile(initial);
					// A rename, reload or explicit profile update wins over a late lookup.
					if(resolved != null) gpSwap.compareAndSet(initial,resolved);
				} catch(RuntimeException unavailable) {
					ModLog.fine("Corpse profile lookup unavailable; using default skin");
				}
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


	@Override
	protected void registerAttributes() {
		// called during constructor of EntityLivingBase
		super.registerAttributes();
		this.getAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(LootableBodies.corpseHP);
	}

	public float getRotation(){
		return this.rotationYaw;
	}

	public void setRotation(float newRot){
		this.rotationYaw = newRot;
	}


	@Override
	public boolean canDespawn() {
		return false;
	}

	public void jumpOutOfWall(){
		if(world.isRemote) return;
		AxisAlignedBB bounds = getBoundingBox();
		// Collision queries omit unloaded chunks. They cannot establish an exit
		// until the complete local terrain (including tall neighbour shapes) exists.
		if(!escapeTerrainLoaded(bounds) || world.isCollisionBoxesEmpty(this,bounds)) return;
		BlockPos currentCoord = getPosition();
		double x = currentCoord.getX()+0.5 - posX;
		double z = currentCoord.getZ()+0.5 - posZ;
		double length = Math.hypot(x,z);
		// At a block centre there is no horizontal direction to normalize.
		if(length > 0 && tryWallExit(posX+x/length,posY,posZ+z/length)) return;

		// Retain the original adjacent-exit priority, but check the whole body.
		for(EnumFacing direction : new EnumFacing[]{EnumFacing.UP,EnumFacing.NORTH,
				EnumFacing.EAST,EnumFacing.SOUTH,EnumFacing.WEST,EnumFacing.DOWN}) {
			BlockPos exit = currentCoord.offset(direction);
			if(tryWallExit(exit.getX()+0.5,exit.getY()+0.015625,exit.getZ()+0.5)) return;
		}
		// A sealed corpse must remain recoverable here, rather than wander through
		// solid terrain or load chunks looking for an unchecked random destination.
	}

	private boolean escapeTerrainLoaded(AxisAlignedBB bounds) {
		return world.isAreaLoaded(new BlockPos(bounds.minX-1,0,bounds.minZ-1),
				new BlockPos(bounds.maxX+1,0,bounds.maxZ+1));
	}

	private boolean tryWallExit(double x,double y,double z) {
		AxisAlignedBB bounds = getBoundingBox().offset(x-posX,y-posY,z-posZ);
		if(bounds.minY < 0 || bounds.maxY > world.getHeight() ||
				!world.getWorldBorder().contains(bounds) || !escapeTerrainLoaded(bounds) ||
				!world.isCollisionBoxesEmpty(this,bounds)) return false;
		setPosition(x,y,z);
		return true;
	}


	@Override
	public void onKillCommand() {
		if(terminate < 0) terminate = DEATH_COUNTDOWN;
		this.attackEntityFrom(DamageSource.OUT_OF_WORLD, this.getMaxHealth());
		this.markDirty();
	}


	@Override
	public int getTalkInterval() {
		return 1200;
	}
	@Override
	public void playAmbientSound() {
		// do nothing
	}



	@Override
	protected int getExperiencePoints(final EntityPlayer p_getExperiencePoints_1_) {
		return 0;
	}


	@Override
	protected SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected Item getDropItem() {
		return null;
	}

	@Override
	protected void dropEquipment(boolean wasRecentlyHit, int lootingModifier) {
		// Corpse equipment belongs to the inventory release path, not mob loot.
	}

	@Override
	public boolean canBeSteered() {
		return false;
	}


	@Override
	public boolean canBeLeashedTo(EntityPlayer player) {
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
	public int getSizeInventory() {
		return EQUIPMENT_SLOTS.length + mainInventory.size();
	}

	/**
	 * Returns the stack in the given slot.
	 *
	 * @param index
	 */
	@Override
	public ItemStack getStackInSlot(int index) {
		if(index < EQUIPMENT_SLOTS.length){
			return super.getItemStackFromSlot(EQUIPMENT_SLOTS[index]);
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
	public void setInventorySlotContents(int index, ItemStack stack) {
        if(stack == null) stack = ItemStack.EMPTY;
		if(index < EQUIPMENT_SLOTS.length){
			super.setItemStackToSlot(EQUIPMENT_SLOTS[index], stack);
		} else {
			mainInventory.set(index - EQUIPMENT_SLOTS.length, stack);
		}
		markDirty();
	}

	/**
	 * Removes a stack from the given slot and returns it.
	 *
	 * @param index
	 */
	@Override
	public ItemStack removeStackFromSlot(int index) {
		ItemStack i = this.getStackInSlot(index);
		this.setInventorySlotContents(index,null);
		return i;
	}

	/**
	 * Removes up to a specified number of items from an inventory slot and returns them in a new stack.
	 *
	 * @param index
	 * @param count
	 */
	@Override
	public ItemStack decrStackSize(int index, int count) {
		ItemStack i = this.getStackInSlot(index);
		if(emptyStack(i) || count <= 0) return ItemStack.EMPTY;
		ItemStack result = i.split(count);
		if (i.getCount() <= 0) {
			i = ItemStack.EMPTY;
		}
		this.setInventorySlotContents(index, i);
		return result;
	}

	/**
	 * Returns the maximum stack size for a inventory slot. Seems to always be 64, possibly will be extended.
	 */
	@Override
	public int getInventoryStackLimit() {
		return 64;
	}

	/**
	 * For tile entities, ensures the chunk containing the tile entity is saved to disk later - the game won't think it
	 * hasn't changed and skip it.
	 */
	@Override
	public void markDirty() {
		if(world != null && !world.isRemote && addedToChunk) {
			world.markChunkDirty(getPosition(),null);
		}
	}

	/**
	 * Do not make give this method the name canInteractWith because it clashes with Container
	 *
	 * @param player
	 */
	@Override
	public boolean isUsableByPlayer(EntityPlayer player) {
		return player != null && player.isAlive() && this.isAlive()
				&& terminate < 0 && player.getEntityWorld() == getEntityWorld()
				&& player.getPosition().distanceSq(this.getPosition()) < 25;
	}

	@Override
	public boolean processInteract(EntityPlayer player, EnumHand hand){
		if(isUsableByPlayer(player)) {
			GUIHandler.open(player,this);
			return true;
		}
		return false;
	}

    @Override public net.minecraft.inventory.Container createContainer(net.minecraft.entity.player.InventoryPlayer inventory, EntityPlayer player){return new cyano.lootable.graphics.CorpseContainer(inventory,this);}
    @Override public String getGuiID(){return "lootablebodies:corpse";}

	@Override
	public void openInventory(EntityPlayer player) {
	}

	@Override
	public void closeInventory(EntityPlayer player) {
	}

	/**
	 * Returns true if automation is allowed to insert the given stack (ignoring stack size) into the given slot.
	 *
	 * @param index
	 * @param stack
	 */
	@Override
	public boolean isItemValidForSlot(int index, ItemStack stack) {
		if(emptyStack(stack) || stack.getCount() <= 0) return false;
		if(index < 4){
			return stack.canEquip(EQUIPMENT_SLOTS[index], this);
		} else {
			return true;
		}
	}

	@Override
	public int getField(int id) {
		return 0;
	}

	@Override
	public void setField(int id, int value) {
		// do nothing
	}

	@Override
	public int getFieldCount() {
		return 0;
	}

	@Override
	public void clear() {
		for(int i = 0; i < EQUIPMENT_SLOTS.length; i++){
			this.setInventorySlotContents(i,null);
		}
		mainInventory.clear();
		auxInventory.clear();
		unresolvedInventory.clear();
	}

	////////// End of IInventory //////////

	public boolean isEmpty(){
		if(!auxInventory.isEmpty() || !unresolvedInventory.isEmpty()) return false;
		for(int slot = 0; slot < this.getSizeInventory(); slot++){
			if(!emptyStack(this.getStackInSlot(slot))) return false;
		}
		return true;
	}

	private boolean dropAllItems(){
		for(int slot = 0; slot < getSizeInventory(); slot++){
			ItemStack stack = getStackInSlot(slot);
			if(!emptyStack(stack) && getEntityWorld().spawnEntity(
					new EntityItem(getEntityWorld(),posX,posY,posZ,stack.copy()))) {
				setInventorySlotContents(slot,null);
			}
		}
		Iterator<ItemStack> iterator = auxInventory.iterator();
		while(iterator.hasNext()) {
			ItemStack stack = iterator.next();
			if(emptyStack(stack) || getEntityWorld().spawnEntity(
					new EntityItem(getEntityWorld(),posX,posY,posZ,stack.copy()))) iterator.remove();
		}
		markDirty();
		return isEmpty();
	}

	private static boolean matchesAny(String damageType, DamageSource... list){
		for(int i = 0; i < list.length; i++){
			if(damageType.equalsIgnoreCase(list[i].damageType)) return true;
		}
		return false;
	}

	@Override
	public CreatureAttribute getCreatureAttribute()
	{
		return CreatureAttribute.UNDEAD;
	}


	@Override
	public void writeAdditional(final NBTTagCompound root) {
		UpgradePreservation.clearTransient(root);
		root.setInt(UpgradePreservation.VERSION,UpgradePreservation.DATA_VERSION);
		if(sourceVersions.isEmpty()) root.removeTag(UpgradePreservation.SOURCES);
		else root.setTag(UpgradePreservation.SOURCES,sourceVersions.copy());
		root.removeTag("ActiveEffects");
		super.writeAdditional(root);
		final NBTTagList equipmentListTag = new NBTTagList();
		for (int i = 0; i < this.getSizeInventory(); i++) {
			ItemStack item = this.getStackInSlot(i);
			if (!emptyStack(item)) {
				NBTTagCompound slotTag = new NBTTagCompound();
				slotTag.setByte("Slot", (byte)i);
				item.write(slotTag);
				equipmentListTag.add(slotTag);
			}
		}
		root.setTag("Equipment", equipmentListTag);

		root.removeTag("Aux");
		if(!this.auxInventory.isEmpty()){
			final NBTTagList nbtauxtaglist = new NBTTagList();
			Iterator<ItemStack> iter = this.auxInventory.iterator();
			while (iter.hasNext()) {
				ItemStack i = iter.next();
				if(emptyStack(i)) continue;
				final NBTTagCompound nbttagcompound1 = new NBTTagCompound();
				i.write(nbttagcompound1);
				nbtauxtaglist.add(nbttagcompound1);
			}
			root.setTag("Aux", nbtauxtaglist);
		}
		root.removeTag(UNRESOLVED_ITEMS_TAG);
		if(!unresolvedInventory.isEmpty()) {
			NBTTagList retained = new NBTTagList();
			for(NBTTagCompound record : unresolvedInventory) retained.add(record.copy());
			root.setTag(UNRESOLVED_ITEMS_TAG,retained);
		}
		root.removeTag(INVALID_FIELDS_TAG);
		if(!invalidFields.isEmpty()) {
			NBTTagList retained = new NBTTagList();
			for(NBTTagCompound record : invalidFields) retained.add(record.copy());
			root.setTag(INVALID_FIELDS_TAG,retained);
		}
		root.removeTag("Vac");
		if(vacuumTime < VACUUM_TIMELIMIT)root.setByte("Vac", vacuumTime);
		root.removeTag("Name");
		String name = getCustomNameTag();
		if(name != null && !name.trim().isEmpty()) root.setString("Name",name);
		else root.removeTag("CustomName");
		root.setLong("DeathTime", this.getDeathTimestamp());
		root.setLong("DecayTime",this.decayTimestamp);
	}


	@Override
	public void read(final NBTTagCompound supplied) {
		final NBTTagCompound root=UpgradePreservation.restore(supplied);
		// Entity reads geometry before our inventory helper. Sanitize a copy
		// before it builds bounding boxes or derives previous-tick positions.
		NBTTagCompound clean = root.copy();
		boolean[] badPosition = new boolean[4], badMotion = new boolean[4];
		boolean[] badRotation = new boolean[3];
		clean.setTag("Pos",readVector(root.getTag("Pos"),false,badPosition));
		clean.setTag("Motion",readVector(root.getTag("Motion"),true,badMotion));
		clean.setTag("Rotation",readRotation(root.getTag("Rotation"),badRotation));
		Float distance = finiteFloat(root.getTag("FallDistance"));
		boolean badFall = root.hasKey("FallDistance") && (distance == null || distance < 0F);
        clean.setFloat("FallDistance",distance != null && distance >= 0F ? distance : 0F);
        // Vanilla expects JSON here. Our attribution reader owns compatibility
        // and retains malformed input without sending it to vanilla's parser.
        clean.removeTag("CustomName");
        super.read(clean);
        String fallback=readName(root,"CustomName"),owner=readName(root,"Name");
        setUserName(owner!=null?owner:fallback!=null?fallback:"");
		// A repeated load must not interpolate from obsolete head/body angles.
		prevRotationYaw = rotationYaw; prevRotationPitch = rotationPitch;
		setRotationYawHead(rotationYaw); prevRotationYawHead = rotationYaw;
		setRenderYawOffset(rotationYaw); prevRenderYawOffset = rotationYaw;
		positionNeedsChunk = true;
		malformedPosition = hasInvalidComponent(badPosition);
		loadedPosition = root.hasKey("Pos") ? root.getTag("Pos").copy() : null;
		missingPositionX = badPosition[0]; missingPositionZ = badPosition[2];
		if(malformedPosition && root.hasKey("Pos")) retainInvalid("Pos",root.getTag("Pos"));
		if(hasInvalidComponent(badMotion) && root.hasKey("Motion")) retainInvalid("Motion",root.getTag("Motion"));
		if(hasInvalidComponent(badRotation) && root.hasKey("Rotation")) retainInvalid("Rotation",root.getTag("Rotation"));
		if(badFall) retainInvalid("FallDistance",root.getTag("FallDistance"));
	}

	private static Float finiteFloat(INBTBase raw) {
		Double number = finiteNumber(raw);
		if(number == null) return null;
		float value = number.floatValue();
		return Float.isFinite(value) && (number == 0D || value != 0F) ? value : null;
	}

	private static NBTTagList readRotation(INBTBase raw, boolean[] invalid) {
		NBTTagList list = raw instanceof NBTTagList ? (NBTTagList)raw : null;
		invalid[2] = list == null || list.size() != 2 || list.getTagType() < 1 || list.getTagType() > 6;
		NBTTagList clean = new NBTTagList();
		for(int axis = 0; axis < 2; axis++) {
			Float angle = list != null && axis < list.size() ? finiteFloat(list.get(axis)) : null;
			if(angle == null) { invalid[axis] = true; angle = 0F; }
			// Preserve the legacy float remainder and the corpse's existing
			// flat pitch before vanilla copies angles into head/render state.
			clean.add(new net.minecraft.nbt.NBTTagFloat(axis == 0 ? angle % 360F : 0F));
		}
		return clean;
	}

	private static boolean hasInvalidComponent(boolean[] invalid) {
		for(boolean value : invalid) if(value) return true;
		return false;
	}

	private static NBTTagList readVector(INBTBase raw, boolean motion, boolean[] invalid) {
		NBTTagList list = raw instanceof NBTTagList ? (NBTTagList)raw : null;
		invalid[3] = list == null || list.size() != 3 || list.getTagType() < 1 || list.getTagType() > 6;
		NBTTagList clean = new NBTTagList();
		for(int axis = 0; axis < 3; axis++) {
			Double value = list != null && axis < list.size() ? finiteNumber(list.get(axis)) : null;
			if(value == null || Math.abs(value) > (motion ? 10D : 3.0E7D)) {
				invalid[axis] = true;
				// A lost height must not put recoverable inventory in the void.
				value = !motion && axis == 1 ? 64D : 0D;
			}
			clean.add(new net.minecraft.nbt.NBTTagDouble(value));
		}
		return clean;
	}

	/** Complete a malformed NBT position using the authoritative containing chunk. */
	public void recoverPositionInChunk(int chunkX, int chunkZ) {
		if(!positionNeedsChunk) return;
		positionNeedsChunk = false;
		boolean wrongX = net.minecraft.util.math.MathHelper.floor(posX / 16D) != chunkX;
		boolean wrongZ = net.minecraft.util.math.MathHelper.floor(posZ / 16D) != chunkZ;
		if(!malformedPosition && !wrongX && !wrongZ) { loadedPosition = null; return; }
		if(!malformedPosition && loadedPosition != null) retainInvalid("Pos",loadedPosition);
		loadedPosition = null;
		double x = missingPositionX || wrongX ? chunkX * 16D + 8.5D : posX;
		double z = missingPositionZ || wrongZ ? chunkZ * 16D + 8.5D : posZ;
		// Chunk.addEntity flags a location mismatch dead immediately before
		// EnteringChunk. Only the first pending NBT admission may undo that
		// flag; saved zero health and normal later removals remain effective.
		if(wrongX || wrongZ) removed = false;
		setLocationAndAngles(x,posY,z,rotationYaw,rotationPitch);
		ModLog.warning("Lootable Bodies recovered malformed corpse position in chunk %s,%s",chunkX,chunkZ);
	}

	@Override
	public void readAdditional(final NBTTagCompound supplied) {
		final NBTTagCompound root=UpgradePreservation.restore(supplied);
		sourceVersions=root.contains(UpgradePreservation.SOURCES,10) ? root.getCompound(UpgradePreservation.SOURCES).copy() : new NBTTagCompound();
		// Persisted Equipment/Aux are already current after the first save.
		// Only the transient protected original and retained raw records use old versions.
		inventoryVersions=root.getBoolean(UpgradePreservation.ITEMS_PENDING) ? sourceVersions : new NBTTagCompound();
		// Replace, rather than append to, an existing inventory on a repeated load.
		this.clear();
		// These flags are not saved. A reused entity must behave like a fresh
		// load; its new Health still decides whether normal death handling starts.
		terminate = -1;
		dead = false;
		removed = false;
		// Item readers may update their input tags; the caller's saved data is immutable.
		NBTTagCompound vanilla = root.copy();
		// Our historical DeathTime is a world timestamp, not vanilla's animation.
		vanilla.setShort("DeathTime",(short)0);
		invalidFields.clear();
		INBTBase metadata = root.getTag(INVALID_FIELDS_TAG);
		if(isInventoryList(metadata)) {
			NBTTagList records = (NBTTagList)metadata;
			for(int i = 0; i < records.size(); i++) invalidFields.add(records.getCompound(i).copy());
		} else if(metadata != null) retainInvalid("Recovery",metadata);
		// NBT replaces state. Clear the old map without effect callbacks: saved
		// attributes and absorption are restored below, just as on a fresh entity.
		getActivePotionEffects().clear();
		for(IAttributeInstance attribute : getAttributeMap().getAllAttributes()) restoreAttribute(attribute);
		vanilla.setTag("Attributes",readAttributes(root.getTag("Attributes")));
		vanilla.setTag("ActiveEffects",readEffects(root.getTag("ActiveEffects")));
		vanilla.setShort("HurtTime",(short)readTimer(root,"HurtTime",Short.MAX_VALUE,0));
		// Health must be checked against the loaded, validated effective maximum.
		vanilla.removeTag("Health");
		vanilla.setFloat("AbsorptionAmount",readLivingFloat(root,"AbsorptionAmount",0F,Float.MAX_VALUE));
		super.readAdditional(vanilla);
		updatePotionMetadata();
		for(IAttributeInstance attribute : getAttributeMap().getAllAttributes()) {
			if(!Double.isFinite(attribute.getValue())) {
				// Also protect against engine ordering/parent-attribute arithmetic.
				if(root.hasKey("Attributes")) retainInvalid("Attributes",root.getTag("Attributes"));
				restoreAttribute(attribute);
			}
		}
		setHealth(readLivingFloat(root,"Health",getMaxHealth(),getMaxHealth()));
		INBTBase equipment = root.getTag("Equipment");
		if(isInventoryList(equipment)) {
			// Vanilla ArmorItems/HandItems mirror these six slots. The custom
			// list is authoritative when present, so do not load both copies.
			for(int i = 0; i < EQUIPMENT_SLOTS.length; i++) setInventorySlotContents(i,null);
		}
		readInventoryTag("Equipment",equipment);
		readInventoryTag("Aux",root.getTag("Aux"));
		INBTBase recovery = root.getTag(UNRESOLVED_ITEMS_TAG);
		if(isInventoryList(recovery)) {
			NBTTagList records = (NBTTagList)recovery;
			for(int i = 0; i < records.size(); i++) {
				NBTTagCompound record = records.getCompound(i);
				String source = record.getString("Source");
				if((record.keySet().size() == 2 || (record.keySet().size() == 3 && record.contains("SourceVersions",10)))
						&& record.contains("Source",8) && record.contains("Data",10)
						&& (source.equals("Equipment") || source.equals("Aux"))) {
					readInventoryEntry(source,record.getCompound("Data"),record);
				} else unresolvedInventory.add(record.copy());
			}
		} else if(recovery != null) retainRaw("Recovery",recovery);
		if(!unresolvedInventory.isEmpty()) ModLog.warning(
				"Lootable Bodies retained %s unreadable inventory records for later recovery",unresolvedInventory.size());

		long now = Math.max(0L,getEntityWorld().getGameTime());
		this.vacuumTime = (byte)readTimer(root,"Vac",VACUUM_TIMELIMIT,VACUUM_TIMELIMIT);
		this.setDeathTimestamp(readTimer(root,"DeathTime",now,now));
		this.decayTimestamp = readTimer(root,"DecayTime",now,now);
		String fallback = readName(root,"CustomName");
		String name = readName(root,"Name");
		this.setUserName(name != null ? name : fallback != null ? fallback : "");
		this.setGameProfile(null);
		this.oldName = null;
		this.setRotation(this.rotationYaw, 0);
	}

	private void retainInvalid(String source, INBTBase data) {
		NBTTagCompound record = new NBTTagCompound();
		record.setString("Source",source);
		record.setTag("Data",data.copy());
		if(!invalidFields.contains(record)) invalidFields.add(record);
	}

	private void restoreAttribute(IAttributeInstance attribute) {
		String name = attribute.getAttribute().getName();
		for(AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) attribute.removeModifier(modifier);
		attribute.setBaseValue(defaultAttributeBases.getOrDefault(name,attribute.getAttribute().getDefaultValue()));
		List<AttributeModifier> defaults = defaultAttributeModifiers.get(name);
		if(defaults != null) for(AttributeModifier modifier : defaults) attribute.applyModifier(modifier);
	}

	private NBTTagList readEffects(INBTBase raw) {
		NBTTagList clean = new NBTTagList();
		if(raw == null) return clean;
		if(!isInventoryList(raw)) { retainInvalid("ActiveEffects",raw); return clean; }
		Set<Potion> seen = new HashSet<>();
		NBTTagList list = (NBTTagList)raw;
		for(int i = 0; i < list.size(); i++) {
			NBTTagCompound record = list.getCompound(i);
			INBTBase rawId = record.getTag("Id"), rawAmplifier = record.getTag("Amplifier");
			Long id = integral(rawId);
			// IDs are unsigned bytes on disk. Amplifiers are read as signed
			// bytes by this engine; preserve all canonical byte encodings.
			if(rawId != null && rawId.getId() == 1) id = id & 255L;
			Long amplifier = rawAmplifier == null ? Long.valueOf(0) : integral(rawAmplifier);
			Long duration = record.hasKey("Duration") ? integral(record.getTag("Duration")) : Long.valueOf(0);
			Potion potion = id != null && id >= 0 && id <= 255 ? Potion.getPotionById(id.intValue()) : null;
			if(potion == null || duration == null || duration < 0 || duration > Integer.MAX_VALUE
					|| amplifier == null || amplifier > 127 || (amplifier < 0 && rawAmplifier.getId() != 1)
					|| !seen.add(potion)) {
				retainInvalid("ActiveEffect",record); continue;
			}
			NBTTagCompound accepted = new NBTTagCompound();
			accepted.setByte("Id",id.byteValue()); accepted.setInt("Duration",duration.intValue());
			accepted.setByte("Amplifier",amplifier.byteValue());
			boolean changed = !Arrays.asList("Id","Duration","Amplifier","Ambient","ShowParticles","ShowIcon","CurativeItems").containsAll(record.keySet());
			// Forge 1.11 persists curative stacks. Preserve their complete custom
			// data; an unreadable list uses Forge's defaults and stays recoverable.
			if(record.hasKey("CurativeItems")) {
				INBTBase rawCuratives = record.getTag("CurativeItems");
				boolean valid = isInventoryList(rawCuratives);
				if(valid) {
					NBTTagList items = (NBTTagList)rawCuratives;
					for(int c=0; c<items.size(); c++)
						if(decodeInventoryItem(items.getCompound(c)) == null) { valid=false; break; }
				}
				if(valid) accepted.setTag("CurativeItems",rawCuratives.copy());
				else changed = true;
			}
			for(String key : Arrays.asList("Ambient","ShowParticles","ShowIcon")) {
				boolean fallback = key.equals("ShowIcon") ? accepted.getBoolean("ShowParticles") : key.equals("ShowParticles");
				Long flag = integral(record.getTag(key));
				if(!record.hasKey(key)) accepted.setBoolean(key,fallback);
				else if(flag != null && flag >= 0 && flag <= 1) accepted.setBoolean(key,flag == 1);
				else { accepted.setBoolean(key,fallback); changed = true; }
			}
			if(changed) retainInvalid("ActiveEffect",record);
			clean.add(accepted);
		}
		return clean;
	}

	private static Double finiteNumber(INBTBase raw) {
		if(!(raw instanceof NBTPrimitive) || raw.getId() < 1 || raw.getId() > 6) return null;
		double value = ((NBTPrimitive)raw).getDouble();
		return Double.isFinite(value) ? value : null;
	}

	private float readLivingFloat(NBTTagCompound root, String key, float fallback, float maximum) {
		INBTBase raw = root.getTag(key);
		if(raw == null) return fallback;
		Double value = finiteNumber(raw);
		if(value != null && value >= 0 && value <= maximum) {
			float narrowed = value.floatValue();
			if(Float.isFinite(narrowed) && (value == 0 || narrowed > 0)) return narrowed;
		}
		retainInvalid(key,raw);
		return fallback;
	}

	private NBTTagList readAttributes(INBTBase raw) {
		NBTTagList clean = new NBTTagList();
		if(raw == null) return clean;
		if(!isInventoryList(raw)) { retainInvalid("Attributes",raw); return clean; }
		Set<String> seen = new HashSet<>();
		NBTTagList list = (NBTTagList)raw;
		for(int i = 0; i < list.size(); i++) {
			NBTTagCompound record = list.getCompound(i);
			IAttributeInstance attribute = record.contains("Name",8)
					? getAttributeMap().getAttributeInstanceByName(record.getString("Name")) : null;
			Double base = finiteNumber(record.getTag("Base"));
			if(attribute == null || base == null) { retainInvalid("Attribute",record); continue; }
			String name = attribute.getAttribute().getName();
			// corpse_HP has always allowed 1..Short.MAX_VALUE. Preserve a base
			// saved under an older config, including vanilla's existing cap.
			boolean configuredHealth = attribute.getAttribute() == SharedMonsterAttributes.MAX_HEALTH
					&& base >= 1D && base <= Short.MAX_VALUE;
			if((attribute.getAttribute().clampValue(base) != base
					&& !configuredHealth && !base.equals(defaultAttributeBases.get(name))) || !seen.add(name)) {
				retainInvalid("Attribute",record); continue;
			}
			boolean changed = !Arrays.asList("Name","Base","Modifiers").containsAll(record.keySet());
			NBTTagCompound accepted = new NBTTagCompound();
			accepted.setString("Name",name); accepted.setDouble("Base",base);
			INBTBase rawModifiers = record.getTag("Modifiers");
			if(rawModifiers != null) {
				NBTTagList modifiers = new NBTTagList();
				if(!isInventoryList(rawModifiers)) changed = true;
				else {
					Set<UUID> ids = new HashSet<>();
					NBTTagList entries = (NBTTagList)rawModifiers;
					for(int m = 0; m < entries.size(); m++) {
						NBTTagCompound entry = entries.getCompound(m);
						Long operation = integral(entry.getTag("Operation"));
						Long most = integral(entry.getTag("UUIDMost")), least = integral(entry.getTag("UUIDLeast"));
						Double amount = finiteNumber(entry.getTag("Amount"));
						if(!entry.contains("Name",8) || entry.getString("Name").isEmpty() || operation == null
								|| operation < 0 || operation > 2 || most == null || least == null || amount == null) {
							changed = true; continue;
						}
						UUID id = new UUID(most,least);
						if(ids.contains(id)) { changed = true; continue; }
						NBTTagCompound modifier = new NBTTagCompound();
						modifier.setString("Name",entry.getString("Name")); modifier.setDouble("Amount",amount);
						modifier.setInt("Operation",operation.intValue()); modifier.setUniqueId("UUID",id);
						NBTTagList candidate = modifiers.copy(); candidate.add(modifier);
						if(!finiteAttributeValue(base,candidate)) { changed = true; continue; }
						ids.add(id); modifiers.add(modifier);
						if(!Arrays.asList("Name","Amount","Operation","UUIDMost","UUIDLeast").containsAll(entry.keySet())) changed = true;
					}
				}
				accepted.setTag("Modifiers",modifiers);
			}
			if(changed) retainInvalid("Attribute",record);
			clean.add(accepted);
		}
		return clean;
	}

	private static boolean finiteAttributeValue(double base, NBTTagList modifiers) {
		double value = base;
		for(int i = 0; i < modifiers.size(); i++) {
			NBTTagCompound m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 0) value += m.getDouble("Amount");
		}
		if(!Double.isFinite(value)) return false;
		double additive = value;
		for(int i = 0; i < modifiers.size(); i++) {
			NBTTagCompound m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 1) value += additive * m.getDouble("Amount");
		}
		if(!Double.isFinite(value)) return false;
		for(int i = 0; i < modifiers.size(); i++) {
			NBTTagCompound m = modifiers.getCompound(i);
			if(m.getInt("Operation") == 2) value *= 1D + m.getDouble("Amount");
		}
		return Double.isFinite(value);
	}

	private long readTimer(NBTTagCompound root, String key, long maximum, long fallback) {
		INBTBase raw = root.getTag(key);
		if(raw == null) return fallback;
		Long value = integral(raw);
		if(value != null && value >= 0 && value <= maximum) return value;
		retainInvalid(key,raw);
		return fallback;
	}

	private long checkedTimestamp(String key, long timestamp, long now) {
		if(timestamp >= 0 && timestamp <= now) return timestamp;
		retainInvalid(key,new net.minecraft.nbt.NBTTagLong(timestamp));
		markDirty();
		return now;
	}

    private String readName(NBTTagCompound root,String key) {
        INBTBase raw=root.getTag(key);if(raw==null)return null;
        if(raw.getId()==8) {
            String name=root.getString(key);
            if(key.equals("CustomName") && root.getInt(UpgradePreservation.VERSION)>=3) {
                try {
                    net.minecraft.util.text.ITextComponent text=net.minecraft.util.text.ITextComponent.Serializer.fromJson(name);
                    if(text==null){retainInvalid(key,raw);return null;}name=text.getString();
                }catch(RuntimeException malformed){retainInvalid(key,raw);return null;}
            }
            String packet=net.minecraft.util.text.ITextComponent.Serializer.toJson(new net.minecraft.util.text.TextComponentString(name));
            if(name.length()<=32767 && name.getBytes(StandardCharsets.UTF_8).length<=32767
                    && packet.getBytes(StandardCharsets.UTF_8).length<=32767)return name.trim().isEmpty()?"":name;
        }
        retainInvalid(key,raw);return null;
    }



	private static boolean isInventoryList(INBTBase data) {
		return data instanceof NBTTagList && (((NBTTagList)data).size() == 0 || ((NBTTagList)data).getTagType() == 10);
	}

	private void retainRaw(String source, INBTBase data) {
		NBTTagCompound record = new NBTTagCompound();
		record.setString("Source",source);
		record.setTag("Data",data.copy());
		if(!inventoryVersions.isEmpty()) record.setTag("SourceVersions",inventoryVersions.copy());
		unresolvedInventory.add(record);
	}

	private void readInventoryTag(String source, INBTBase data) {
		if(data == null) return;
		if(!isInventoryList(data)) { retainRaw(source + "List",data); return; }
		NBTTagList list = (NBTTagList)data;
		for(int i = 0; i < list.size(); i++) readInventoryEntry(source,list.getCompound(i),null);
	}

	private static Long integral(INBTBase data) {
		return data instanceof NBTPrimitive && data.getId() >= 1 && data.getId() <= 4 ? ((NBTPrimitive)data).getLong() : null;
	}

	private ItemStack decodeInventoryItem(NBTTagCompound raw) {
		// Ambiguous fields, unavailable IDs and unhandled capability data stay
		// verbatim. Never guess an item, truncate a count or erase custom data.
		for(String key : raw.keySet()) if(!Arrays.asList("id","Count","Damage","tag","ForgeCaps","Slot").contains(key)) return null;
		if(!raw.contains("id",8)) return null;
		Long count = integral(raw.getTag("Count"));
		Long legacyDamage = raw.hasKey("Damage") ? integral(raw.getTag("Damage")) : Long.valueOf(0L);
        Long damage = raw.getCompound("tag").hasKey("Damage") ? integral(raw.getCompound("tag").getTag("Damage")) : legacyDamage;
        if(legacyDamage == null || legacyDamage < 0 || legacyDamage > 32767
                || (raw.hasKey("Damage") && (damage==null || legacyDamage.longValue()!=damage.longValue())))return null;
		if(count == null || count <= 0 || count > 127 || damage == null || damage < 0 || damage > 32767) return null;
		if((raw.hasKey("tag") && !raw.contains("tag",10)) || (raw.hasKey("ForgeCaps") && !raw.contains("ForgeCaps",10))) return null;
		try {
			ResourceLocation id = new ResourceLocation(raw.getString("id"));
			if(!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id)) return null;
			Item registered = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
			if(registered == null || Item.getIdFromItem(registered) == 0) return null;
			NBTTagCompound input = raw.copy();
			input.setString("id",id.toString());
			input.setByte("Count",count.byteValue());
			if(raw.hasKey("Damage"))input.setShort("Damage",legacyDamage.shortValue());
			ItemStack stack = ItemStack.read(input);
			if(emptyStack(stack) || stack.getItem() != registered || stack.getCount() != count.intValue()
					|| stack.getDamage() != damage.intValue() || stack.getMaxStackSize() <= 0) return null;
			NBTTagCompound encoded = stack.write(new NBTTagCompound());
            if(!Objects.equals(raw.getTag("tag"),encoded.getTag("tag"))
                    || !Objects.equals(raw.getTag("ForgeCaps"),encoded.getTag("ForgeCaps"))) return null;
			return stack;
		} catch(RuntimeException unreadable) { return null; }
	}

	private void readInventoryEntry(String source, NBTTagCompound raw, NBTTagCompound retained) {
		NBTTagCompound context=retained!=null && retained.contains("SourceVersions",10) ? retained.getCompound("SourceVersions") : inventoryVersions;
		NBTTagCompound converted=ItemMigration.convert(raw,context);
		ItemStack stack = converted==null ? null : decodeInventoryItem(converted);
		if(emptyStack(stack)) {
			if(retained == null) retainRaw(source,raw);
			else {
				NBTTagCompound copy=retained.copy();
				if(!copy.hasKey("SourceVersions") && !context.isEmpty()) copy.setTag("SourceVersions",context.copy());
				unresolvedInventory.add(copy);
			}
			return;
		}
		int limit = Math.min(stack.getMaxStackSize(),getInventoryStackLimit());
		if(source.equals("Equipment")) {
			Long number = integral(raw.getTag("Slot"));
			if(number != null && number >= 0 && number < getSizeInventory()) {
				int slot = number.intValue();
				if(emptyStack(getStackInSlot(slot)) && isItemValidForSlot(slot,stack)) {
					setInventorySlotContents(slot,stack.split(Math.min(stack.getCount(),slot < 4 ? 1 : limit)));
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
