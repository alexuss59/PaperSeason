package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonConfigCache.SubSeasonSettings;
import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SeasonalEvent;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import fr.alexuslab.paperseasons.TemperatureTask.ThermalBreakdown;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interface graphique interactive (27 slots) de l'Almanach des Saisons (/season calendar ou /season).
 * Utilise un InventoryHolder dédié pour bloquer toute interaction ou prise d'item en O(1).
 */
public final class SeasonCalendarGui implements Listener {

    private static final String GUI_TITLE = "<gradient:#76FF03:#00E5FF><bold>📅 Almanach des Saisons</bold></gradient>";
    private static final int SLOT_CLOCK = 4;
    private static final int SLOT_SPRING = 10;
    private static final int SLOT_SUMMER = 12;
    private static final int SLOT_AUTUMN = 14;
    private static final int SLOT_WINTER = 16;
    private static final int SLOT_THERMAL = 22;
    private static final int SLOT_CRAFTING_FORAGING = 24;

    public static final class AlmanacHolder implements InventoryHolder {
        private Inventory inventory;

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }

    private final PaperSeasonsPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SeasonCalendarGui(PaperSeasonsPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        AlmanacHolder holder = new AlmanacHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, this.mm.deserialize(GUI_TITLE));
        holder.setInventory(inv);

        populateInventory(player, inv);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.85F, 1.1F);
    }

    private void populateInventory(Player player, Inventory inv) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        SeasonManager sm = this.plugin.getSeasonManager();
        SeasonConfigCache cache = this.plugin.getConfigCache();
        Season currentSeason = sm.getCurrentSeason();
        SubSeason currentSub = sm.getCurrentSubSeason();
        SeasonalEvent currentEvent = sm.getCurrentEvent();
        long worldTime = player.getWorld().getTime();
        int subPct = (int) Math.round(sm.getSubSeasonProgress(worldTime) * 100.0D);

        // 1. Slot 4 : Horloge Astronomique & Météo
        SubSeasonSettings activeSubSettings = cache.getSubSeasonSettings(currentSub);
        String subLabel = (activeSubSettings != null) ? activeSubSettings.displayNameRaw() : currentSub.name();
        String eventLabel = (currentEvent != SeasonalEvent.NONE)
                ? currentEvent.getDisplayNameRaw()
                : "<dark_gray>Aucun (Climat normal)</dark_gray>";

        List<String> clockLore = new ArrayList<>();
        clockLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");
        clockLore.add("<gray>• Année :</gray> <white>Année " + sm.getCurrentYear() + "</white>");
        clockLore.add("<gray>• Jour actuel :</gray> <aqua>Jour " + sm.getCurrentDayInSeason() + "/" + cache.getDaysPerSeason() + "</aqua>");
        clockLore.add("<gray>• Heure solaire :</gray> <yellow>" + formatClock(worldTime) + "</yellow>");
        clockLore.add("<gray>• Phase active :</gray> " + subLabel + " <dark_gray>(" + subPct + "%)</dark_gray>");
        clockLore.add("<gray>• Événement météo :</gray> " + eventLabel);
        if (currentEvent != SeasonalEvent.NONE) {
            clockLore.add("  <dark_gray>»</dark_gray> " + currentEvent.getDescriptionRaw());
        }
        clockLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");

        inv.setItem(SLOT_CLOCK, createItem(
                Material.CLOCK,
                "<gradient:#FFD600:#FFAB00><bold>⏳ Horloge Astronomique</bold></gradient>",
                clockLore,
                true
        ));

        // 2. Ligne centrale : Les 4 Saisons (Slots 10, 12, 14, 16)
        inv.setItem(SLOT_SPRING, buildSeasonIcon(
                Season.SPRING,
                Material.CHERRY_SAPLING,
                currentSeason == Season.SPRING,
                currentSub,
                List.of(
                        "<green>✔ +35% Blé, Carottes, Pommes de terre</green>",
                        "<green>✔ +35% Betteraves, Baies, Pousses d'arbres</green>"
                ),
                List.of(
                        "<light_purple>🐣 Élevage : 25% de naissances gémellaires & XP x2</light_purple>",
                        "<aqua>🎣 Pêche : Morsures +25% plus rapides</aqua>",
                        "<yellow>🌸 Événement rare : Floraison Printanière (x2)</yellow>"
                )
        ));

        inv.setItem(SLOT_SUMMER, buildSeasonIcon(
                Season.SUMMER,
                Material.SUNFLOWER,
                currentSeason == Season.SUMMER,
                currentSub,
                List.of(
                        "<green>✔ +40% Pastèques, Canne à sucre, Cacao</green>",
                        "<green>✔ +40% Bambou, Cactus ｜ +20% Blé</green>",
                        "<red>✘ -35% Pommes de terre, Betteraves, Champignons</red>"
                ),
                List.of(
                        "<gold>☀️ Ensoleillement : Journées +35% plus longues</gold>",
                        "<aqua>💧 Baignade (>32°C) : Soulagement thermique -6°C</aqua>",
                        "<gold>🔥 Événement rare : Vague de Canicule (+7°C)</gold>"
                )
        ));

        inv.setItem(SLOT_AUTUMN, buildSeasonIcon(
                Season.AUTUMN,
                Material.CARVED_PUMPKIN,
                currentSeason == Season.AUTUMN,
                currentSub,
                List.of(
                        "<green>✔ +40% Citrouilles, Champignons, Baies sucrées</green>",
                        "<green>✔ +40% Carottes, Pommes de terre, Betteraves</green>",
                        "<red>✘ -50% Pastèques, Canne à sucre, Bambou, Blé</red>"
                ),
                List.of(
                        "<aqua>🎣 Pêche : Morsures +25% plus rapides</aqua>",
                        "<gold>🍲 Soupes & Alcools : Buff réchauffant +4°C (90s)</gold>",
                        "<gray>🌫️ Événement rare : Brume d'Automne (Pêche +40%)</gray>"
                )
        ));

        inv.setItem(SLOT_WINTER, buildSeasonIcon(
                Season.WINTER,
                Material.BLUE_ICE,
                currentSeason == Season.WINTER,
                currentSub,
                List.of(
                        "<red>✘ Gel total des cultures en plein air</red>",
                        "<aqua>🏡 Serres en Verre (Y+1..12) + Chauffage (Light >= 12)</aqua>"
                ),
                List.of(
                        "<blue>🌙 Nuits polaires : Nuits +35% plus longues</blue>",
                        "<red>🥶 Élevage : 50% d'échec dehors (Grange requise)</red>",
                        "<gray>🎣 Pêche : Temps d'attente rallongé (+35%)</gray>",
                        "<aqua>🌨️ Événement rare : Blizzard Polaire (-8°C)</aqua>"
                )
        ));

        // 3. Slot 22 : Bilan Thermique en direct du joueur
        SeasonSettings settings = cache.getSeasonSettings(currentSeason);
        TemperatureTask tempTask = this.plugin.getTemperatureTask();
        if (settings != null && tempTask != null) {
            ThermalBreakdown b = tempTask.computeThermalBreakdownO1(
                    player, player.getWorld(), currentSeason, currentSub, settings
            );
            Block block = player.getLocation().getBlock();
            int skyLight = block.getLightFromSky();
            int blockLight = block.getLightFromBlocks();
            int freezeTicks = tempTask.getVirtualFreezeTicks(player.getUniqueId());
            int freezePct = (int) Math.min(100L, Math.round((freezeTicks / 140.0D) * 100.0D));

            List<String> thermalLore = new ArrayList<>();
            thermalLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");
            thermalLore.add("<gray>• Température corporelle :</gray> <aqua><bold>"
                    + String.format(Locale.US, "%+.1f°C", b.totalTemperature()) + "</bold></aqua>");
            thermalLore.add("<gray>• Saison & Cycle Solaire :</gray> <white>"
                    + String.format(Locale.US, "%+.1f°C", b.effectiveSeasonAndWeatherMod())
                    + " ｜ Jour/Nuit " + String.format(Locale.US, "%+.1f°C", b.diurnalOffset()) + "</white>");
            if (Math.abs(b.seasonalEventOffset()) > 0.05D) {
                thermalLore.add("<gray>• Événement Climatique :</gray> <gold>"
                        + String.format(Locale.US, "%+.1f°C", b.seasonalEventOffset()) + "</gold>");
            }
            thermalLore.add("<gray>• Isolation Abri (Sky=" + skyLight + ") :</gray> "
                    + (b.sheltered() ? "<green>Sous abri (-70% climat)</green>" : "<yellow>Exposé au ciel</yellow>"));
            thermalLore.add("<gray>• Chaleur Blocs (Light=" + blockLight + ") :</gray> <gold>"
                    + String.format(Locale.US, "%+.1f°C", b.blockLightHeat())
                    + (b.heldItemHeat() > 0 ? " (Main: +" + b.heldItemHeat() + "°C)" : "") + "</gold>");
            thermalLore.add("<gray>• Humidité & Vent :</gray> <blue>💧 " + b.wetnessPercent() + "% ("
                    + String.format(Locale.US, "%+.1f°C", b.wetnessThermalOffset()) + ")</blue> <dark_gray>｜</dark_gray> <white>🌬️ "
                    + String.format(Locale.US, "%+.1f°C", b.windChillOffset()) + "</white>");
            thermalLore.add("<gray>• Niveau de Gel :</gray> <aqua>❄️ " + freezePct + "% (" + freezeTicks + "/140 ticks)</aqua>");
            thermalLore.add("<gray>• Bonus Armure :</gray> <white>"
                    + String.format(Locale.US, "%+.1f°C", b.armorBonus()) + "</white>"
                    + (b.armorInsulated() ? " <green>[Cuir/Garniture Isolante]</green>" : "")
                    + (b.fireProtectionLevels() > 0 ? " <aqua>[Prot. Feu " + b.fireProtectionLevels() + "]</aqua>" : ""));
            thermalLore.add("<gray>• Repas & BreweryX :</gray> <gold>"
                    + String.format(Locale.US, "%+.1f°C", b.consumableAndBreweryBonus())
                    + (b.breweryActive() ? " (🍺 BreweryX actif)" : "") + "</gold>");
            thermalLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");
            thermalLore.add("<yellow>» Cliquez pour actualiser en direct</yellow>");

            inv.setItem(SLOT_THERMAL, createItem(
                    Material.RECOVERY_COMPASS,
                    "<gradient:#00E5FF:#18FFFF><bold>🌡️ Bilan Thermique Personnel</bold></gradient>",
                    thermalLore,
                    freezePct > 0 || b.totalTemperature() > cache.getHeatstrokeThresholdCelsius()
            ));
        }

        // 4. Slot 24 : Guide d'Artisanat Thermique & Cueillettes de la Saison active (v1.3.0)
        List<String> craftLore = new ArrayList<>();
        craftLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");
        craftLore.add("<white><bold>⚒️ Doublures d'Armure (Enclume — 3 niv.) :</bold></white>");
        craftLore.add("  <aqua>❄️ Doublure Polaire (+2.5°C/pièce en Hiver/Automne)</aqua>");
        craftLore.add("    <gray>» Armure + <white>Laine</white> ou <white>Peau de lapin</white></gray>");
        craftLore.add("  <gold>☀️ Doublure Respirante (-2.5°C/pièce en Été)</gold>");
        craftLore.add("    <gray>» Armure + <white>Boule de neige</white>, <white>Glace</white> ou <white>Bambou</white></gray>");
        craftLore.add("");
        craftLore.add("<white><bold>🍲 Cuisine de Survie (Table de Craft) :</bold></white>");
        craftLore.add("  <gold>🍲 Soupe Forestière Épicée (+6.0°C, 3 min)</gold>");
        craftLore.add("    <gray>» Bol + Champignon + Patate cuite + Baies sucrées</gray>");
        craftLore.add("");
        craftLore.add("<white><bold>🌿 Cueillettes de la Saison Active :</bold></white>");
        switch (currentSeason) {
            case SPRING -> {
                craftLore.add("  <green>🌸 Printemps : Récoltes agricoles boostées (+35%)</green>");
                craftLore.add("  <light_purple>🐣 Naissances gémellaires d'élevage (25%)</light_purple>");
            }
            case SUMMER -> {
                craftLore.add("  <green>🍏 Pomme Juteuse d'Été (-4.0°C, 90s & anti-fatigue)</green>");
                craftLore.add("    <gray>» 3% en cassant des feuilles de Chêne / Chêne noir</gray>");
            }
            case AUTUMN -> {
                craftLore.add("  <gold>🍄 Champignon d'Automne Doré (+3.0°C, 90s)</gold>");
                craftLore.add("  <light_purple>🍒 Baies Sauvages Sucrées (+3.0°C, 90s)</light_purple>");
                craftLore.add("    <gray>» 4% en cassant feuilles, fougères ou buissons</gray>");
            }
            case WINTER -> {
                craftLore.add("  <aqua>🐟 Prise des Glaces (+4 Nourriture & XP x2)</aqua>");
                craftLore.add("    <gray>» 20% lors d'une prise de cabillaud/saumon à la pêche</gray>");
            }
        }
        craftLore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");

        inv.setItem(SLOT_CRAFTING_FORAGING, createItem(
                Material.ANVIL,
                "<gradient:#FFAB00:#FF6D00><bold>⚒️ Artisanat Thermique & Cueillettes</bold></gradient>",
                craftLore,
                false
        ));
    }

    private ItemStack buildSeasonIcon(
            Season season,
            Material iconMaterial,
            boolean isActiveSeason,
            SubSeason activeSubSeason,
            List<String> cropLines,
            List<String> bonusLines
    ) {
        SeasonConfigCache cache = this.plugin.getConfigCache();
        SeasonSettings sSettings = cache.getSeasonSettings(season);
        String title = (sSettings != null ? sSettings.displayNameRaw() : season.name())
                + (isActiveSeason ? " <green><bold>(SAISON ACTIVE)</bold></green>" : "");

        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");
        lore.add("<white><bold>Sous-Saisons (3 Phases) :</bold></white>");

        SubSeason[] allSubs = SubSeason.values();
        for (int p = 0; p < 3; p++) {
            SubSeason sub = allSubs[season.ordinal() * 3 + p];
            SubSeasonSettings subSet = cache.getSubSeasonSettings(sub);
            double mod = (subSet != null) ? subSet.temperatureModifier() : 0.0D;
            String marker = (sub == activeSubSeason) ? "<green>▶ </green>" : "<dark_gray>  • </dark_gray>";
            String subName = (subSet != null) ? subSet.displayNameRaw() : sub.name();
            lore.add(marker + subName + " <gray>(" + String.format(Locale.US, "%+.1f°C", mod) + ")</gray>");
        }

        lore.add("");
        lore.add("<white><bold>🌾 Calendrier Agricole :</bold></white>");
        for (String line : cropLines) {
            lore.add("  " + line);
        }

        lore.add("");
        lore.add("<white><bold>✨ Écosystème & Climat :</bold></white>");
        for (String line : bonusLines) {
            lore.add("  " + line);
        }
        lore.add("<dark_gray>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</dark_gray>");

        return createItem(iconMaterial, title, lore, isActiveSeason);
    }

    private ItemStack createItem(Material material, String displayNameMini, List<String> loreMini, boolean glint) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(this.mm.deserialize(displayNameMini).decoration(TextDecoration.ITALIC, false));
            if (!loreMini.isEmpty()) {
                List<Component> loreComponents = new ArrayList<>(loreMini.size());
                for (String line : loreMini) {
                    loreComponents.add(this.mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(loreComponents);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(Boolean.TRUE);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String formatClock(long worldTime) {
        long totalMinutes = (((worldTime + 6000L) % 24000L) * 60L) / 1000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        String period = (worldTime < 12000L) ? "☀️ Jour" : "🌙 Nuit";
        return String.format(Locale.US, "%s %02dh%02d", period, hours, minutes);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AlmanacHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == SLOT_THERMAL && event.getWhoClicked() instanceof Player player) {
            populateInventory(player, event.getInventory());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 1.2F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AlmanacHolder) {
            event.setCancelled(true);
        }
    }
}
