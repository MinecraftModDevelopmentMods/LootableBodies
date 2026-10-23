package cyano.lootable;

import java.io.File;
import cyano.lootable.config.LegacyConfiguration;
import cyano.lootable.entities.EntityLootableBody;
import cyano.lootable.events.PlayerDeathEventHandler;
import cyano.lootable.upgrade.UpgradePreservation;
import cyano.lootable.upgrade.ChunkVersionContext;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemSpawnEgg;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(LootableBodies.MODID)
public final class LootableBodies {
    public static final String MODID = "lootablebodies";
    public static final String NAME = "DrCyano's Lootable Bodies";
    public static final String VERSION = "2.4.4.113021";
    public static final ResourceLocation CORPSE_ID = new ResourceLocation("lootablebodies:corpse");
    public static EntityType<EntityLootableBody> CORPSE;
    public static Item CORPSE_EGG;
    private static LootableBodies INSTANCE;
    private static LegacyConfiguration configuration;

    public static boolean displayNameTag = true;
    public static boolean addBonesToCorpse = true;
    public static boolean useLocalSkin = false;
    public static long ticksPerItemDecay = 10 * 60 * 20;
    public static boolean hurtByEnvironment = false;
    public static boolean hurtByAttacks = false;
    public static boolean hurtByMisc = false;
    public static boolean completelyInvulnerable = false;
    public static float corpseHP = 20;
    public static boolean allowCorpseDecay = true;
    public static boolean decayOnlyWhenEmpty = true;
    public static long corpseDecayTime = 3600*20;
    public static boolean corpseBuoyancy = false;
    public static boolean voidPlatform = false;
    public static boolean dimensionSpecificPlatform = true;

    public LootableBodies() {
        INSTANCE=this;
        loadConfiguration(FMLPaths.CONFIGDIR.get().resolve("lootablebodies.cfg").toFile());
        net.minecraftforge.eventbus.api.IEventBus bus=FMLJavaModLoadingContext.get().getModEventBus();
        bus.addGenericListener(EntityType.class,this::registerEntities);
        bus.addGenericListener(Item.class,this::registerItems);
        MinecraftForge.EVENT_BUS.register(new PlayerDeathEventHandler());
        MinecraftForge.EVENT_BUS.register(this);
        DistExecutor.runWhenOn(Dist.CLIENT,()->ClientProxy::register);
    }
    private static synchronized EntityType<EntityLootableBody> corpseType() {
        if(CORPSE==null)CORPSE=new EntityType<>(EntityLootableBody.class,EntityLootableBody::new,
                true,true,null,false,null,true,32,1,true);
        return CORPSE;
    }
    private void registerEntities(RegistryEvent.Register<EntityType<?>> event) {
        event.getRegistry().register(corpseType().setRegistryName(CORPSE_ID));
    }
    private void registerItems(RegistryEvent.Register<Item> event) {
        CORPSE_EGG=new ItemSpawnEgg(corpseType(),1,1,new Item.Properties().group(ItemGroup.MISC))
                .setRegistryName(MODID,"corpse_spawn_egg");
        event.getRegistry().register(CORPSE_EGG);
    }
    public static LootableBodies getInstance(){return INSTANCE;}
    @SubscribeEvent public void beforeWorldLoading(FMLServerAboutToStartEvent event) {
        File world=event.getServer().getActiveAnvilConverter().getFile(event.getServer().getFolderName(),"");
        try {
            UpgradePreservation.register(event.getServer().getDataFixer());
            int count=UpgradePreservation.prepare(world.toPath()).size();
            ChunkVersionContext.installServer(event.getServer());
            ModLog.info("Lootable Bodies preserved %s existing corpses before world loading",count);
        } catch(java.io.IOException unsafe) {
            throw new IllegalStateException("Lootable Bodies could not preserve this world's corpses. World loading has been stopped; keep the original world and recovery journal.",unsafe);
        }
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public void worldLoaded(net.minecraftforge.event.world.WorldEvent.Load event) {
        if(event.getWorld() instanceof net.minecraft.world.WorldServer)ChunkVersionContext.installWorld((net.minecraft.world.WorldServer)event.getWorld());
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public void beforeSpawn(net.minecraftforge.event.world.WorldEvent.CreateSpawnPosition event) {
        if(event.getWorld() instanceof net.minecraft.world.WorldServer)ChunkVersionContext.installWorld((net.minecraft.world.WorldServer)event.getWorld());
    }
    @SubscribeEvent public void worldStopped(FMLServerStoppedEvent event){UpgradePreservation.close();}
    private static boolean or(boolean... values){for(boolean value:values)if(value)return true;return false;}
    /** Reads startup rules. GUI saves deliberately do not call this method. */
    public static void loadConfiguration(File file) {
        // Do stuff in pre-init phase (read config, create blocks and items, register them)
    	// load config
        LegacyConfiguration config = new LegacyConfiguration(file);
        configuration = config;
    	config.load();

    	corpseHP = config.getFloat("corpse_HP", "options", corpseHP, 1,Short.MAX_VALUE,
				"The amount of damage a corpse can suffer before being \n"
				+ "destroyed and releasing its items. \n"
				+ "Note that 10 hearts = 20 HP.");
		useLocalSkin = config.getBoolean("local_texture", "options", useLocalSkin,
				"If false, corpses will have the skins of the player who \n"
				+ "died. If true, then corpses will use the following texture \n."
				+ "location: assets/"+MODID+"/textures/entity/corpse/corpse.png");
    	displayNameTag = config.getBoolean("display_nametag", "options", displayNameTag,
				"If true, corpses will show their owner's name");

		addBonesToCorpse = config.getBoolean("add_bones_to_corpse", "options", addBonesToCorpse,
			"If true, corpses will have bones and rotten flesh added to them.");


    	hurtByEnvironment = config.getBoolean("hurt_by_environment", "corpse damage", hurtByEnvironment,
				"If true, corpses will be damaged by fire, lava, falling, and other such hazards.");
		hurtByAttacks = config.getBoolean("hurt_by_weapons", "corpse damage", hurtByAttacks,
				"If true, corpses can be damaged by attacking them.");
		hurtByMisc = config.getBoolean("hurt_by_misc", "corpse damage", hurtByMisc,
				"If true, corpses will be damaged by damage sources not covered by the other options in this section.");


		completelyInvulnerable = !or(hurtByEnvironment,hurtByAttacks,hurtByMisc);

		ticksPerItemDecay = (int)(60 * 20 * config.getFloat("item_decay_rate", "options", 5F, -1F, 1e9F,
				"All damageable items on the corpse will suffer 1 durability damage \n"
				+ "for every X number of minutes (default is 5 minutes) that they are. \n"
				+ "Items damaged in this way will never be completely destroyed. \n"
				+ "Set to 0 (or negative) to disable."));

    	allowCorpseDecay = config.getBoolean("enable_corpse_decay", "corpse decay", allowCorpseDecay,
				"If true, corpses will self-destruct after a period of time.");
    	decayOnlyWhenEmpty = config.getBoolean("empty_only_decay", "corpse decay", decayOnlyWhenEmpty,
				"If true and enable_corpse_decay is also true, corpses will \n"
				+ "self-destruct after being empty for a period of time (will \n"
				+ "not decay if there are any items on the corpse). If using this \n"
				+ "option, you will probably want to also disable the \n"
				+ "add_bones_to_corpse option.");
    	String decayTime = config.getString("corpse_decay_time", "corpse decay", "1:00:00",
				"Time after death before a corpse will self-destruct (if the \n"
				+ "enable_corpse_decay option is set to true). \n"
				+ "The format is hours:minutes:seconds or just hours:minutes");
    	corpseDecayTime = Math.max(parseTimeInSeconds(decayTime),2)*20; // 2 second minimum

		corpseBuoyancy = config.getBoolean("enable_corpse_buoyancy","corpse recovery", corpseBuoyancy,
				"If true, corpses will float in fluids.");
		voidPlatform = config.getBoolean("prevent_void_loss", "corpse recovery", voidPlatform,
				"If true, corpses that would fall into the void instead create platform to lay on");
		dimensionSpecificPlatform= config.getBoolean("dim_specific_platform", "corpse recovery", dimensionSpecificPlatform,
				"If true, corpses generate dimension specific platforms, otherwise they only make cobblestone platforms");

        for (String category : new String[] {"options", "corpse damage", "corpse decay", "corpse recovery"}) {
            for (LegacyConfiguration.Property property : config.getCategory(category).values()) {
                String key = property.getName();
                // Unknown pack options are retained without exposing them in our GUI.
                if (!isKnownOption(key)) continue;
                property.setLanguageKey("lootablebodies.config." + key);
                property.setRequiresMcRestart(!key.equals("local_texture") && !key.equals("display_nametag"));
            }
        }
		config.save();
    }

    private static boolean isKnownOption(String key) {
        return java.util.Arrays.asList("corpse_HP", "local_texture", "display_nametag", "add_bones_to_corpse",
                "hurt_by_environment", "hurt_by_weapons", "hurt_by_misc", "item_decay_rate",
                "enable_corpse_decay", "empty_only_decay", "corpse_decay_time",
                "enable_corpse_buoyancy", "prevent_void_loss", "dim_specific_platform").contains(key);
    }

    public static LegacyConfiguration getConfiguration() {
        return configuration;
    }

    /** Persists pending rules, applying only this client's appearance preferences. */
    public static void saveClientConfiguration() {
        if (configuration == null) return;
        useLocalSkin = configuration.getCategory("options").get("local_texture").getBoolean();
        displayNameTag = configuration.getCategory("options").get("display_nametag").getBoolean();
        configuration.save();
    }
	private static int parseTimeInSeconds(String time) {
		String[] component = time.split(":");
		int hr = 0,min=0,sec=0;
		if(component.length > 0)hr = Integer.parseInt(component[0].trim());
		if(component.length > 1)min = Integer.parseInt(component[1].trim());
		if(component.length > 2)sec = Integer.parseInt(component[2].trim());
		return 3600 * hr + 60 * min + sec;
	}


}
