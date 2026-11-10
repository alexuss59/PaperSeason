package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonConfigCache.SubSeasonSettings;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Gestionnaire du calendrier saisonnier (4 Saisons / 12 Sous-Saisons) et du cycle astronomique.
 *
 * Garanties d'architecture v1.1 (Pi 5 + Playit.gg + BreweryX) :
 * - 12 Sous-Saisons progressives (EARLY, MID, LATE) calculées dynamiquement.
 * - Progression continue [0.0 .. 1.0] pour l'interpolation thermique cosinus sans saut brutal.
 * - Zéro appel à world.setFullTime() (préserve BreweryX et la fluidité client Playit.gg).
 */
@SuppressWarnings("removal")
public final class SeasonManager {

    public enum Season {
        SPRING,
        SUMMER,
        AUTUMN,
        WINTER;

        private static final Season[] VALUES = values();

        public Season next() {
            return VALUES[(this.ordinal() + 1) % VALUES.length];
        }
    }

    public enum SubSeason {
        SPRING_EARLY(Season.SPRING, 0, "EARLY", "paperseasons:spring_early"),
        SPRING_MID(Season.SPRING, 1, "MID", "paperseasons:spring_mid"),
        SPRING_LATE(Season.SPRING, 2, "LATE", "paperseasons:spring_late"),

        SUMMER_EARLY(Season.SUMMER, 0, "EARLY", "paperseasons:summer_early"),
        SUMMER_MID(Season.SUMMER, 1, "MID", "paperseasons:summer_mid"),
        SUMMER_LATE(Season.SUMMER, 2, "LATE", "paperseasons:summer_late"),

        AUTUMN_EARLY(Season.AUTUMN, 0, "EARLY", "paperseasons:autumn_early"),
        AUTUMN_MID(Season.AUTUMN, 1, "MID", "paperseasons:autumn_mid"),
        AUTUMN_LATE(Season.AUTUMN, 2, "LATE", "paperseasons:autumn_late"),

        WINTER_EARLY(Season.WINTER, 0, "EARLY", "paperseasons:winter_early"),
        WINTER_MID(Season.WINTER, 1, "MID", "paperseasons:winter_mid"),
        WINTER_LATE(Season.WINTER, 2, "LATE", "paperseasons:winter_late");

        private static final SubSeason[] VALUES = values();

        private final Season parentSeason;
        private final int phaseIndex; // 0 = EARLY, 1 = MID, 2 = LATE
        private final String phaseKey;
        private final String defaultBiomeKey;

        SubSeason(Season parentSeason, int phaseIndex, String phaseKey, String defaultBiomeKey) {
            this.parentSeason = parentSeason;
            this.phaseIndex = phaseIndex;
            this.phaseKey = phaseKey;
            this.defaultBiomeKey = defaultBiomeKey;
        }

        public Season getParentSeason() {
            return this.parentSeason;
        }

        public int getPhaseIndex() {
            return this.phaseIndex;
        }

        public String getPhaseKey() {
            return this.phaseKey;
        }

        public String getDefaultBiomeKey() {
            return this.defaultBiomeKey;
        }

        public SubSeason next() {
            return VALUES[(this.ordinal() + 1) % VALUES.length];
        }

        public static SubSeason from(Season season, int currentDayInSeason, int daysPerSeason) {
            int safeDays = Math.max(1, daysPerSeason);
            int clampedDay = Math.clamp(currentDayInSeason, 1, safeDays);
            double ratio = (clampedDay - 1.0D) / (double) safeDays;
            int phase;
            if (ratio < (1.0D / 3.0D)) {
                phase = 0;
            } else if (ratio < (2.0D / 3.0D)) {
                phase = 1;
            } else {
                phase = 2;
            }
            return VALUES[season.ordinal() * 3 + phase];
        }
    }

    /**
     * Événements climatiques saisonniers rares (v1.2.0).
     */
    public enum SeasonalEvent {
        NONE(null, "<gray>Aucun</gray>", "", ""),
        SPRING_BLOOM(
                Season.SPRING,
                "<gradient:#76FF03:#FF4081><bold>🌸 Floraison Printanière</bold></gradient>",
                "<light_purple>🌸 Floraison</light_purple>",
                "<green>La nature explose : chance de pousse des fleurs/arbres et jumeaux d'élevage doublés !</green>"
        ),
        HEATWAVE(
                Season.SUMMER,
                "<gradient:#FF6D00:#DD2C00><bold>🔥 Vague de Canicule</bold></gradient>",
                "<gold>🔥 Canicule</gold>",
                "<gold>Soleil de plomb : +7.0°C en journée (08h-16h) et séchage 2x plus rapide !</gold>"
        ),
        AUTUMN_MIST(
                Season.AUTUMN,
                "<gradient:#B0BEC5:#78909C><bold>🌫️ Brume d'Automne</bold></gradient>",
                "<gray>🌫️ Brume</gray>",
                "<aqua>Pluie fine mystique : pousse des champignons/baies boostée et pêche accélérée de +40% !</aqua>"
        ),
        BLIZZARD(
                Season.WINTER,
                "<gradient:#00E5FF:#2979FF><bold>🌨️ Blizzard Polaire</bold></gradient>",
                "<aqua>🌨️ Blizzard</aqua>",
                "<aqua>Tempête glaciale : -8.0°C en extérieur, Wind Chill doublé et fortes chutes de neige !</aqua>"
        );

        private final Season associatedSeason;
        private final String displayNameRaw;
        private final String actionbarBadgeRaw;
        private final String descriptionRaw;

        SeasonalEvent(Season associatedSeason, String displayNameRaw, String actionbarBadgeRaw, String descriptionRaw) {
            this.associatedSeason = associatedSeason;
            this.displayNameRaw = displayNameRaw;
            this.actionbarBadgeRaw = actionbarBadgeRaw;
            this.descriptionRaw = descriptionRaw;
        }

        public Season getAssociatedSeason() {
            return this.associatedSeason;
        }

        public String getDisplayNameRaw() {
            return this.displayNameRaw;
        }

        public String getActionbarBadgeRaw() {
            return this.actionbarBadgeRaw;
        }

        public String getDescriptionRaw() {
            return this.descriptionRaw;
        }

        public static SeasonalEvent forSeason(Season season) {
            return switch (season) {
                case SPRING -> SPRING_BLOOM;
                case SUMMER -> HEATWAVE;
                case AUTUMN -> AUTUMN_MIST;
                case WINTER -> BLIZZARD;
            };
        }
    }

    private final PaperSeasonsPlugin plugin;
    private final SeasonConfigCache configCache;
    private final File dataFile;
    private final Object saveLock = new Object();

    private Season currentSeason = Season.SPRING;
    private int currentDayInSeason = 1;
    private int currentYear = 1;
    private SeasonalEvent currentEvent = SeasonalEvent.NONE;

    private final Map<UUID, Long> lastWorldDayTime = new HashMap<>();
    private final Map<UUID, Double> daylightAccumulators = new HashMap<>();

    private BukkitTask astronomyTask;

    public SeasonManager(PaperSeasonsPlugin plugin, SeasonConfigCache configCache) {
        this.plugin = plugin;
        this.configCache = configCache;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
    }

    public void initialize() {
        loadStateSync();
        startAstronomyTask();
    }

    public void startAstronomyTask() {
        stopAstronomyTask();
        long interval = this.configCache.getDaylightCycleCheckIntervalTicks();
        this.astronomyTask = Bukkit.getScheduler().runTaskTimer(
                this.plugin,
                this::tickAstronomy,
                interval,
                interval
        );
    }

    public void stopAstronomyTask() {
        if (this.astronomyTask != null) {
            this.astronomyTask.cancel();
            this.astronomyTask = null;
        }
        restoreDaylightCycleOnAllWorlds();
    }

    public void shutdown() {
        stopAstronomyTask();
        saveStateSync();
    }

    private void tickAstronomy() {
        SeasonSettings settings = this.configCache.getSeasonSettings(this.currentSeason);
        if (settings == null) {
            return;
        }

        boolean calendarUpdatedThisTick = false;

        for (String worldName : this.configCache.getEnabledWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }

            UUID worldId = world.getUID();
            long currentDayTime = world.getTime();

            // 1. Détection du passage d'un nouveau jour via world.getTime()
            Long previousDayTime = this.lastWorldDayTime.put(worldId, currentDayTime);
            if (!calendarUpdatedThisTick && previousDayTime != null && currentDayTime < previousDayTime) {
                if (previousDayTime - currentDayTime > 1000L) {
                    calendarUpdatedThisTick = true;
                    advanceOneDay();
                    settings = this.configCache.getSeasonSettings(this.currentSeason);
                }
            }

            // 2. Contrôle fluide du soleil via GameRule.DO_DAYLIGHT_CYCLE (Zéro setFullTime)
            boolean isDaytime = currentDayTime < 12000L;
            double speedMultiplier = isDaytime ? settings.daySpeedMultiplier() : settings.nightSpeedMultiplier();

            boolean shouldAdvance;
            if (speedMultiplier >= 0.999D) {
                shouldAdvance = true;
                this.daylightAccumulators.put(worldId, 0.0D);
            } else {
                double acc = this.daylightAccumulators.getOrDefault(worldId, 0.0D) + speedMultiplier;
                if (acc >= 1.0D) {
                    shouldAdvance = true;
                    acc -= 1.0D;
                } else {
                    shouldAdvance = false;
                }
                this.daylightAccumulators.put(worldId, acc);
            }

            Boolean currentRule = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
            if (currentRule == null || currentRule != shouldAdvance) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, shouldAdvance);
            }
        }
    }

    private void restoreDaylightCycleOnAllWorlds() {
        for (String worldName : this.configCache.getEnabledWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                Boolean currentRule = world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
                if (currentRule != null && !currentRule) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
                }
            }
        }
    }

    public void advanceOneDay() {
        SubSeason oldSubSeason = getCurrentSubSeason();
        int maxDays = this.configCache.getDaysPerSeason();
        this.currentDayInSeason++;

        if (this.currentDayInSeason > maxDays) {
            this.currentDayInSeason = 1;
            Season nextSeason = this.currentSeason.next();
            if (nextSeason == Season.SPRING) {
                this.currentYear++;
            }
            setSeason(nextSeason, false);
            rollDailySeasonalEvent();
        } else {
            SubSeason newSubSeason = getCurrentSubSeason();
            broadcastDayTransition(oldSubSeason != newSubSeason);
            rollDailySeasonalEvent();
            if (oldSubSeason != newSubSeason) {
                if (this.plugin.getTemperatureTask() != null) {
                    this.plugin.getTemperatureTask().invalidateAllActionBars();
                }
                if (this.plugin.getBiomePacketSpoofer() != null) {
                    this.plugin.getBiomePacketSpoofer().refreshChunksForAllPlayers();
                }
            }
            saveStateAsync();
        }
    }

    private void rollDailySeasonalEvent() {
        if (!this.configCache.isEventsEnabled()) {
            setSeasonalEvent(SeasonalEvent.NONE, false);
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < this.configCache.getDailyEventTriggerChance()) {
            setSeasonalEvent(SeasonalEvent.forSeason(this.currentSeason), true);
        } else {
            setSeasonalEvent(SeasonalEvent.NONE, false);
        }
    }

    public void setSeasonalEvent(SeasonalEvent event, boolean announce) {
        this.currentEvent = (event != null) ? event : SeasonalEvent.NONE;

        for (String worldName : this.configCache.getEnabledWorlds()) {
            World world = Bukkit.getWorld(worldName);
            if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            switch (this.currentEvent) {
                case BLIZZARD -> {
                    world.setStorm(true);
                    world.setThundering(true);
                    world.setWeatherDuration(18000);
                }
                case HEATWAVE -> {
                    world.setStorm(false);
                    world.setThundering(false);
                    world.setClearWeatherDuration(18000);
                }
                case AUTUMN_MIST -> {
                    world.setStorm(true);
                    world.setThundering(false);
                    world.setWeatherDuration(14000);
                }
                default -> {
                }
            }
        }

        if (announce && this.currentEvent != SeasonalEvent.NONE) {
            Component msg = MiniMessage.miniMessage().deserialize(
                    "<dark_gray>[<gradient:#76FF03:#00E5FF>PaperSeasons</gradient>]</dark_gray> "
                            + "<white>Événement Climatique :</white> " + this.currentEvent.getDisplayNameRaw()
                            + "\n<dark_gray>»</dark_gray> " + this.currentEvent.getDescriptionRaw()
            );
            ((Audience) Bukkit.getServer()).sendMessage(msg);

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (this.currentEvent == SeasonalEvent.BLIZZARD) {
                    p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7F, 0.55F);
                } else if (this.currentEvent == SeasonalEvent.HEATWAVE) {
                    p.playSound(p.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.9F, 0.85F);
                } else if (this.currentEvent == SeasonalEvent.SPRING_BLOOM) {
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
                } else if (this.currentEvent == SeasonalEvent.AUTUMN_MIST) {
                    p.playSound(p.getLocation(), Sound.WEATHER_RAIN_ABOVE, 0.8F, 0.75F);
                }
            }
        }

        if (this.plugin.getTemperatureTask() != null) {
            this.plugin.getTemperatureTask().invalidateAllActionBars();
        }
        saveStateAsync();
    }

    public SeasonalEvent getCurrentEvent() {
        return this.currentEvent;
    }

    public void setSeason(Season newSeason, boolean resetDayCounter) {
        this.currentSeason = newSeason;
        if (resetDayCounter) {
            this.currentDayInSeason = 1;
        }
        if (this.currentEvent != SeasonalEvent.NONE && this.currentEvent.getAssociatedSeason() != newSeason) {
            this.currentEvent = SeasonalEvent.NONE;
        }
        broadcastSeasonChange();
        if (this.plugin.getTemperatureTask() != null) {
            this.plugin.getTemperatureTask().invalidateAllActionBars();
        }
        if (this.plugin.getBiomePacketSpoofer() != null) {
            this.plugin.getBiomePacketSpoofer().refreshChunksForAllPlayers();
        }
        saveStateAsync();
    }

    public void setSubSeason(SubSeason targetSubSeason) {
        this.currentSeason = targetSubSeason.getParentSeason();
        int daysPerSeason = this.configCache.getDaysPerSeason();
        int phase = targetSubSeason.getPhaseIndex();
        // Positionne le jour au début de la sous-saison demandée
        this.currentDayInSeason = Math.clamp((int) Math.ceil((phase * daysPerSeason) / 3.0D) + 1, 1, daysPerSeason);
        broadcastSeasonChange();
        if (this.plugin.getTemperatureTask() != null) {
            this.plugin.getTemperatureTask().invalidateAllActionBars();
        }
        if (this.plugin.getBiomePacketSpoofer() != null) {
            this.plugin.getBiomePacketSpoofer().refreshChunksForAllPlayers();
        }
        saveStateAsync();
    }

    public void nextSeason() {
        Season next = this.currentSeason.next();
        if (next == Season.SPRING) {
            this.currentYear++;
        }
        setSeason(next, true);
    }

    public SubSeason getCurrentSubSeason() {
        return SubSeason.from(this.currentSeason, this.currentDayInSeason, this.configCache.getDaysPerSeason());
    }

    /**
     * Retourne la progression continue [0.0 .. 1.0] à l'intérieur de la sous-saison actuelle
     * en tenant compte du jour et de l'heure du monde (worldTime 0..23999).
     */
    public double getSubSeasonProgress(long worldTime) {
        int daysPerSeason = Math.max(1, this.configCache.getDaysPerSeason());
        double dayFraction = Math.clamp(worldTime / 24000.0D, 0.0D, 1.0D);
        double exactSeasonProgress = ((this.currentDayInSeason - 1.0D) + dayFraction) / (double) daysPerSeason;
        double scaled = exactSeasonProgress * 3.0D; // 3 sous-saisons par saison
        int phaseIndex = getCurrentSubSeason().getPhaseIndex();
        return Math.clamp(scaled - phaseIndex, 0.0D, 1.0D);
    }

    private void broadcastSeasonChange() {
        SubSeason sub = getCurrentSubSeason();
        SubSeasonSettings subSettings = this.configCache.getSubSeasonSettings(sub);
        String label = (subSettings != null) ? subSettings.displayNameRaw() : sub.name();

        Component msg = MiniMessage.miniMessage().deserialize(
                "<dark_gray>[<gradient:#76FF03:#00E5FF>PaperSeasons</gradient>]</dark_gray> "
                        + "<gray>Transition saisonnière : place à </gray>"
                        + label
                        + " <dark_gray>(Année " + this.currentYear + " - Jour " + this.currentDayInSeason
                        + "/" + this.configCache.getDaysPerSeason() + ")</dark_gray>"
        );
        ((Audience) Bukkit.getServer()).sendMessage(msg);
    }

    private void broadcastDayTransition(boolean subSeasonChanged) {
        SubSeason sub = getCurrentSubSeason();
        SubSeasonSettings subSettings = this.configCache.getSubSeasonSettings(sub);
        String label = (subSettings != null) ? subSettings.displayNameRaw() : sub.name();

        String prefix = subSeasonChanged
                ? "<gray>Nouvelle phase saisonnière : </gray>"
                : "<gray>Jour <white>" + this.currentDayInSeason + "</white>/" + this.configCache.getDaysPerSeason() + " — </gray>";

        Component msg = MiniMessage.miniMessage().deserialize(
                "<dark_gray>[</dark_gray>" + label + "<dark_gray>]</dark_gray> "
                        + prefix + "<dark_gray>(Année " + this.currentYear + ")</dark_gray>"
        );
        ((Audience) Bukkit.getServer()).sendMessage(msg);
    }

    public void loadStateSync() {
        if (!this.dataFile.exists()) {
            saveStateAsync();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.dataFile);
        String seasonName = yaml.getString("current-season", Season.SPRING.name());
        try {
            this.currentSeason = Season.valueOf(seasonName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            this.currentSeason = Season.SPRING;
        }
        this.currentDayInSeason = Math.max(1, yaml.getInt("current-day-in-season", 1));
        this.currentYear = Math.max(1, yaml.getInt("current-year", 1));
        String eventName = yaml.getString("current-event", SeasonalEvent.NONE.name());
        try {
            this.currentEvent = SeasonalEvent.valueOf(eventName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            this.currentEvent = SeasonalEvent.NONE;
        }
    }

    public void saveStateAsync() {
        final Season snapSeason = this.currentSeason;
        final int snapDay = this.currentDayInSeason;
        final int snapYear = this.currentYear;
        final SeasonalEvent snapEvent = this.currentEvent;

        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> writeYamlToDisk(snapSeason, snapDay, snapYear, snapEvent));
    }

    public void saveStateSync() {
        writeYamlToDisk(this.currentSeason, this.currentDayInSeason, this.currentYear, this.currentEvent);
    }

    private void writeYamlToDisk(Season season, int day, int year, SeasonalEvent event) {
        synchronized (this.saveLock) {
            try {
                File parent = this.dataFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("current-season", season.name());
                yaml.set("current-day-in-season", day);
                yaml.set("current-year", year);
                yaml.set("current-event", event != null ? event.name() : SeasonalEvent.NONE.name());
                yaml.save(this.dataFile);
            } catch (IOException e) {
                this.plugin.getLogger().log(Level.WARNING, "[PaperSeasons] Impossible de sauvegarder data.yml", e);
            }
        }
    }

    public Season getCurrentSeason() {
        return this.currentSeason;
    }

    public int getCurrentDayInSeason() {
        return this.currentDayInSeason;
    }

    public int getCurrentYear() {
        return this.currentYear;
    }
}
