package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonConfigCache.SubSeasonSettings;
import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SeasonalEvent;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import fr.alexuslab.paperseasons.TemperatureTask.ThermalBreakdown;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Commande principale /season v1.2.0 (calendar, status, event, set, next, nextday, reload).
 */
public final class SeasonCommand extends Command {

    private static final List<String> SUBCOMMANDS_ADMIN = List.of(
            "calendar", "status", "event", "set", "next", "nextday", "reload"
    );
    private static final List<String> SUBCOMMANDS_PLAYER = List.of("calendar", "status");

    private final PaperSeasonsPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SeasonCommand(PaperSeasonsPlugin plugin) {
        super(
                "season",
                "Affiche l'Almanach des Saisons ou contrôle PaperSeasons v1.2.0",
                "/season [calendar|status|event <type>|set <saison>|next|nextday|reload]",
                List.of("seasons", "paperseasons")
        );
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && this.plugin.getCalendarGui() != null) {
                this.plugin.getCalendarGui().open(player);
            } else {
                handleStatus(sender);
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "calendar", "almanac", "gui" -> {
                if (!(sender instanceof Player player)) {
                    sendMsg(sender, "<red>Seul un joueur en jeu peut ouvrir l'Almanach GUI. Utilisez /season status.</red>");
                    return true;
                }
                if (this.plugin.getCalendarGui() != null) {
                    this.plugin.getCalendarGui().open(player);
                }
                return true;
            }
            case "status" -> {
                handleStatus(sender);
                return true;
            }
            case "event" -> {
                if (!checkAdminPermission(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sendMsg(sender, "<red>Usage : /season event <NONE|BLIZZARD|HEATWAVE|AUTUMN_MIST|SPRING_BLOOM></red>");
                    return true;
                }
                String rawEvent = args[1].toUpperCase(Locale.ROOT);
                try {
                    SeasonalEvent targetEvent = SeasonalEvent.valueOf(rawEvent);
                    this.plugin.getSeasonManager().setSeasonalEvent(targetEvent, targetEvent != SeasonalEvent.NONE);
                    sendMsg(sender, "<green>✔ Événement climatique forcé sur : <bold>" + targetEvent.name()
                            + "</bold> (" + targetEvent.getDisplayNameRaw() + "<green>).</green>");
                } catch (IllegalArgumentException ex) {
                    sendMsg(sender, "<red>Événement inconnu. Valeurs : NONE, BLIZZARD, HEATWAVE, AUTUMN_MIST, SPRING_BLOOM.</red>");
                }
                return true;
            }
            case "set" -> {
                if (!checkAdminPermission(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sendMsg(sender, "<red>Usage : /season set <SPRING|SUMMER|AUTUMN|WINTER|SPRING_EARLY|...></red>");
                    return true;
                }
                String rawTarget = args[1].toUpperCase(Locale.ROOT);
                try {
                    Season targetSeason = Season.valueOf(rawTarget);
                    this.plugin.getSeasonManager().setSeason(targetSeason, true);
                    sendMsg(sender, "<green>✔ Saison forcée sur <bold>" + targetSeason.name()
                            + "</bold> (" + this.plugin.getSeasonManager().getCurrentSubSeason().name() + ", Jour 1).</green>");
                    return true;
                } catch (IllegalArgumentException ignored) {
                }
                try {
                    SubSeason targetSub = SubSeason.valueOf(rawTarget);
                    this.plugin.getSeasonManager().setSubSeason(targetSub);
                    sendMsg(sender, "<green>✔ Sous-saison forcée sur <bold>" + targetSub.name()
                            + "</bold> (Jour " + this.plugin.getSeasonManager().getCurrentDayInSeason() + ").</green>");
                    return true;
                } catch (IllegalArgumentException ex) {
                    sendMsg(sender, "<red>Saison ou sous-saison invalide. Exemples : SPRING, AUTUMN, AUTUMN_MID, WINTER_LATE.</red>");
                    return true;
                }
            }
            case "next" -> {
                if (!checkAdminPermission(sender)) {
                    return true;
                }
                this.plugin.getSeasonManager().nextSeason();
                Season current = this.plugin.getSeasonManager().getCurrentSeason();
                SubSeason currentSub = this.plugin.getSeasonManager().getCurrentSubSeason();
                sendMsg(sender, "<green>✔ Passage forcé à la saison suivante : <bold>"
                        + current.name() + "</bold> (" + currentSub.name() + ").</green>");
                return true;
            }
            case "nextday" -> {
                if (!checkAdminPermission(sender)) {
                    return true;
                }
                this.plugin.getSeasonManager().advanceOneDay();
                SeasonManager sm = this.plugin.getSeasonManager();
                sendMsg(sender, "<green>✔ Passage au jour suivant : <bold>Jour "
                        + sm.getCurrentDayInSeason() + "/" + this.plugin.getConfigCache().getDaysPerSeason()
                        + "</bold> (" + sm.getCurrentSubSeason().name() + ").</green>");
                return true;
            }
            case "reload" -> {
                if (!checkAdminPermission(sender)) {
                    return true;
                }
                this.plugin.reloadPluginConfig();
                sendMsg(sender, "<green>✔ Configuration PaperSeasons v1.2.0 et caches O(1) rechargés.</green>");
                return true;
            }
            default -> {
                sendMsg(sender, "<red>Sous-commande inconnue. Utilisez <white>/season [calendar|status|event|set|next|nextday|reload]</white>.</red>");
                return true;
            }
        }
    }

    private void handleStatus(CommandSender sender) {
        SeasonManager sm = this.plugin.getSeasonManager();
        SeasonConfigCache cache = this.plugin.getConfigCache();
        Season current = sm.getCurrentSeason();
        SubSeason currentSub = sm.getCurrentSubSeason();
        SeasonalEvent currentEvent = sm.getCurrentEvent();
        SeasonSettings settings = cache.getSeasonSettings(current);
        SubSeasonSettings subSettings = cache.getSubSeasonSettings(currentSub);

        long worldTime = 6000L;
        if (sender instanceof Player p) {
            worldTime = p.getWorld().getTime();
        } else {
            for (String wName : cache.getEnabledWorlds()) {
                World w = Bukkit.getWorld(wName);
                if (w != null) {
                    worldTime = w.getTime();
                    break;
                }
            }
        }
        int subPct = (int) Math.round(sm.getSubSeasonProgress(worldTime) * 100.0D);

        sendMsg(sender,
                "<dark_gray><strikethrough>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</strikethrough></dark_gray> "
                        + "<gradient:#76FF03:#00E5FF><bold>PaperSeasons v1.2</bold></gradient> "
                        + "<dark_gray><strikethrough>⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯</strikethrough></dark_gray>"
        );
        sendMsg(sender,
                "<gray>• Saison & Phase :</gray> "
                        + (settings != null ? settings.displayNameRaw() : current.name())
                        + " <dark_gray>»</dark_gray> "
                        + (subSettings != null ? subSettings.displayNameRaw() : currentSub.name())
                        + " <dark_gray>(" + subPct + "%)</dark_gray>"
        );
        sendMsg(sender,
                "<gray>• Calendrier :</gray> <white>Année " + sm.getCurrentYear()
                        + " — Jour " + sm.getCurrentDayInSeason() + "/" + cache.getDaysPerSeason()
                        + " (" + formatMinecraftClock(worldTime) + ")</white>"
        );
        sendMsg(sender,
                "<gray>• Événement Climatique :</gray> "
                        + (currentEvent != SeasonalEvent.NONE
                        ? currentEvent.getDisplayNameRaw() + " <dark_gray>(" + currentEvent.name() + ")</dark_gray>"
                        : "<dark_gray>Aucun (Climat normal)</dark_gray>")
        );

        BiomePacketSpoofer spoofer = this.plugin.getBiomePacketSpoofer();
        if (spoofer != null) {
            String color = spoofer.isOperational() ? "<green>" : "<yellow>";
            String endColor = spoofer.isOperational() ? "</green>" : "</yellow>";
            sendMsg(sender, "<gray>• Spoofing Visuel 3D :</gray> " + color + spoofer.getStatusDescription() + endColor);
        }

        if (sender instanceof Player player && settings != null) {
            TemperatureTask tempTask = this.plugin.getTemperatureTask();
            ThermalBreakdown b = tempTask.computeThermalBreakdownO1(
                    player, player.getWorld(), current, currentSub, settings
            );
            Block block = player.getLocation().getBlock();
            int skyLight = block.getLightFromSky();
            int blockLight = block.getLightFromBlocks();

            String formattedTemp = String.format(Locale.US, "%+.1f°C", b.totalTemperature());
            String formattedInertie = String.format(Locale.US, "%+.1f°C", b.interpolatedSeasonMod());
            String formattedDiurnal = String.format(Locale.US, "%+.1f°C", b.diurnalOffset());

            sendMsg(sender,
                    "<gray>• Température ressentie :</gray> <aqua><bold>" + formattedTemp + "</bold></aqua> "
                            + "<dark_gray>(Inertie Cosinus: " + formattedInertie
                            + " ｜ Cycle Jour/Nuit: " + formattedDiurnal + ")</dark_gray>"
            );
            sendMsg(sender,
                    "<gray>• Humidité & Vent :</gray> <blue>💧 Mouillure " + b.wetnessPercent() + "% ("
                            + String.format(Locale.US, "%+.1f°C", b.wetnessThermalOffset()) + ")</blue>"
                            + " <dark_gray>｜</dark_gray> <white>🌬️ Wind Chill : "
                            + String.format(Locale.US, "%+.1f°C", b.windChillOffset()) + "</white>"
            );
            sendMsg(sender,
                    "<gray>• Abri & Armure (Sky=" + skyLight + ", Block=" + blockLight + ") :</gray> "
                            + (b.sheltered() ? "<green>Isolé (-70%)</green>" : "<yellow>Exposé</yellow>")
                            + " <dark_gray>｜</dark_gray> <white>Armure " + String.format(Locale.US, "%+.1f°C", b.armorBonus()) + "</white>"
                            + (b.armorInsulated() ? " <green>[Cuir/Trim]</green>" : "")
                            + (b.fireProtectionLevels() > 0 ? " <aqua>[Prot.Feu " + b.fireProtectionLevels() + "]</aqua>" : "")
            );

            List<String> activeBuffs = new ArrayList<>();
            if (b.warmingSecondsLeft() > 0L) {
                activeBuffs.add("<gold>🍲 Repas chaud (+" + cache.getWarmingBuffCelsius() + "°C, " + b.warmingSecondsLeft() + "s)</gold>");
            }
            if (b.coolingSecondsLeft() > 0L) {
                activeBuffs.add("<aqua>🧊 Fraîcheur (" + cache.getCoolingBuffCelsius() + "°C, " + b.coolingSecondsLeft() + "s)</aqua>");
            }
            if (b.breweryActive()) {
                activeBuffs.add("<light_purple>🍺 Alcool BreweryX (+" + cache.getBreweryWinterWarmthBonus() + "°C)</light_purple>");
            }
            sendMsg(sender,
                    "<gray>• Buffs actifs :</gray> "
                            + (activeBuffs.isEmpty() ? "<dark_gray>Aucun</dark_gray>" : String.join(" <dark_gray>｜</dark_gray> ", activeBuffs))
            );
        }
    }

    private static String formatMinecraftClock(long worldTime) {
        long totalMinutes = (((worldTime + 6000L) % 24000L) * 60L) / 1000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        String period = (worldTime < 12000L) ? "☀️ Jour" : "🌙 Nuit";
        return String.format(Locale.US, "%s %02dh%02d", period, hours, minutes);
    }

    private void sendMsg(CommandSender sender, String miniMessageText) {
        ((Audience) sender).sendMessage(this.mm.deserialize(miniMessageText));
    }

    private boolean checkAdminPermission(CommandSender sender) {
        if (!sender.hasPermission("paperseasons.admin")) {
            sendMsg(sender, "<red>Vous n'avez pas la permission (paperseasons.admin).</red>");
            return false;
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        boolean isAdmin = sender.hasPermission("paperseasons.admin");

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> base = isAdmin ? SUBCOMMANDS_ADMIN : SUBCOMMANDS_PLAYER;
            List<String> matches = new ArrayList<>();
            for (String s : base) {
                if (s.startsWith(prefix)) {
                    matches.add(s);
                }
            }
            return matches;
        }

        if (args.length == 2 && isAdmin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toUpperCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if (sub.equals("set")) {
                for (Season s : Season.values()) {
                    if (s.name().startsWith(prefix)) {
                        matches.add(s.name());
                    }
                }
                for (SubSeason ss : SubSeason.values()) {
                    if (ss.name().startsWith(prefix)) {
                        matches.add(ss.name());
                    }
                }
            } else if (sub.equals("event")) {
                for (SeasonalEvent ev : SeasonalEvent.values()) {
                    if (ev.name().startsWith(prefix)) {
                        matches.add(ev.name());
                    }
                }
            }
            return matches;
        }

        return Collections.emptyList();
    }
}
