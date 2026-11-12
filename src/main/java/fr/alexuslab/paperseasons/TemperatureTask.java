package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonConfigCache.SubSeasonSettings;
import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SeasonalEvent;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tâche de thermorégulation réaliste v1.1 (O(1)) exécutée toutes les 40 ticks (2 secondes).
 *
 * Nouveautés v1.1 :
 * 1. Inertie thermique annuelle par interpolation cosinus continue entre les 12 sous-saisons.
 * 2. Cycle solaire diurne/nocturne (Math.sin, pic à 7000t, creux à 19000t), atténué de 80% sous abri ou pluie.
 * 3. Système de Mouillure O(1) (0..100%) avec séchage accéléré près d'un feu/torche (BlockLight >= 12).
 * 4. Wind Chill (refroidissement éolien) en haute altitude (Y > 95) sur les sommets Terralith.
 * 5. Buffs thermiques temporaires liés aux boissons fraîches (-4°C, 60s) et ragoûts chauds (+4°C, 90s).
 */
public final class TemperatureTask implements Runnable {

    public record ThermalBreakdown(
            double totalTemperature,
            double biomeBaseTemp,
            double interpolatedSeasonMod,
            double effectiveSeasonAndWeatherMod,
            double seasonalEventOffset,
            double diurnalOffset,
            double altitudeOffset,
            double windChillOffset,
            int wetnessPercent,
            double wetnessThermalOffset,
            double armorBonus,
            boolean armorInsulated,
            int fireProtectionLevels,
            double heldItemHeat,
            double blockLightHeat,
            double consumableAndBreweryBonus,
            boolean sheltered,
            boolean breweryActive,
            long warmingSecondsLeft,
            long coolingSecondsLeft
    ) {}

    private final PaperSeasonsPlugin plugin;
    private final SeasonConfigCache configCache;
    private final SeasonManager seasonManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Cache réseau par joueur pour économiser la bande passante Playit.gg
    private final Map<UUID, Double> lastDisplayedTemperature = new HashMap<>();
    private final Map<UUID, SubSeason> lastDisplayedSubSeason = new HashMap<>();
    private final Map<UUID, Integer> lastDisplayedWetnessBucket = new HashMap<>();

    // État dynamique O(1) en RAM par joueur
    private final Map<UUID, Double> currentTemperatures = new HashMap<>();
    private final Map<UUID, Integer> wetnessLevels = new HashMap<>();
    private final Map<UUID, Integer> consecutiveFreezeChecks = new HashMap<>();
    private final Map<UUID, Integer> virtualFreezeTicks = new HashMap<>();
    private final Map<UUID, Long> coolingBuffExpiry = new HashMap<>();
    private final Map<UUID, Long> warmingBuffExpiry = new HashMap<>();
    private final Map<UUID, double[]> customWarmBuffs = new HashMap<>();
    private final Map<UUID, double[]> customCoolBuffs = new HashMap<>();
    private final NamespacedKey liningKey;

    // Hook réflexion léger vers BreweryX (com.dre.brewery.BPlayer) mis en cache une seule fois
    private boolean breweryReflectionInitialized = false;
    private Method bPlayerGetMethod = null;
    private Method bPlayerGetDrunkenessMethod = null;

    private BukkitTask scheduledTask;

    public TemperatureTask(PaperSeasonsPlugin plugin, SeasonConfigCache configCache, SeasonManager seasonManager) {
        this.plugin = plugin;
        this.configCache = configCache;
        this.seasonManager = seasonManager;
        this.liningKey = new NamespacedKey(plugin, "thermal_lining");
    }

    public void start() {
        stop();
        initBreweryHook();
        if (!this.configCache.isThermoregulationEnabled()) {
            return;
        }
        long interval = this.configCache.getCheckIntervalTicks();
        this.scheduledTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this, interval, interval);
    }

    public void stop() {
        if (this.scheduledTask != null) {
            this.scheduledTask.cancel();
            this.scheduledTask = null;
        }
    }

    private void initBreweryHook() {
        if (this.breweryReflectionInitialized) {
            return;
        }
        this.breweryReflectionInitialized = true;
        if (Bukkit.getPluginManager().getPlugin("BreweryX") == null
                && Bukkit.getPluginManager().getPlugin("Brewery") == null) {
            return;
        }
        try {
            Class<?> bPlayerClass = Class.forName("com.dre.brewery.BPlayer");
            this.bPlayerGetMethod = bPlayerClass.getMethod("get", OfflinePlayer.class);
            this.bPlayerGetDrunkenessMethod = bPlayerClass.getMethod("getDrunkeness");
            this.plugin.getLogger().info("[PaperSeasons] Hook thermique BreweryX (BPlayer) activé avec succès.");
        } catch (ReflectiveOperationException ignored) {
            this.bPlayerGetMethod = null;
            this.bPlayerGetDrunkenessMethod = null;
        }
    }

    @Override
    public void run() {
        if (!this.configCache.isThermoregulationEnabled()) {
            return;
        }
        Season currentSeason = this.seasonManager.getCurrentSeason();
        SubSeason currentSubSeason = this.seasonManager.getCurrentSubSeason();
        SeasonSettings settings = this.configCache.getSeasonSettings(currentSeason);
        SubSeasonSettings subSettings = this.configCache.getSubSeasonSettings(currentSubSeason);
        if (settings == null || subSettings == null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) {
                continue;
            }

            World world = player.getWorld();
            if (!this.configCache.isWorldEnabled(world.getName())
                    || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }

            UUID playerId = player.getUniqueId();
            updatePlayerWetnessO1(player, playerId, world, currentSeason);

            ThermalBreakdown breakdown = computeThermalBreakdownO1(player, world, currentSeason, currentSubSeason, settings);
            double temp = breakdown.totalTemperature();
            this.currentTemperatures.put(playerId, temp);

            GameMode gm = player.getGameMode();
            boolean vulnerable = (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE);
            if (vulnerable) {
                applyThermalEffects(player, playerId, temp);
            }

            if (this.configCache.isActionbarEnabled()) {
                updateActionBarIfNeeded(player, playerId, currentSubSeason, subSettings, breakdown);
            }
        }
    }

    /**
     * Met à jour le niveau d'humidité/mouillure (0..100%) en O(1) à chaque cycle de 40 ticks.
     */
    private void updatePlayerWetnessO1(Player player, UUID playerId, World world, Season currentSeason) {
        int currentWetness = this.wetnessLevels.getOrDefault(playerId, 0);
        Block block = player.getLocation().getBlock();
        int skyLight = block.getLightFromSky();
        int blockLight = block.getLightFromBlocks();

        int nextWetness;
        if (player.isInWater()) {
            nextWetness = 100;
        } else if (world.hasStorm()
                && skyLight >= 13
                && block.getTemperature() >= 0.15D
                && currentSeason != Season.WINTER) {
            EntityEquipment eq = player.getEquipment();
            boolean hasHelmet = eq != null && eq.getHelmet() != null && !eq.getHelmet().getType().isAir();
            int gain = hasHelmet
                    ? this.configCache.getWetnessRainGainWithHelmet()
                    : this.configCache.getWetnessRainGainNoHelmet();
            nextWetness = Math.min(100, currentWetness + gain);
        } else {
            int dryRate = (blockLight >= this.configCache.getWetnessHeatedDryMinBlockLight())
                    ? this.configCache.getWetnessHeatedDryRate()
                    : this.configCache.getWetnessPassiveDryRate();
            if (this.seasonManager.getCurrentEvent() == SeasonalEvent.HEATWAVE) {
                dryRate *= 2;
            }
            nextWetness = Math.max(0, currentWetness - dryRate);
        }

        if (nextWetness == 0) {
            this.wetnessLevels.remove(playerId);
        } else {
            this.wetnessLevels.put(playerId, nextWetness);
        }
    }

    /**
     * Calcul O(1) complet et détaillé de la température ressentie (utilisé par la boucle et /season status).
     */
    public ThermalBreakdown computeThermalBreakdownO1(
            Player player,
            World world,
            Season currentSeason,
            SubSeason currentSubSeason,
            SeasonSettings settings
    ) {
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        UUID playerId = player.getUniqueId();
        long worldTime = world.getTime();

        // 1. Température de base du biome (compatible Terralith)
        double biomeBaseTemp = block.getTemperature() * this.configCache.getBiomeBaseTempMultiplier();

        // 2. Inertie thermique annuelle par interpolation Cosinus entre currentSubSeason et currentSubSeason.next()
        SubSeason nextSubSeason = currentSubSeason.next();
        SubSeasonSettings currSubSettings = this.configCache.getSubSeasonSettings(currentSubSeason);
        SubSeasonSettings nextSubSettings = this.configCache.getSubSeasonSettings(nextSubSeason);

        double modStart = currSubSettings != null ? currSubSettings.temperatureModifier() : settings.temperatureModifier();
        double modEnd = nextSubSettings != null ? nextSubSettings.temperatureModifier() : modStart;
        double progress = this.seasonManager.getSubSeasonProgress(worldTime);
        double cosineWeight = (1.0D - Math.cos(progress * Math.PI)) * 0.5D;
        double interpolatedSeasonMod = modStart + (modEnd - modStart) * cosineWeight;

        // 3. Météo & Isolation intérieure / grottes (SkyLight < 10 -> réduction de 70%)
        double rawSeasonAndWeatherMod = interpolatedSeasonMod;
        if (world.hasStorm()) {
            rawSeasonAndWeatherMod += settings.weatherColdPenalty();
        }

        int skyLight = block.getLightFromSky();
        boolean sheltered = skyLight < this.configCache.getIndoorSkyLightThreshold();
        double effectiveSeasonMod = sheltered
                ? rawSeasonAndWeatherMod * this.configCache.getIndoorSeasonMitigationFactor()
                : rawSeasonAndWeatherMod;

        // 4. Cycle Solaire Diurne / Nocturne (Math.sin, pic à ~7000 ticks, creux à ~19000 ticks)
        double baseAmplitude = settings.diurnalAmplitude();
        double effectiveAmplitude = (sheltered || world.hasStorm())
                ? baseAmplitude * this.configCache.getDiurnalShelteredAttenuation()
                : baseAmplitude;
        double diurnalOffset = Math.sin(((worldTime - 1000.0D) / 24000.0D) * 2.0D * Math.PI) * effectiveAmplitude;

        // 5. Altitude : - ((Y - 64) * 0.05)
        int blockY = loc.getBlockY();
        double altitudeOffset = (blockY - this.configCache.getAltitudeSeaLevel())
                * this.configCache.getAltitudeFactorPerBlock();

        // 6. Refroidissement Éolien (Wind Chill sur les reliefs Terralith Y > 95 exposés au ciel)
        SeasonalEvent activeEvent = this.seasonManager.getCurrentEvent();
        double windChillOffset = 0.0D;
        if (blockY > this.configCache.getWindChillMinAltitudeY()
                && skyLight >= this.configCache.getWindChillMinSkyLight()) {
            double rawWind = Math.min(
                    this.configCache.getMaxBaseWindChill(),
                    (blockY - this.configCache.getWindChillMinAltitudeY()) * this.configCache.getWindChillFactorPerBlock()
            );
            if (world.hasStorm()) {
                rawWind *= this.configCache.getWindChillStormMultiplier();
            }
            if (activeEvent == SeasonalEvent.BLIZZARD) {
                rawWind *= 2.0D;
            }
            windChillOffset = -rawWind;
        }

        // 6b. Impact thermique des Événements Climatiques (BLIZZARD / HEATWAVE)
        double seasonalEventOffset = 0.0D;
        if (activeEvent == SeasonalEvent.BLIZZARD && skyLight >= 12) {
            seasonalEventOffset = this.configCache.getBlizzardExtraCold();
        } else if (activeEvent == SeasonalEvent.HEATWAVE && worldTime >= 2000L && worldTime <= 10000L) {
            double rawHeatwave = this.configCache.getHeatwaveExtraHeat();
            seasonalEventOffset = sheltered
                    ? rawHeatwave * this.configCache.getIndoorSeasonMitigationFactor()
                    : rawHeatwave;
        }

        // 7. Humidité / Mouillure ("Wetness" 0..100%)
        int wetness = this.wetnessLevels.getOrDefault(playerId, 0);
        double dryAmbientTemp = biomeBaseTemp + effectiveSeasonMod + seasonalEventOffset + diurnalOffset - altitudeOffset;
        double wetnessThermalOffset = 0.0D;
        if (wetness > 0) {
            double ratio = wetness / 100.0D;
            if (currentSeason == Season.SUMMER && dryAmbientTemp > this.configCache.getSummerCoolingThresholdCelsius()) {
                // En pleine canicule estivale, se baigner rafraîchit agréablement le joueur
                wetnessThermalOffset = -ratio * this.configCache.getMaxSummerWetRelief();
            } else {
                // Hors canicule (hiver, automne, nuit), être mouillé refroidit fortement le corps
                wetnessThermalOffset = -ratio * this.configCache.getMaxWetColdPenalty();
            }
        }

        // 8. Bonus d'armure via EnumMap O(1) + Équilibrage v1.2 (Enchantement FIRE_PROTECTION & Cuir/Garnitures)
        double armorBonus = 0.0D;
        boolean hasLeatherOrWarmTrim = false;
        int totalFireProtLevels = 0;

        EntityEquipment equipment = player.getEquipment();
        if (equipment != null) {
            ItemStack[] armorContents = equipment.getArmorContents();
            for (int i = 0; i < armorContents.length; i++) {
                ItemStack piece = armorContents[i];
                if (piece == null || piece.getType().isAir()) {
                    continue;
                }
                Material mat = piece.getType();
                if (mat == Material.LEATHER_HELMET || mat == Material.LEATHER_CHESTPLATE
                        || mat == Material.LEATHER_LEGGINGS || mat == Material.LEATHER_BOOTS) {
                    hasLeatherOrWarmTrim = true;
                } else if (!hasLeatherOrWarmTrim && piece.hasItemMeta() && piece.getItemMeta() instanceof ArmorMeta armorMeta && armorMeta.hasTrim()) {
                    ArmorTrim trim = armorMeta.getTrim();
                    if (trim != null) {
                        TrimMaterial tm = trim.getMaterial();
                        if (tm == TrimMaterial.QUARTZ || tm == TrimMaterial.GOLD
                                || tm == TrimMaterial.AMETHYST || tm == TrimMaterial.RESIN || tm == TrimMaterial.COPPER) {
                            hasLeatherOrWarmTrim = true;
                        }
                    }
                }
                totalFireProtLevels += piece.getEnchantmentLevel(Enchantment.FIRE_PROTECTION);
            }

            double metalColdMultiplier = (hasLeatherOrWarmTrim && (currentSeason == Season.WINTER || currentSeason == Season.AUTUMN))
                    ? (1.0D - this.configCache.getInsulatedMetalColdReductionFactor())
                    : 1.0D;

            for (int i = 0; i < armorContents.length; i++) {
                ItemStack piece = armorContents[i];
                if (piece != null && !piece.getType().isAir()) {
                    double pieceBonus = this.configCache.getArmorHeatBonus(piece.getType());
                    if (pieceBonus < 0.0D) {
                        pieceBonus *= metalColdMultiplier;
                    }
                    armorBonus += pieceBonus;

                    // v1.3.0 : Doublures thermiques artisanales (PersistentDataContainer "thermal_lining")
                    String lining = piece.getPersistentDataContainer().get(this.liningKey, PersistentDataType.STRING);
                    if (lining != null) {
                        if (SeasonCraftingManager.LINING_WARM.equals(lining)
                                && (currentSeason == Season.WINTER || currentSeason == Season.AUTUMN)) {
                            armorBonus += SeasonCraftingManager.LINING_CELSIUS;
                            hasLeatherOrWarmTrim = true;
                        } else if (SeasonCraftingManager.LINING_COOL.equals(lining)
                                && (currentSeason == Season.SUMMER || dryAmbientTemp > 30.0D)) {
                            armorBonus -= SeasonCraftingManager.LINING_CELSIUS;
                        }
                    }
                }
            }

            if (totalFireProtLevels > 0 && (dryAmbientTemp > 30.0D || currentSeason == Season.SUMMER)) {
                armorBonus -= totalFireProtLevels * this.configCache.getFireProtectionCoolingPerLevel();
            }
        }

        // 9. Chaleur de l'objet tenu en main principale / secondaire via EnumMap O(1)
        PlayerInventory inv = player.getInventory();
        double mainHandHeat = this.configCache.getHeldItemHeat(inv.getItemInMainHand().getType());
        double offHandHeat = this.configCache.getHeldItemHeat(inv.getItemInOffHand().getType());
        double heldItemHeat = Math.max(mainHandHeat, offHandHeat);

        // 10. Chaleur des blocs lumineux environnants (four, feu de camp, torche, lave) en O(1)
        int blockLight = block.getLightFromBlocks();
        double blockLightHeat = blockLight * this.configCache.getBlockLightHeatMultiplier();

        // 11. Buffs alimentaires temporaires (Soupes chaudes / Boissons fraîches) & BreweryX
        long now = System.currentTimeMillis();
        long warmingLeft = getRemainingBuffSeconds(this.warmingBuffExpiry, playerId, now);
        long coolingLeft = getRemainingBuffSeconds(this.coolingBuffExpiry, playerId, now);
        long customWarmLeft = getRemainingCustomBuffSeconds(this.customWarmBuffs, playerId, now);
        long customCoolLeft = getRemainingCustomBuffSeconds(this.customCoolBuffs, playerId, now);
        boolean breweryActive = (currentSeason == Season.WINTER || currentSeason == Season.AUTUMN)
                && isWarmedByBrewery(player);

        double consumableBonus = 0.0D;
        if (warmingLeft > 0L) {
            consumableBonus += this.configCache.getWarmingBuffCelsius();
        }
        if (coolingLeft > 0L) {
            consumableBonus += this.configCache.getCoolingBuffCelsius();
        }
        if (customWarmLeft > 0L) {
            consumableBonus += this.customWarmBuffs.get(playerId)[0];
            warmingLeft = Math.max(warmingLeft, customWarmLeft);
        }
        if (customCoolLeft > 0L) {
            consumableBonus += this.customCoolBuffs.get(playerId)[0];
            coolingLeft = Math.max(coolingLeft, customCoolLeft);
        }
        if (breweryActive && currentSeason == Season.WINTER) {
            consumableBonus += this.configCache.getBreweryWinterWarmthBonus();
        }

        double totalTemp = biomeBaseTemp
                + effectiveSeasonMod
                + seasonalEventOffset
                + diurnalOffset
                - altitudeOffset
                + windChillOffset
                + wetnessThermalOffset
                + armorBonus
                + heldItemHeat
                + blockLightHeat
                + consumableBonus;

        return new ThermalBreakdown(
                totalTemp,
                biomeBaseTemp,
                interpolatedSeasonMod,
                effectiveSeasonMod,
                seasonalEventOffset,
                diurnalOffset,
                altitudeOffset,
                windChillOffset,
                wetness,
                wetnessThermalOffset,
                armorBonus,
                hasLeatherOrWarmTrim,
                totalFireProtLevels,
                heldItemHeat,
                blockLightHeat,
                consumableBonus,
                sheltered,
                breweryActive,
                warmingLeft,
                coolingLeft
        );
    }

    public double computePlayerTemperatureO1(Player player, World world, Season currentSeason, SeasonSettings settings) {
        return computeThermalBreakdownO1(
                player, world, currentSeason, this.seasonManager.getCurrentSubSeason(), settings
        ).totalTemperature();
    }

    private long getRemainingBuffSeconds(Map<UUID, Long> map, UUID playerId, long nowMs) {
        Long expiry = map.get(playerId);
        if (expiry == null) {
            return 0L;
        }
        long diff = expiry - nowMs;
        if (diff <= 0L) {
            map.remove(playerId);
            return 0L;
        }
        return (diff + 999L) / 1000L;
    }

    public void grantCoolingBuff(UUID playerId) {
        this.coolingBuffExpiry.put(playerId, System.currentTimeMillis() + this.configCache.getCoolingBuffDurationMs());
        this.lastDisplayedTemperature.remove(playerId);
    }

    public void grantWarmingBuff(UUID playerId) {
        this.warmingBuffExpiry.put(playerId, System.currentTimeMillis() + this.configCache.getWarmingBuffDurationMs());
        this.lastDisplayedTemperature.remove(playerId);
    }

    /**
     * v1.3.0 : Buff thermique personnalisé (Soupe Forestière +6°C, Champignon doré +3°C, Pomme d'été -4°C...).
     * Un emplacement chaud et un emplacement froid par joueur (le plus récent remplace le précédent).
     */
    public void grantCustomBuff(UUID playerId, double celsius, long durationMs) {
        double[] entry = {celsius, System.currentTimeMillis() + durationMs};
        if (celsius >= 0.0D) {
            this.customWarmBuffs.put(playerId, entry);
        } else {
            this.customCoolBuffs.put(playerId, entry);
        }
        this.lastDisplayedTemperature.remove(playerId);
    }

    private long getRemainingCustomBuffSeconds(Map<UUID, double[]> map, UUID playerId, long nowMs) {
        double[] entry = map.get(playerId);
        if (entry == null) {
            return 0L;
        }
        long diff = (long) entry[1] - nowMs;
        if (diff <= 0L) {
            map.remove(playerId);
            return 0L;
        }
        return (diff + 999L) / 1000L;
    }

    public int getPlayerWetness(UUID playerId) {
        return this.wetnessLevels.getOrDefault(playerId, 0);
    }

    public boolean isWarmedByBrewery(Player player) {
        if (player.hasPotionEffect(PotionEffectType.NAUSEA)
                || player.hasMetadata("brewery_drunk")
                || player.getScoreboardTags().contains("brewery_warmth")) {
            return true;
        }

        if (this.bPlayerGetMethod != null && this.bPlayerGetDrunkenessMethod != null) {
            try {
                Object bPlayer = this.bPlayerGetMethod.invoke(null, player);
                if (bPlayer != null) {
                    int drunkeness = (int) this.bPlayerGetDrunkenessMethod.invoke(bPlayer);
                    return drunkeness > 0;
                }
            } catch (ReflectiveOperationException ignored) {
                this.bPlayerGetMethod = null;
            }
        }
        return false;
    }

    private void applyThermalEffects(Player player, UUID playerId, double temp) {
        int maxFreeze = Math.max(140, player.getMaxFreezeTicks());
        int added = this.configCache.getFreezeTicksAddedPerCheck();
        // Vanilla décrémente FreezeTicks de 2 par tick hors bloc de poudreuse (soit 80 sur 40 ticks)
        int vanillaDecayCompensation = (int) (this.configCache.getCheckIntervalTicks() * 2L);

        if (temp < this.configCache.getFreezeThresholdCelsius()) {
            int checks = this.consecutiveFreezeChecks.getOrDefault(playerId, 0) + 1;
            this.consecutiveFreezeChecks.put(playerId, checks);

            int prevVirtual = this.virtualFreezeTicks.getOrDefault(playerId, 0);
            int nextVirtual = Math.min(maxFreeze, prevVirtual + added);
            this.virtualFreezeTicks.put(playerId, nextVirtual);

            // Maintient l'effet visuel de givre et les cœurs bleus sans clignotement entre deux checks
            player.setFreezeTicks(nextVirtual + vanillaDecayCompensation);

            if (checks >= this.configCache.getHypothermiaDamageAfterChecks() || nextVirtual >= maxFreeze) {
                double damage = (temp <= this.configCache.getHypothermiaSevereThresholdCelsius())
                        ? this.configCache.getHypothermiaSevereDamage()
                        : this.configCache.getHypothermiaDamage();
                if (damage > 0.0D) {
                    try {
                        DamageSource freezeSource = DamageSource.builder(DamageType.FREEZE).build();
                        player.damage(damage, freezeSource);
                    } catch (Throwable fallback) {
                        player.damage(damage);
                    }
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 0.85F, 1.0F);
                }
            } else {
                // Frisson d'avertissement dès l'entrée en hypothermie
                player.playSound(player.getLocation(), Sound.BLOCK_POWDER_SNOW_STEP, 0.75F, 0.8F);
            }
        } else {
            this.consecutiveFreezeChecks.remove(playerId);
            int prevVirtual = this.virtualFreezeTicks.getOrDefault(playerId, 0);
            if (prevVirtual > 0) {
                int cooledDown = Math.max(0, prevVirtual - added);
                if (cooledDown == 0) {
                    this.virtualFreezeTicks.remove(playerId);
                } else {
                    this.virtualFreezeTicks.put(playerId, cooledDown);
                }
            }

            if (temp > this.configCache.getHeatstrokeThresholdCelsius()) {
                float currentExhaustion = player.getExhaustion();
                player.setExhaustion(currentExhaustion + this.configCache.getHeatstrokeExhaustionAdded());
            }
        }
    }

    private void updateActionBarIfNeeded(
            Player player,
            UUID playerId,
            SubSeason currentSubSeason,
            SubSeasonSettings subSettings,
            ThermalBreakdown breakdown
    ) {
        double temp = breakdown.totalTemperature();
        int wetnessBucket = breakdown.wetnessPercent() / 10;

        Double lastTemp = this.lastDisplayedTemperature.get(playerId);
        SubSeason lastSub = this.lastDisplayedSubSeason.get(playerId);
        Integer lastWetBucket = this.lastDisplayedWetnessBucket.get(playerId);

        boolean isExtreme = (temp < this.configCache.getFreezeThresholdCelsius()
                || temp > this.configCache.getHeatstrokeThresholdCelsius());

        if (!isExtreme
                && lastSub == currentSubSeason
                && lastTemp != null
                && Math.abs(temp - lastTemp) < this.configCache.getTemperatureDeltaThreshold()
                && lastWetBucket != null
                && lastWetBucket == wetnessBucket) {
            return;
        }

        this.lastDisplayedTemperature.put(playerId, temp);
        this.lastDisplayedSubSeason.put(playerId, currentSubSeason);
        this.lastDisplayedWetnessBucket.put(playerId, wetnessBucket);

        String formattedTemp = String.format(Locale.US, "%+.1f", temp);
        StringBuilder badges = new StringBuilder();
        SeasonalEvent activeEvent = this.seasonManager.getCurrentEvent();
        if (activeEvent != SeasonalEvent.NONE) {
            badges.append(" ").append(activeEvent.getActionbarBadgeRaw());
        }
        if (breakdown.wetnessPercent() >= 20) {
            badges.append(" <blue>💧").append(breakdown.wetnessPercent()).append("%</blue>");
        }
        if (breakdown.windChillOffset() <= -1.5D) {
            badges.append(" <white>🌬️</white>");
        }
        if (breakdown.warmingSecondsLeft() > 0L) {
            badges.append(" <gold>🍲</gold>");
        }
        if (breakdown.coolingSecondsLeft() > 0L) {
            badges.append(" <aqua>🧊</aqua>");
        }

        String template;
        if (temp < this.configCache.getFreezeThresholdCelsius()) {
            template = this.configCache.getFormatFreezing();
        } else if (temp > this.configCache.getHeatstrokeThresholdCelsius()) {
            template = this.configCache.getFormatOverheating();
        } else {
            template = this.configCache.getFormatNormal();
        }

        // Compatibilité ascendante si un ancien config.yml n'avait pas le tag <badges>
        if (!template.contains("<badges>")) {
            template = template + "<badges>";
        }

        Component badgeComp = badges.isEmpty()
                ? Component.empty()
                : this.miniMessage.deserialize(badges.toString());

        Component actionbarComponent = this.miniMessage.deserialize(
                template,
                Placeholder.component("season", subSettings.displayComponent()),
                Placeholder.unparsed("temp", formattedTemp),
                Placeholder.component("badges", badgeComp)
        );
        ((Audience) player).sendActionBar(actionbarComponent);
    }

    public void invalidateAllActionBars() {
        this.lastDisplayedSubSeason.clear();
        this.lastDisplayedTemperature.clear();
        this.lastDisplayedWetnessBucket.clear();
    }

    public void removePlayerCache(UUID playerId) {
        this.lastDisplayedSubSeason.remove(playerId);
        this.lastDisplayedTemperature.remove(playerId);
        this.lastDisplayedWetnessBucket.remove(playerId);
        this.currentTemperatures.remove(playerId);
        this.wetnessLevels.remove(playerId);
        this.consecutiveFreezeChecks.remove(playerId);
        this.virtualFreezeTicks.remove(playerId);
        this.coolingBuffExpiry.remove(playerId);
        this.warmingBuffExpiry.remove(playerId);
        this.customWarmBuffs.remove(playerId);
        this.customCoolBuffs.remove(playerId);
    }

    public Double getCachedTemperature(UUID playerId) {
        return this.currentTemperatures.get(playerId);
    }

    public int getVirtualFreezeTicks(UUID playerId) {
        return this.virtualFreezeTicks.getOrDefault(playerId, 0);
    }
}
