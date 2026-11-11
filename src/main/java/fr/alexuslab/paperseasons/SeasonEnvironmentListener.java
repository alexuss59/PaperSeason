package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SeasonalEvent;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Snowable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Listener O(1) v1.1 pour :
 * - Le Calendrier Agricole Biologique par espèce (EnumMap<Season, EnumMap<Material, Double>>)
 * - Les Serres en verre paresseuses en hiver (Tag.IMPERMEABLE / GLASS_MATERIALS)
 * - La Faune saisonnière (jumeaux au printemps, échec au froid en hiver)
 * - La Pêche saisonnière (FishHook wait time)
 * - La Synergie thermique des repas chauds et boissons fraîches / BreweryX
 */
public final class SeasonEnvironmentListener implements Listener {

    private static final EnumSet<Material> GLASS_MATERIALS = EnumSet.noneOf(Material.class);
    private static final EnumSet<Material> HOT_STEW_MATERIALS = EnumSet.of(
            Material.MUSHROOM_STEW,
            Material.RABBIT_STEW,
            Material.SUSPICIOUS_STEW,
            Material.BEETROOT_SOUP
    );
    private static final EnumSet<Material> REFRESHING_CONSUME_MATERIALS = EnumSet.of(
            Material.POTION,
            Material.MILK_BUCKET,
            Material.MELON_SLICE,
            Material.SWEET_BERRIES,
            Material.GLOW_BERRIES
    );
    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    static {
        for (Material mat : Material.values()) {
            if (mat.name().contains("GLASS")) {
                GLASS_MATERIALS.add(mat);
            }
        }
    }

    private final PaperSeasonsPlugin plugin;
    private final SeasonConfigCache configCache;
    private final SeasonManager seasonManager;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private BukkitTask winterStormTask;
    private int surfaceTickCounter = 0;

    public SeasonEnvironmentListener(
            PaperSeasonsPlugin plugin,
            SeasonConfigCache configCache,
            SeasonManager seasonManager
    ) {
        this.plugin = plugin;
        this.configCache = configCache;
        this.seasonManager = seasonManager;
    }

    /**
     * Démarre la tâche légère O(1) de surface (toutes les 20 ticks = 1 seconde) :
     * - En Hiver (toutes les 40 ticks) : accumulation contrôlée de neige et gel de l'eau.
     * - Au Printemps / Été / Canicule / Fin d'Hiver ensoleillée (toutes les 20 ticks) : fonte active O(1).
     */
    public void startWinterStormTask() {
        stopWinterStormTask();
        this.surfaceTickCounter = 0;
        this.winterStormTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::tickSurfaceClimateO1, 20L, 20L);
    }

    public void stopWinterStormTask() {
        if (this.winterStormTask != null) {
            this.winterStormTask.cancel();
            this.winterStormTask = null;
        }
    }

    private void tickSurfaceClimateO1() {
        this.surfaceTickCounter++;
        Season currentSeason = this.seasonManager.getCurrentSeason();
        if (currentSeason == Season.WINTER && (this.surfaceTickCounter & 1) == 0) {
            tickWinterStormO1();
        }
        tickActiveThawO1();
    }

    /**
     * Moteur de fonte active O(1) (exécuté toutes les 20 ticks = 1s) pour compenser l'absence
     * de fonte au soleil dans Minecraft Vanilla (SnowLayerBlock & IceBlock ne testent que BlockLight > 11).
     */
    private void tickActiveThawO1() {
        Season currentSeason = this.seasonManager.getCurrentSeason();
        SubSeason currentSubSeason = this.seasonManager.getCurrentSubSeason();
        SeasonalEvent currentEvent = this.seasonManager.getCurrentEvent();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        boolean meltEnabled = settings.iceSnowMeltEnabled();
        boolean lateWinterThaw = (currentSubSeason == SubSeason.WINTER_LATE && currentEvent != SeasonalEvent.BLIZZARD);
        if (!meltEnabled && !lateWinterThaw && currentEvent != SeasonalEvent.HEATWAVE) {
            return;
        }

        int samplesPerSecond;
        if (currentEvent == SeasonalEvent.HEATWAVE) {
            samplesPerSecond = 25;
        } else if (currentSeason == Season.SUMMER) {
            samplesPerSecond = 12;
        } else if (currentSeason == Season.SPRING) {
            samplesPerSecond = 6;
        } else {
            samplesPerSecond = 4; // Fin d'hiver (WINTER_LATE) en journée ensoleillée
        }

        boolean instantFullMelt = (currentEvent == SeasonalEvent.HEATWAVE);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            World world = player.getWorld();
            if (!isEligibleOverworld(world)) {
                continue;
            }
            if (!meltEnabled && lateWinterThaw && (world.hasStorm() || world.getTime() >= 12000L)) {
                continue;
            }

            Location loc = player.getLocation();
            int baseX = loc.getBlockX();
            int baseZ = loc.getBlockZ();

            for (int i = 0; i < samplesPerSecond; i++) {
                int x = baseX + rng.nextInt(-32, 33);
                int z = baseZ + rng.nextInt(-32, 33);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                meltSurfaceColumnO1(world, x, z, instantFullMelt);
            }
        }
    }

    /**
     * Nettoie en O(1) la neige (Material.SNOW) et la glace (Material.ICE) au sommet d'une colonne (X, Z),
     * tout en protégeant les biomes glacés natifs (getTemperature() <= 0.15) et les blocs de construction
     * (PACKED_ICE, BLUE_ICE, SNOW_BLOCK, POWDER_SNOW).
     *
     * @return Nombre de blocs fondus dans cette colonne (0, 1 ou 2)
     */
    public int meltSurfaceColumnO1(World world, int x, int z, boolean instantFullMelt) {
        Block highest = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
        // Sécurité Biomes Glacés Natifs (Protection Terralith : Frozen Ocean, Ice Spikes, Jagged Peaks, haute montagne)
        if (highest.getTemperature() <= 0.15D) {
            return 0;
        }

        int meltedCount = 0;
        Block above = highest.getRelative(BlockFace.UP);

        // 1. Fonte de la neige (Material.SNOW uniquement, jamais SNOW_BLOCK ni POWDER_SNOW)
        if (above.getType() == Material.SNOW) {
            if (!instantFullMelt && above.getBlockData() instanceof Snow snowData && snowData.getLayers() > 1) {
                snowData.setLayers(snowData.getLayers() - 1);
                above.setBlockData(snowData, false);
            } else {
                above.setType(Material.AIR, false);
                clearSnowyState(highest);
            }
            meltedCount++;
        } else if (highest.getType() == Material.SNOW) {
            if (!instantFullMelt && highest.getBlockData() instanceof Snow snowData && snowData.getLayers() > 1) {
                snowData.setLayers(snowData.getLayers() - 1);
                highest.setBlockData(snowData, false);
            } else {
                Block below = highest.getRelative(BlockFace.DOWN);
                highest.setType(Material.AIR, false);
                clearSnowyState(below);
                highest = below;
            }
            meltedCount++;
        }

        // 2. Fonte de la glace de surface (Material.ICE uniquement, jamais PACKED_ICE ni BLUE_ICE)
        if (highest.getType() == Material.ICE) {
            highest.setType(Material.WATER, true);
            meltedCount++;
        }

        return meltedCount;
    }

    private void clearSnowyState(Block blockBelow) {
        BlockData data = blockBelow.getBlockData();
        if (data instanceof Snowable snowable && snowable.isSnowy()) {
            snowable.setSnowy(false);
            blockBelow.setBlockData(data, false);
        }
    }

    /**
     * Nettoyage accéléré au chargement d'un chunk par le joueur au Printemps ou en Été.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        World world = event.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null || !settings.iceSnowMeltEnabled()) {
            return;
        }

        Chunk chunk = event.getChunk();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Échantillonnage O(1) de 16 colonnes aléatoires à l'envoi du chunk
        for (int i = 0; i < 16; i++) {
            int x = baseX + rng.nextInt(16);
            int z = baseZ + rng.nextInt(16);
            meltSurfaceColumnO1(world, x, z, true);
        }
    }

    /**
     * Exécute la commande d'administration /season thaw [rayon] en étalant le balayage sur plusieurs ticks
     * (max 2048 colonnes par tick, < 0.4 ms/tick sur Raspberry Pi 5).
     */
    public void runEmergencyThaw(CommandSender sender, World world, int centerX, int centerZ, int radius) {
        final int minX = centerX - radius;
        final int maxX = centerX + radius;
        final int minZ = centerZ - radius;
        final int maxZ = centerZ + radius;
        final int columnsPerTick = 2048;

        new BukkitRunnable() {
            private int curX = minX;
            private int curZ = minZ;
            private int totalMelted = 0;

            @Override
            public void run() {
                int processed = 0;
                while (curX <= maxX && processed < columnsPerTick) {
                    if (world.isChunkLoaded(curX >> 4, curZ >> 4)) {
                        totalMelted += meltSurfaceColumnO1(world, curX, curZ, true);
                    }
                    processed++;
                    curZ++;
                    if (curZ > maxZ) {
                        curZ = minZ;
                        curX++;
                    }
                }

                if (curX > maxX) {
                    cancel();
                    ((Audience) sender).sendMessage(mm.deserialize(
                            "<green>✔ Dégel d'urgence terminé dans un rayon de <bold>" + radius + " blocs</bold> : "
                                    + "<aqua><bold>" + totalMelted + "</bold></aqua> blocs de neige/glace fondus "
                                    + "<dark_gray>(biomes glacés natifs préservés)</dark_gray>.</green>"
                    ));
                }
            }
        }.runTaskTimer(this.plugin, 1L, 1L);
    }

    private void tickWinterStormO1() {
        if (this.seasonManager.getCurrentSeason() != Season.WINTER) {
            return;
        }
        SeasonSettings winterSettings = this.configCache.getSeasonSettings(Season.WINTER);
        if (winterSettings == null) {
            return;
        }

        int columnsPerPlayer = this.configCache.getWinterSnowColumnsPerCheck();
        if (this.seasonManager.getCurrentEvent() == SeasonalEvent.BLIZZARD) {
            columnsPerPlayer *= 2;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            World world = player.getWorld();
            if (!isEligibleOverworld(world) || !world.hasStorm()) {
                continue;
            }

            Location loc = player.getLocation();
            Block playerBlock = loc.getBlock();

            // 1. Particules de flocons groupées (1 seul paquet réseau par joueur exposé au ciel)
            if (playerBlock.getLightFromSky() >= 10) {
                player.spawnParticle(
                        Particle.SNOWFLAKE,
                        loc.getX(), loc.getY() + 3.5D, loc.getZ(),
                        24, 8.0D, 3.5D, 8.0D, 0.02D
                );
            }

            // 2. Dépôt contrôlé O(1) de neige (Material.SNOW) et gel de l'eau (Material.ICE) via HeightMap
            int baseX = loc.getBlockX();
            int baseZ = loc.getBlockZ();
            for (int i = 0; i < columnsPerPlayer; i++) {
                int x = baseX + rng.nextInt(-22, 23);
                int z = baseZ + rng.nextInt(-22, 23);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                Block surface = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
                Material surfaceType = surface.getType();

                if (surfaceType == Material.WATER) {
                    if (surface.getBlockData() instanceof Levelled levelled
                            && levelled.getLevel() == 0
                            && surface.getLightFromBlocks() < winterSettings.minBlockLightToGrow()
                            && !isUnderGreenhouseGlass(surface, winterSettings.greenhouseMaxGlassHeight())) {
                        surface.setType(Material.ICE, true);
                    }
                } else if (canSupportSnowLayer(surface, surfaceType)) {
                    Block above = surface.getRelative(BlockFace.UP);
                    if (above.getType().isAir()
                            && above.getLightFromBlocks() < winterSettings.minBlockLightToGrow()
                            && !isUnderGreenhouseGlass(above, winterSettings.greenhouseMaxGlassHeight())) {
                        above.setType(Material.SNOW, false);
                        BlockData belowData = surface.getBlockData();
                        if (belowData instanceof Snowable snowable && !snowable.isSnowy()) {
                            snowable.setSnowy(true);
                            surface.setBlockData(belowData, false);
                        }
                    }
                }
            }
        }
    }

    private boolean canSupportSnowLayer(Block block, Material type) {
        if (!type.isSolid() || !type.isOccluding() && !Tag.LEAVES.isTagged(type)) {
            return false;
        }
        if (type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE
                || type == Material.MAGMA_BLOCK || GLASS_MATERIALS.contains(type)) {
            return false;
        }
        String name = type.name();
        return !name.endsWith("_STAIRS") && !name.endsWith("_SLAB") && !name.endsWith("_WALL") && !name.endsWith("_FENCE");
    }

    // =========================================================================
    // 1. CALENDRIER AGRICOLE BIOLOGIQUE PAR ESPÈCE (BlockGrowEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        if (currentSeason == Season.WINTER) {
            if (!isHeatedOrUnderGreenhouse(block, settings)) {
                event.setCancelled(true);
            }
            return;
        }

        // Lookup O(1) dans l'EnumMap du calendrier biologique de l'espèce
        Material cropType = block.getType();
        if (cropType.isAir()) {
            cropType = event.getNewState().getType();
        }
        Double speciesMult = this.configCache.getCropMultiplier(currentSeason, cropType);

        if (speciesMult != null && speciesMult < 0.0D) {
            // Culture hors-saison (ex: céréales d'été en automne -50%, ou culture fraîche en plein été -35%)
            double cancelChance = Math.abs(speciesMult);
            if (ThreadLocalRandom.current().nextDouble() < cancelChance
                    && !isHeatedOrUnderGreenhouse(block, settings)) {
                event.setCancelled(true);
            }
            return;
        }

        double boostChance = (speciesMult != null) ? speciesMult : settings.cropGrowthBoostChance();
        SeasonalEvent activeEvent = this.seasonManager.getCurrentEvent();
        if (activeEvent == SeasonalEvent.SPRING_BLOOM) {
            boostChance = Math.min(1.0D, Math.max(0.25D, boostChance) * this.configCache.getSpringBloomMultiplier());
        } else if (activeEvent == SeasonalEvent.AUTUMN_MIST
                && (cropType == Material.SWEET_BERRY_BUSH || cropType == Material.BROWN_MUSHROOM
                || cropType == Material.RED_MUSHROOM || cropType == Material.CAVE_VINES)) {
            boostChance = Math.min(1.0D, boostChance + 0.50D);
        }
        if (boostChance > 0.0D && ThreadLocalRandom.current().nextDouble() < boostChance) {
            if (event.getNewState().getBlockData() instanceof Ageable ageable) {
                int currentAge = ageable.getAge();
                int maxAge = ageable.getMaximumAge();
                if (currentAge < maxAge) {
                    ageable.setAge(currentAge + 1);
                    event.getNewState().setBlockData(ageable);
                }
            }
        }
    }

    // =========================================================================
    // 2. CROISSANCE DES ARBRES (StructureGrowEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        World world = event.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        Block saplingBlock = event.getLocation().getBlock();
        if (currentSeason == Season.WINTER) {
            if (!isHeatedOrUnderGreenhouse(saplingBlock, settings)) {
                event.setCancelled(true);
            }
        } else if (currentSeason == Season.AUTUMN && !event.isFromBonemeal()) {
            // En automne, la pousse naturelle des arbres ralentit de 35% hors serre
            if (ThreadLocalRandom.current().nextDouble() < 0.35D
                    && !isHeatedOrUnderGreenhouse(saplingBlock, settings)) {
                event.setCancelled(true);
            }
        }
    }

    // =========================================================================
    // 3. PROPAGATION VÉGÉTALE : BAMBOU, VIGNES, CHAMPIGNONS (BlockSpreadEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Material sourceType = event.getSource().getType();
        if (sourceType == Material.FIRE || sourceType == Material.LAVA) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        if (currentSeason == Season.WINTER) {
            if (!isHeatedOrUnderGreenhouse(block, settings)) {
                event.setCancelled(true);
            }
            return;
        }

        Double speciesMult = this.configCache.getCropMultiplier(currentSeason, sourceType);
        if (speciesMult != null && speciesMult < 0.0D) {
            if (ThreadLocalRandom.current().nextDouble() < Math.abs(speciesMult)
                    && !isHeatedOrUnderGreenhouse(block, settings)) {
                event.setCancelled(true);
            }
        }
    }

    // =========================================================================
    // 4. FORMATION DE NEIGE ET GLACE EN HIVER (BlockFormEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Material newType = event.getNewState().getType();
        if (newType != Material.SNOW && newType != Material.ICE) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        if (currentSeason == Season.WINTER) {
            if (isHeatedOrUnderGreenhouse(block, settings)) {
                event.setCancelled(true);
            }
        } else {
            if (block.getTemperature() > 0.15D) {
                event.setCancelled(true);
            }
        }
    }

    // =========================================================================
    // 5. FONTE PROGRESSIVE AU PRINTEMPS / ÉTÉ (BlockFadeEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Material type = block.getType();
        if (type != Material.SNOW && type != Material.ICE) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        if (!settings.iceSnowMeltEnabled()) {
            if (block.getLightFromBlocks() < settings.minBlockLightToGrow()) {
                event.setCancelled(true);
            }
        } else if (type == Material.SNOW) {
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getBlockData() instanceof Snowable snowable && snowable.isSnowy()) {
                snowable.setSnowy(false);
                below.setBlockData(snowable, false);
            }
            BlockFace randomFace = HORIZONTAL_FACES[ThreadLocalRandom.current().nextInt(HORIZONTAL_FACES.length)];
            Block neighbor = block.getRelative(randomFace);
            if (neighbor.getType() == Material.SNOW && neighbor.getTemperature() > 0.15D) {
                neighbor.setType(Material.AIR, false);
                Block neighborBelow = neighbor.getRelative(BlockFace.DOWN);
                if (neighborBelow.getBlockData() instanceof Snowable nSnowable && nSnowable.isSnowy()) {
                    nSnowable.setSnowy(false);
                    neighborBelow.setBlockData(nSnowable, false);
                }
            }
        }
    }

    // =========================================================================
    // 6. MÉTÉO SAISONNIÈRE (WeatherChangeEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        World world = event.getWorld();
        if (!isEligibleOverworld(world) || !event.toWeatherState()) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        double rainMultiplier = settings.rainChanceMultiplier();

        if (rainMultiplier < 1.0D && ThreadLocalRandom.current().nextDouble() > rainMultiplier) {
            event.setCancelled(true);
            return;
        }

        if (Math.abs(rainMultiplier - 1.0D) > 0.05D || currentSeason == Season.WINTER) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (world.hasStorm()) {
                    int baseDuration = world.getWeatherDuration();
                    if (baseDuration > 0 && Math.abs(rainMultiplier - 1.0D) > 0.05D) {
                        int adjustedDuration = Math.clamp((int) (baseDuration * rainMultiplier), 1200, 36000);
                        world.setWeatherDuration(adjustedDuration);
                    }
                    if (this.seasonManager.getCurrentSeason() == Season.WINTER
                            && this.plugin.getBiomePacketSpoofer() != null) {
                        this.plugin.getBiomePacketSpoofer().refreshChunksForAllPlayers();
                    }
                }
            });
        }
    }

    // =========================================================================
    // 7. FAUNE SAISONNIÈRE : REPRODUCTION ANIMALE (EntityBreedEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        LivingEntity child = event.getEntity();
        World world = child.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        Block block = child.getLocation().getBlock();

        // En Hiver : l'élevage en plein air non chauffé (SkyLight >= 12 et BlockLight < 10) a 50% de chance d'échouer
        if (currentSeason == Season.WINTER) {
            double failChance = settings.breedingOutdoorFailChance();
            if (failChance > 0.0D
                    && block.getLightFromSky() >= 12
                    && block.getLightFromBlocks() < 10
                    && ThreadLocalRandom.current().nextDouble() < failChance) {
                event.setCancelled(true);
            }
            return;
        }

        // Au Printemps (ou Été léger) : chance de donner naissance à un jumeau + double XP
        double twinChance = settings.breedingTwinChance();
        if (this.seasonManager.getCurrentEvent() == SeasonalEvent.SPRING_BLOOM) {
            twinChance = Math.min(1.0D, twinChance * this.configCache.getSpringBloomMultiplier());
        }
        if (twinChance > 0.0D && ThreadLocalRandom.current().nextDouble() < twinChance) {
            event.setExperience(Math.max(1, event.getExperience() * 2));
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (child.isValid() && !child.isDead()) {
                    Entity twin = world.spawnEntity(child.getLocation(), child.getType());
                    if (twin instanceof org.bukkit.entity.Ageable ageableTwin) {
                        ageableTwin.setBaby();
                    }
                }
            });
        }
    }

    // =========================================================================
    // 8. PÊCHE SAISONNIÈRE (PlayerFishEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING) {
            return;
        }
        World world = event.getPlayer().getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        Season currentSeason = this.seasonManager.getCurrentSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        if (settings == null) {
            return;
        }

        double waitMult = settings.fishingWaitMultiplier();
        if (this.seasonManager.getCurrentEvent() == SeasonalEvent.AUTUMN_MIST) {
            waitMult *= this.configCache.getAutumnMistFishingMultiplier();
        }
        if (Math.abs(waitMult - 1.0D) < 0.02D) {
            return;
        }

        FishHook hook = event.getHook();
        int minWait = Math.max(20, (int) Math.round(hook.getMinWaitTime() * waitMult));
        int maxWait = Math.max(minWait + 20, (int) Math.round(hook.getMaxWaitTime() * waitMult));
        hook.setMinWaitTime(minWait);
        hook.setMaxWaitTime(maxWait);
    }

    // =========================================================================
    // 9. SYNERGIE REPAS CHAUDS & BOISSONS FRAÎCHES / BREWERYX (PlayerItemConsumeEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!isEligibleOverworld(world)) {
            return;
        }

        TemperatureTask tempTask = this.plugin.getTemperatureTask();
        if (tempTask == null) {
            return;
        }

        ItemStack item = event.getItem();
        Material type = item.getType();
        UUID playerId = player.getUniqueId();
        Season currentSeason = this.seasonManager.getCurrentSeason();

        // 1. Ragoûts et soupes chaudes : +4.0°C pendant 90s
        if (HOT_STEW_MATERIALS.contains(type)) {
            tempTask.grantWarmingBuff(playerId);
            return;
        }

        // 2. Fioles, Lait, Pastèque, Baies & Boissons BreweryX
        if (REFRESHING_CONSUME_MATERIALS.contains(type)) {
            // Si on est en Hiver/Automne et qu'il s'agit d'une boisson custom BreweryX (ItemMeta custom/lore), réchauffe !
            if ((currentSeason == Season.WINTER || currentSeason == Season.AUTUMN)
                    && type == Material.POTION
                    && isCustomBreweryPotion(item)) {
                tempTask.grantWarmingBuff(playerId);
                return;
            }

            // En Été ou si la température ressentie du joueur dépasse 30°C : buff de fraîcheur (-4.0°C pendant 60s)
            Double cachedTemp = tempTask.getCachedTemperature(playerId);
            double currentTemp = (cachedTemp != null) ? cachedTemp : 25.0D;
            if (currentSeason == Season.SUMMER || currentTemp >= this.configCache.getCoolingMinAmbientTemp()) {
                tempTask.grantCoolingBuff(playerId);
                // Réduit légèrement l'épuisement lié à la chaleur
                player.setExhaustion(Math.max(0.0F, player.getExhaustion() - 0.5F));
            }
        }
    }

    private boolean isCustomBreweryPotion(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (meta.hasDisplayName() || meta.hasLore() || !meta.getPersistentDataContainer().isEmpty());
    }

    // =========================================================================
    // UTILITAIRES O(1) : CHAUFFAGE & DÉTECTION PARESSEUSE DE SERRE EN VERRE
    // =========================================================================
    private boolean isEligibleOverworld(World world) {
        return world.getEnvironment() == World.Environment.NORMAL
                && this.configCache.isWorldEnabled(world.getName());
    }

    private boolean isHeatedOrUnderGreenhouse(Block plantBlock, SeasonSettings settings) {
        if (plantBlock.getLightFromBlocks() >= settings.minBlockLightToGrow()) {
            return true;
        }
        return isUnderGreenhouseGlass(plantBlock, settings.greenhouseMaxGlassHeight());
    }

    private boolean isUnderGreenhouseGlass(Block plantBlock, int maxGlassHeight) {
        World world = plantBlock.getWorld();
        int baseX = plantBlock.getX();
        int baseY = plantBlock.getY();
        int baseZ = plantBlock.getZ();
        int maxY = Math.min(world.getMaxHeight() - 1, baseY + maxGlassHeight);

        for (int y = baseY + 1; y <= maxY; y++) {
            Material type = world.getBlockAt(baseX, y, baseZ).getType();
            if (type.isAir()) {
                continue;
            }
            if (Tag.IMPERMEABLE.isTagged(type) || GLASS_MATERIALS.contains(type)) {
                return true;
            }
            if (type.isOccluding()) {
                return false;
            }
        }
        return false;
    }
}
