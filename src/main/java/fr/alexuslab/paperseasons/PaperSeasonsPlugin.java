package fr.alexuslab.paperseasons;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point d'entrée principal du plugin PaperSeasons.
 * Architecture hybride optimisée pour Raspberry Pi 5 (ARM64) + Playit.gg.
 */
public final class PaperSeasonsPlugin extends JavaPlugin implements Listener {

    private SeasonConfigCache configCache;
    private SeasonManager seasonManager;
    private TemperatureTask temperatureTask;
    private SeasonEnvironmentListener environmentListener;
    private BiomePacketSpoofer biomePacketSpoofer;
    private SeasonCalendarGui calendarGui;
    private SeasonCraftingManager craftingManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 1. Initialisation du cache O(1) en mémoire (EnumMap Material -> Double)
        this.configCache = new SeasonConfigCache();
        this.configCache.reload(getConfig(), getLogger());

        // 2. Initialisation du calendrier et du contrôleur DO_DAYLIGHT_CYCLE (Zéro setFullTime)
        this.seasonManager = new SeasonManager(this, this.configCache);
        this.seasonManager.initialize();

        // 3. Démarrage de la tâche de thermorégulation O(1) (toutes les 40 ticks)
        this.temperatureTask = new TemperatureTask(this, this.configCache, this.seasonManager);
        this.temperatureTask.start();

        // 4. Listeners agricoles, serres en verre, gel/fonte et météo saisonnière (O(1))
        this.environmentListener = new SeasonEnvironmentListener(this, this.configCache, this.seasonManager);
        this.environmentListener.startWinterStormTask();

        // 5. Spoofing visuel 3D autonome (Netty ChannelDuplexHandler + ChunkBiomeData, Y >= 48)
        this.biomePacketSpoofer = new BiomePacketSpoofer(this, this.configCache, this.seasonManager);
        this.biomePacketSpoofer.initialize();

        // 5b. Almanach interactif GUI 27 slots (/season calendar)
        this.calendarGui = new SeasonCalendarGui(this);

        // 5c. Artisanat thermique v1.3.0 (doublures d'armure + Soupe Forestière Épicée)
        this.craftingManager = new SeasonCraftingManager(this);
        this.craftingManager.registerRecipes();

        // 6. Enregistrement des événements Paper
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(this.environmentListener, this);
        pm.registerEvents(this.biomePacketSpoofer, this);
        pm.registerEvents(this.calendarGui, this);
        pm.registerEvents(this.craftingManager, this);

        // 7. Enregistrement natif de la commande /season dans la CommandMap Paper
        Bukkit.getCommandMap().register("paperseasons", new SeasonCommand(this));

        getLogger().info("[PaperSeasons] v1.3.0 Activé avec succès (Saison : "
                + this.seasonManager.getCurrentSeason().name()
                + " [" + this.seasonManager.getCurrentSubSeason().name() + "], Jour "
                + this.seasonManager.getCurrentDayInSeason()
                + "/" + this.configCache.getDaysPerSeason() + ").");
    }

    @Override
    public void onDisable() {
        if (this.craftingManager != null) {
            this.craftingManager.unregisterRecipes();
        }
        if (this.environmentListener != null) {
            this.environmentListener.stopWinterStormTask();
        }
        if (this.biomePacketSpoofer != null) {
            this.biomePacketSpoofer.shutdown();
        }
        if (this.temperatureTask != null) {
            this.temperatureTask.stop();
        }
        if (this.seasonManager != null) {
            // Restaure GameRule.DO_DAYLIGHT_CYCLE à true et sauvegarde data.yml de manière synchrone
            this.seasonManager.shutdown();
        }
        getLogger().info("[PaperSeasons] Désactivé proprement (DO_DAYLIGHT_CYCLE restauré et data.yml sauvegardé).");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        this.configCache.reload(getConfig(), getLogger());
        this.seasonManager.startAstronomyTask();
        this.temperatureTask.invalidateAllActionBars();
        this.temperatureTask.start();
        if (this.environmentListener != null) {
            this.environmentListener.startWinterStormTask();
        }
        if (this.biomePacketSpoofer != null) {
            this.biomePacketSpoofer.initialize();
            this.biomePacketSpoofer.refreshChunksForAllPlayers();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (this.temperatureTask != null) {
            this.temperatureTask.removePlayerCache(event.getPlayer().getUniqueId());
        }
    }

    public SeasonConfigCache getConfigCache() {
        return this.configCache;
    }

    public SeasonManager getSeasonManager() {
        return this.seasonManager;
    }

    public TemperatureTask getTemperatureTask() {
        return this.temperatureTask;
    }

    public SeasonEnvironmentListener getEnvironmentListener() {
        return this.environmentListener;
    }

    public BiomePacketSpoofer getBiomePacketSpoofer() {
        return this.biomePacketSpoofer;
    }

    public SeasonCraftingManager getCraftingManager() {
        return this.craftingManager;
    }

    public SeasonCalendarGui getCalendarGui() {
        return this.calendarGui;
    }
}
