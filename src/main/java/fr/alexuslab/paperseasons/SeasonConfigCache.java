package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Cache immuable en mémoire (O(1)) de toute la configuration PaperSeasons v1.1.
 * Zéro lecture YAML à chaud dans TemperatureTask ou les Listeners :
 * toutes les tables (armures, objets en main, calendrier agricole par espèce, 12 sous-saisons)
 * passent par des EnumMap indexées par ordinal.
 */
public final class SeasonConfigCache {

    public record SeasonSettings(
            String displayNameRaw,
            Component displayComponent,
            String clientBiomeKey,
            double temperatureModifier,
            double diurnalAmplitude,
            double daySpeedMultiplier,
            double nightSpeedMultiplier,
            double weatherColdPenalty,
            double rainChanceMultiplier,
            double cropGrowthBoostChance,
            boolean iceSnowMeltEnabled,
            int minBlockLightToGrow,
            int greenhouseMaxGlassHeight,
            double breedingTwinChance,
            double breedingOutdoorFailChance,
            double fishingWaitMultiplier
    ) {}

    public record SubSeasonSettings(
            SubSeason subSeason,
            String displayNameRaw,
            Component displayComponent,
            String clientBiomeKey,
            double temperatureModifier
    ) {}

    private final Set<String> enabledWorlds = new HashSet<>();
    private final EnumMap<Season, SeasonSettings> seasonSettings = new EnumMap<>(Season.class);
    private final EnumMap<SubSeason, SubSeasonSettings> subSeasonSettings = new EnumMap<>(SubSeason.class);
    private final EnumMap<Material, Double> armorBonusMap = new EnumMap<>(Material.class);
    private final EnumMap<Material, Double> heldItemHeatMap = new EnumMap<>(Material.class);
    private final EnumMap<Season, EnumMap<Material, Double>> seasonalCropMultipliers = new EnumMap<>(Season.class);

    // Calendrier & Événements climatiques rares (v1.2.0)
    private int daysPerSeason = 9;
    private long daylightCycleCheckIntervalTicks = 2L;
    private boolean eventsEnabled = true;
    private double dailyEventTriggerChance = 0.20D;
    private double blizzardExtraCold = -8.0D;
    private double heatwaveExtraHeat = 7.0D;
    private double autumnMistFishingMultiplier = 0.60D;
    private double springBloomMultiplier = 2.0D;

    // Équilibrage armures v1.2.0 (Enchantements & Garnitures)
    private double fireProtectionCoolingPerLevel = 0.75D;
    private double insulatedMetalColdReductionFactor = 0.50D;

    // Thermorégulation (Primitives O(1))
    private boolean thermoregulationEnabled = true;
    private long checkIntervalTicks = 40L;
    private double biomeBaseTempMultiplier = 20.0D;
    private int altitudeSeaLevel = 64;
    private double altitudeFactorPerBlock = 0.05D;
    private double blockLightHeatMultiplier = 0.8D;
    private double diurnalShelteredAttenuation = 0.20D;

    // Isolation intérieure / grottes
    private int indoorSkyLightThreshold = 10;
    private double indoorSeasonMitigationFactor = 0.30D;

    // Mouillure ("Wetness")
    private int wetnessRainGainNoHelmet = 15;
    private int wetnessRainGainWithHelmet = 5;
    private int wetnessPassiveDryRate = 5;
    private int wetnessHeatedDryRate = 25;
    private int wetnessHeatedDryMinBlockLight = 12;
    private double maxWetColdPenalty = 8.0D;
    private double summerCoolingThresholdCelsius = 32.0D;
    private double maxSummerWetRelief = 6.0D;

    // Refroidissement éolien ("Wind Chill")
    private int windChillMinAltitudeY = 95;
    private int windChillMinSkyLight = 13;
    private double windChillFactorPerBlock = 0.08D;
    private double maxBaseWindChill = 6.0D;
    private double windChillStormMultiplier = 2.0D;

    // Buffs alimentaires & BreweryX
    private double breweryWinterWarmthBonus = 3.0D;
    private double coolingBuffCelsius = -4.0D;
    private long coolingBuffDurationMs = 60_000L;
    private double coolingMinAmbientTemp = 30.0D;
    private double warmingBuffCelsius = 4.0D;
    private long warmingBuffDurationMs = 90_000L;

    // Seuils gel, hypothermie directe & canicule
    private double freezeThresholdCelsius = 0.0D;
    private int freezeTicksAddedPerCheck = 55;
    private double hypothermiaDamage = 1.0D;
    private double hypothermiaSevereDamage = 2.0D;
    private double hypothermiaSevereThresholdCelsius = -10.0D;
    private int hypothermiaDamageAfterChecks = 2;
    private int winterSnowColumnsPerCheck = 8;
    private double heatstrokeThresholdCelsius = 38.0D;
    private float heatstrokeExhaustionAdded = 0.35F;

    // UI ActionBar
    private boolean actionbarEnabled = true;
    private double temperatureDeltaThreshold = 0.5D;
    private String formatNormal = "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <aqua>🌡️ <temp>°C<badges></aqua>";
    private String formatFreezing = "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <aqua>🥶 <bold><temp>°C (Gel !)<badges></bold></aqua>";
    private String formatOverheating = "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <gold>🔥 <bold><temp>°C (Canicule !)<badges></bold></gold>";

    // Hook réseau Packet / Netty
    private boolean packetBiomeSpoofingEnabled = true;
    private boolean preserveNetherAndEnd = true;
    private boolean preserveOceanAndCaveBiomes = true;
    private int minSurfaceY = 48;

    public void reload(FileConfiguration config, Logger logger) {
        this.enabledWorlds.clear();
        List<String> worlds = config.getStringList("enabled-worlds");
        if (worlds.isEmpty()) {
            this.enabledWorlds.add("world");
        } else {
            this.enabledWorlds.addAll(worlds);
        }

        // Calendrier & Événements climatiques v1.2.0
        this.daysPerSeason = Math.max(3, config.getInt("calendar.days-per-season", 9));
        this.daylightCycleCheckIntervalTicks = Math.max(1L, config.getLong("calendar.daylight-cycle-check-interval-ticks", 2L));
        this.eventsEnabled = config.getBoolean("events.enabled", true);
        this.dailyEventTriggerChance = Math.clamp(config.getDouble("events.daily-trigger-chance", 0.20D), 0.0D, 1.0D);
        this.blizzardExtraCold = config.getDouble("events.blizzard-extra-cold", -8.0D);
        this.heatwaveExtraHeat = config.getDouble("events.heatwave-extra-heat", 7.0D);
        this.autumnMistFishingMultiplier = Math.clamp(config.getDouble("events.autumn-mist-fishing-multiplier", 0.60D), 0.1D, 2.0D);
        this.springBloomMultiplier = Math.clamp(config.getDouble("events.spring-bloom-multiplier", 2.0D), 1.0D, 5.0D);

        // Saisons & 12 Sous-Saisons
        this.seasonSettings.clear();
        this.subSeasonSettings.clear();
        MiniMessage mm = MiniMessage.miniMessage();

        for (Season season : Season.values()) {
            String path = "seasons." + season.name() + ".";
            String displayRaw = config.getString(path + "display-name", season.name());
            Component displayComp = mm.deserialize(displayRaw);
            String biomeKey = config.getString(path + "client-biome-key", "paperseasons:" + season.name().toLowerCase(Locale.ROOT) + "_mid");
            double tempMod = config.getDouble(path + "temperature-modifier", 0.0D);
            double diurnalAmp = config.getDouble(path + "diurnal-amplitude", 4.5D);
            double daySpeed = Math.clamp(config.getDouble(path + "day-speed-multiplier", 1.0D), 0.1D, 1.0D);
            double nightSpeed = Math.clamp(config.getDouble(path + "night-speed-multiplier", 1.0D), 0.1D, 1.0D);
            double weatherPenalty = config.getDouble(path + "weather-cold-penalty", -1.5D);
            double rainMultiplier = Math.clamp(
                    config.getDouble(path + "rain-chance-multiplier",
                            config.getDouble(path + "weather.rain-chance-multiplier", 1.0D)),
                    0.0D, 3.0D
            );
            double cropBoost = Math.clamp(config.getDouble(path + "crop-growth-boost-chance", 0.0D), 0.0D, 1.0D);
            boolean meltEnabled = config.getBoolean(path + "ice-snow-melt-enabled", season == Season.SPRING || season == Season.SUMMER);
            int minLight = config.getInt(path + "min-block-light-to-grow", 12);
            int maxGlassHeight = config.getInt(path + "greenhouse-max-glass-height", 12);
            double twinChance = Math.clamp(config.getDouble(path + "breeding-twin-chance", season == Season.SPRING ? 0.25D : 0.0D), 0.0D, 1.0D);
            double breedFail = Math.clamp(config.getDouble(path + "breeding-outdoor-fail-chance", season == Season.WINTER ? 0.50D : 0.0D), 0.0D, 1.0D);
            double fishMult = Math.clamp(config.getDouble(path + "fishing-wait-multiplier", 1.0D), 0.2D, 3.0D);

            this.seasonSettings.put(season, new SeasonSettings(
                    displayRaw,
                    displayComp,
                    biomeKey,
                    tempMod,
                    diurnalAmp,
                    daySpeed,
                    nightSpeed,
                    weatherPenalty,
                    rainMultiplier,
                    cropBoost,
                    meltEnabled,
                    minLight,
                    maxGlassHeight,
                    twinChance,
                    breedFail,
                    fishMult
            ));
        }

        for (SubSeason sub : SubSeason.values()) {
            Season parent = sub.getParentSeason();
            SeasonSettings parentSettings = this.seasonSettings.get(parent);
            String subPath = "seasons." + parent.name() + ".sub-seasons." + sub.getPhaseKey() + ".";

            String subDisplayRaw = config.getString(subPath + "display-name",
                    parentSettings != null ? parentSettings.displayNameRaw() : sub.name());
            Component subDisplayComp = mm.deserialize(subDisplayRaw);
            String subBiomeKey = config.getString(subPath + "client-biome-key", sub.getDefaultBiomeKey());
            double defaultSubTemp = parentSettings != null ? parentSettings.temperatureModifier() : 0.0D;
            double subTempMod = config.getDouble(subPath + "temperature-modifier", defaultSubTemp);

            this.subSeasonSettings.put(sub, new SubSeasonSettings(
                    sub,
                    subDisplayRaw,
                    subDisplayComp,
                    subBiomeKey,
                    subTempMod
            ));
        }

        // Calendrier Agricole Biologique par espèce (EnumMap<Season, EnumMap<Material, Double>>)
        initBiologicalCropCalendar();

        // Thermorégulation
        this.thermoregulationEnabled = config.getBoolean("thermoregulation.enabled", true);
        this.checkIntervalTicks = Math.max(10L, config.getLong("thermoregulation.check-interval-ticks", 40L));
        this.biomeBaseTempMultiplier = config.getDouble("thermoregulation.biome-base-temp-multiplier", 20.0D);
        this.altitudeSeaLevel = config.getInt("thermoregulation.altitude-sea-level", 64);
        this.altitudeFactorPerBlock = config.getDouble("thermoregulation.altitude-factor-per-block", 0.05D);
        this.blockLightHeatMultiplier = config.getDouble("thermoregulation.block-light-heat-multiplier", 0.8D);
        this.diurnalShelteredAttenuation = Math.clamp(
                config.getDouble("thermoregulation.diurnal-sheltered-attenuation", 0.20D),
                0.0D, 1.0D
        );

        // Isolation intérieure (LightFromSky < 10 -> réduction de 70% de l'impact saison/météo)
        this.indoorSkyLightThreshold = config.getInt("thermoregulation.indoor-sky-light-threshold", 10);
        this.indoorSeasonMitigationFactor = Math.clamp(
                config.getDouble("thermoregulation.indoor-season-mitigation-factor", 0.30D),
                0.0D, 1.0D
        );

        // Mouillure ("Wetness")
        this.wetnessRainGainNoHelmet = config.getInt("thermoregulation.wetness.rain-gain-no-helmet", 15);
        this.wetnessRainGainWithHelmet = config.getInt("thermoregulation.wetness.rain-gain-with-helmet", 5);
        this.wetnessPassiveDryRate = config.getInt("thermoregulation.wetness.passive-dry-rate", 5);
        this.wetnessHeatedDryRate = config.getInt("thermoregulation.wetness.heated-dry-rate", 25);
        this.wetnessHeatedDryMinBlockLight = config.getInt("thermoregulation.wetness.heated-dry-min-block-light", 12);
        this.maxWetColdPenalty = config.getDouble("thermoregulation.wetness.max-wet-cold-penalty", 8.0D);
        this.summerCoolingThresholdCelsius = config.getDouble("thermoregulation.wetness.summer-cooling-threshold-celsius", 32.0D);
        this.maxSummerWetRelief = config.getDouble("thermoregulation.wetness.max-summer-wet-relief", 6.0D);

        // Wind Chill
        this.windChillMinAltitudeY = config.getInt("thermoregulation.wind-chill.min-altitude-y", 95);
        this.windChillMinSkyLight = config.getInt("thermoregulation.wind-chill.min-sky-light", 13);
        this.windChillFactorPerBlock = config.getDouble("thermoregulation.wind-chill.factor-per-block-above-min", 0.08D);
        this.maxBaseWindChill = config.getDouble("thermoregulation.wind-chill.max-base-wind-chill", 6.0D);
        this.windChillStormMultiplier = config.getDouble("thermoregulation.wind-chill.storm-multiplier", 2.0D);

        // Buffs alimentaires & BreweryX
        this.breweryWinterWarmthBonus = config.getDouble("thermoregulation.brewery-winter-warmth-bonus", 3.0D);
        this.coolingBuffCelsius = config.getDouble("thermoregulation.consumable-buffs.cooling-buff-celsius", -4.0D);
        this.coolingBuffDurationMs = config.getLong("thermoregulation.consumable-buffs.cooling-duration-seconds", 60L) * 1000L;
        this.coolingMinAmbientTemp = config.getDouble("thermoregulation.consumable-buffs.cooling-min-ambient-temp", 30.0D);
        this.warmingBuffCelsius = config.getDouble("thermoregulation.consumable-buffs.warming-buff-celsius", 4.0D);
        this.warmingBuffDurationMs = config.getLong("thermoregulation.consumable-buffs.warming-duration-seconds", 90L) * 1000L;

        // Seuils & Hypothermie directe
        this.freezeThresholdCelsius = config.getDouble("thermoregulation.freeze-threshold-celsius", 0.0D);
        this.freezeTicksAddedPerCheck = config.getInt("thermoregulation.freeze-ticks-added-per-check", 55);
        this.hypothermiaDamage = Math.max(0.0D, config.getDouble("thermoregulation.hypothermia-damage", 1.0D));
        this.hypothermiaSevereDamage = Math.max(this.hypothermiaDamage, config.getDouble("thermoregulation.hypothermia-severe-damage", 2.0D));
        this.hypothermiaSevereThresholdCelsius = config.getDouble("thermoregulation.hypothermia-severe-threshold-celsius", -10.0D);
        this.hypothermiaDamageAfterChecks = Math.max(1, config.getInt("thermoregulation.hypothermia-damage-after-checks", 2));
        this.winterSnowColumnsPerCheck = Math.clamp(config.getInt("seasons.WINTER.snow-accumulation-columns-per-check", 8), 0, 24);
        this.heatstrokeThresholdCelsius = config.getDouble("thermoregulation.heatstroke-threshold-celsius", 38.0D);
        this.heatstrokeExhaustionAdded = (float) config.getDouble("thermoregulation.heatstroke-exhaustion-added", 0.35D);

        // Pré-chargement EnumMap Armures (O(1))
        this.armorBonusMap.clear();
        double leatherBonus = config.getDouble("thermoregulation.armor-bonus.leather-per-piece", 2.5D);
        double chainBonus = config.getDouble("thermoregulation.armor-bonus.chainmail-per-piece", -0.5D);
        double ironBonus = config.getDouble("thermoregulation.armor-bonus.iron-per-piece", -1.0D);
        double goldBonus = config.getDouble("thermoregulation.armor-bonus.gold-per-piece", -0.5D);
        double diamondBonus = config.getDouble("thermoregulation.armor-bonus.diamond-per-piece", -1.0D);
        double netheriteBonus = config.getDouble("thermoregulation.armor-bonus.netherite-per-piece", 1.5D);
        this.fireProtectionCoolingPerLevel = Math.max(0.0D, config.getDouble("thermoregulation.armor-bonus.fire-protection-cooling-per-level", 0.75D));
        this.insulatedMetalColdReductionFactor = Math.clamp(config.getDouble("thermoregulation.armor-bonus.insulated-metal-cold-reduction-factor", 0.50D), 0.0D, 1.0D);

        registerArmorTier(leatherBonus, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS);
        registerArmorTier(chainBonus, Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS);
        registerArmorTier(ironBonus, Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS);
        registerArmorTier(goldBonus, Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS);
        registerArmorTier(diamondBonus, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);
        registerArmorTier(netheriteBonus, Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS);

        // Pré-chargement EnumMap Objets tenus en main (O(1))
        this.heldItemHeatMap.clear();
        ConfigurationSection heldSection = config.getConfigurationSection("thermoregulation.held-item-heat");
        if (heldSection != null) {
            for (String key : heldSection.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat != null) {
                    this.heldItemHeatMap.put(mat, heldSection.getDouble(key, 0.0D));
                } else if (logger != null) {
                    logger.warning("[PaperSeasons] Material inconnu dans held-item-heat : " + key);
                }
            }
        }

        // UI ActionBar
        this.actionbarEnabled = config.getBoolean("ui.actionbar-enabled", true);
        this.temperatureDeltaThreshold = Math.max(0.1D, config.getDouble("ui.temperature-delta-threshold", 0.5D));
        this.formatNormal = config.getString("ui.format-normal", "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <aqua>🌡️ <temp>°C<badges></aqua>");
        this.formatFreezing = config.getString("ui.format-freezing", "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <aqua>🥶 <bold><temp>°C (Gel !)<badges></bold></aqua>");
        this.formatOverheating = config.getString("ui.format-overheating", "<dark_gray>[</dark_gray><season><dark_gray>] <gray>｜ <gold>🔥 <bold><temp>°C (Canicule !)<badges></bold></gold>");

        // Packet spoofing
        this.packetBiomeSpoofingEnabled = config.getBoolean("packet-biome-spoofing.enabled", true);
        this.preserveNetherAndEnd = config.getBoolean("packet-biome-spoofing.preserve-nether-and-end", true);
        this.preserveOceanAndCaveBiomes = config.getBoolean("packet-biome-spoofing.preserve-ocean-and-cave-biomes", true);
        this.minSurfaceY = config.getInt("packet-biome-spoofing.min-surface-y", 48);
    }

    /**
     * Pré-charge le calendrier biologique par espèce dans une EnumMap<Season, EnumMap<Material, Double>> (O(1)).
     * Valeur > 0.0 : probabilité de boost (+1 stade Ageable).
     * Valeur < 0.0 : probabilité de ralentissement (annulation de BlockGrowEvent sauf sous serre).
     */
    private void initBiologicalCropCalendar() {
        this.seasonalCropMultipliers.clear();
        for (Season s : Season.values()) {
            this.seasonalCropMultipliers.put(s, new EnumMap<>(Material.class));
        }

        EnumMap<Material, Double> spring = this.seasonalCropMultipliers.get(Season.SPRING);
        registerCrops(spring, 0.35D,
                Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
                Material.SWEET_BERRY_BUSH, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);
        for (Material mat : Material.values()) {
            if (mat.name().endsWith("_SAPLING") || mat == Material.MANGROVE_PROPAGULE) {
                spring.put(mat, 0.35D);
            }
        }

        EnumMap<Material, Double> summer = this.seasonalCropMultipliers.get(Season.SUMMER);
        registerCrops(summer, 0.40D,
                Material.MELON_STEM, Material.ATTACHED_MELON_STEM, Material.SUGAR_CANE,
                Material.COCOA, Material.BAMBOO, Material.BAMBOO_SAPLING, Material.CACTUS);
        registerCrops(summer, 0.20D, Material.WHEAT, Material.TORCHFLOWER_CROP);
        // Les cultures de climat frais ralentissent sous la chaleur d'été (sauf sous abri/serre)
        registerCrops(summer, -0.35D,
                Material.POTATOES, Material.BEETROOTS, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM);

        EnumMap<Material, Double> autumn = this.seasonalCropMultipliers.get(Season.AUTUMN);
        registerCrops(autumn, 0.40D,
                Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM,
                Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
                Material.CARROTS, Material.POTATOES, Material.BEETROOTS);
        // Les cultures tropicales et céréales d'été ralentissent de 50% en automne (sauf sous serre)
        registerCrops(autumn, -0.50D,
                Material.MELON_STEM, Material.SUGAR_CANE, Material.CACTUS,
                Material.BAMBOO, Material.COCOA, Material.WHEAT);
    }

    private void registerCrops(EnumMap<Material, Double> map, double multiplier, Material... materials) {
        for (Material m : materials) {
            if (m != null) {
                map.put(m, multiplier);
            }
        }
    }

    private void registerArmorTier(double bonus, Material... materials) {
        for (Material mat : materials) {
            if (mat != null) {
                this.armorBonusMap.put(mat, bonus);
            }
        }
    }

    public boolean isWorldEnabled(String worldName) {
        return this.enabledWorlds.contains(worldName);
    }

    public Set<String> getEnabledWorlds() {
        return Collections.unmodifiableSet(this.enabledWorlds);
    }

    public SeasonSettings getSeasonSettings(Season season) {
        return this.seasonSettings.get(season);
    }

    public SubSeasonSettings getSubSeasonSettings(SubSeason subSeason) {
        return this.subSeasonSettings.get(subSeason);
    }

    public Double getCropMultiplier(Season season, Material cropType) {
        if (season == null || cropType == null) {
            return null;
        }
        EnumMap<Material, Double> map = this.seasonalCropMultipliers.get(season);
        return map != null ? map.get(cropType) : null;
    }

    public double getArmorHeatBonus(Material material) {
        return material == null ? 0.0D : this.armorBonusMap.getOrDefault(material, 0.0D);
    }

    public double getHeldItemHeat(Material material) {
        return material == null ? 0.0D : this.heldItemHeatMap.getOrDefault(material, 0.0D);
    }

    public Map<Material, Double> getArmorBonusMapView() {
        return Collections.unmodifiableMap(this.armorBonusMap);
    }

    public Map<Material, Double> getHeldItemHeatMapView() {
        return Collections.unmodifiableMap(this.heldItemHeatMap);
    }

    public int getDaysPerSeason() {
        return this.daysPerSeason;
    }

    public long getDaylightCycleCheckIntervalTicks() {
        return this.daylightCycleCheckIntervalTicks;
    }

    public boolean isEventsEnabled() {
        return this.eventsEnabled;
    }

    public double getDailyEventTriggerChance() {
        return this.dailyEventTriggerChance;
    }

    public double getBlizzardExtraCold() {
        return this.blizzardExtraCold;
    }

    public double getHeatwaveExtraHeat() {
        return this.heatwaveExtraHeat;
    }

    public double getAutumnMistFishingMultiplier() {
        return this.autumnMistFishingMultiplier;
    }

    public double getSpringBloomMultiplier() {
        return this.springBloomMultiplier;
    }

    public double getFireProtectionCoolingPerLevel() {
        return this.fireProtectionCoolingPerLevel;
    }

    public double getInsulatedMetalColdReductionFactor() {
        return this.insulatedMetalColdReductionFactor;
    }

    public boolean isThermoregulationEnabled() {
        return this.thermoregulationEnabled;
    }

    public long getCheckIntervalTicks() {
        return this.checkIntervalTicks;
    }

    public double getBiomeBaseTempMultiplier() {
        return this.biomeBaseTempMultiplier;
    }

    public int getAltitudeSeaLevel() {
        return this.altitudeSeaLevel;
    }

    public double getAltitudeFactorPerBlock() {
        return this.altitudeFactorPerBlock;
    }

    public double getBlockLightHeatMultiplier() {
        return this.blockLightHeatMultiplier;
    }

    public double getDiurnalShelteredAttenuation() {
        return this.diurnalShelteredAttenuation;
    }

    public int getIndoorSkyLightThreshold() {
        return this.indoorSkyLightThreshold;
    }

    public double getIndoorSeasonMitigationFactor() {
        return this.indoorSeasonMitigationFactor;
    }

    public int getWetnessRainGainNoHelmet() {
        return this.wetnessRainGainNoHelmet;
    }

    public int getWetnessRainGainWithHelmet() {
        return this.wetnessRainGainWithHelmet;
    }

    public int getWetnessPassiveDryRate() {
        return this.wetnessPassiveDryRate;
    }

    public int getWetnessHeatedDryRate() {
        return this.wetnessHeatedDryRate;
    }

    public int getWetnessHeatedDryMinBlockLight() {
        return this.wetnessHeatedDryMinBlockLight;
    }

    public double getMaxWetColdPenalty() {
        return this.maxWetColdPenalty;
    }

    public double getSummerCoolingThresholdCelsius() {
        return this.summerCoolingThresholdCelsius;
    }

    public double getMaxSummerWetRelief() {
        return this.maxSummerWetRelief;
    }

    public int getWindChillMinAltitudeY() {
        return this.windChillMinAltitudeY;
    }

    public int getWindChillMinSkyLight() {
        return this.windChillMinSkyLight;
    }

    public double getWindChillFactorPerBlock() {
        return this.windChillFactorPerBlock;
    }

    public double getMaxBaseWindChill() {
        return this.maxBaseWindChill;
    }

    public double getWindChillStormMultiplier() {
        return this.windChillStormMultiplier;
    }

    public double getBreweryWinterWarmthBonus() {
        return this.breweryWinterWarmthBonus;
    }

    public double getCoolingBuffCelsius() {
        return this.coolingBuffCelsius;
    }

    public long getCoolingBuffDurationMs() {
        return this.coolingBuffDurationMs;
    }

    public double getCoolingMinAmbientTemp() {
        return this.coolingMinAmbientTemp;
    }

    public double getWarmingBuffCelsius() {
        return this.warmingBuffCelsius;
    }

    public long getWarmingBuffDurationMs() {
        return this.warmingBuffDurationMs;
    }

    public double getFreezeThresholdCelsius() {
        return this.freezeThresholdCelsius;
    }

    public int getFreezeTicksAddedPerCheck() {
        return this.freezeTicksAddedPerCheck;
    }

    public double getHypothermiaDamage() {
        return this.hypothermiaDamage;
    }

    public double getHypothermiaSevereDamage() {
        return this.hypothermiaSevereDamage;
    }

    public double getHypothermiaSevereThresholdCelsius() {
        return this.hypothermiaSevereThresholdCelsius;
    }

    public int getHypothermiaDamageAfterChecks() {
        return this.hypothermiaDamageAfterChecks;
    }

    public int getWinterSnowColumnsPerCheck() {
        return this.winterSnowColumnsPerCheck;
    }

    public double getHeatstrokeThresholdCelsius() {
        return this.heatstrokeThresholdCelsius;
    }

    public float getHeatstrokeExhaustionAdded() {
        return this.heatstrokeExhaustionAdded;
    }

    public boolean isActionbarEnabled() {
        return this.actionbarEnabled;
    }

    public double getTemperatureDeltaThreshold() {
        return this.temperatureDeltaThreshold;
    }

    public String getFormatNormal() {
        return this.formatNormal;
    }

    public String getFormatFreezing() {
        return this.formatFreezing;
    }

    public String getFormatOverheating() {
        return this.formatOverheating;
    }

    public boolean isPacketBiomeSpoofingEnabled() {
        return this.packetBiomeSpoofingEnabled;
    }

    public boolean isPreserveNetherAndEnd() {
        return this.preserveNetherAndEnd;
    }

    public boolean isPreserveOceanAndCaveBiomes() {
        return this.preserveOceanAndCaveBiomes;
    }

    public int getMinSurfaceY() {
        return this.minSurfaceY;
    }
}
