package fr.alexuslab.paperseasons;

import fr.alexuslab.paperseasons.SeasonConfigCache.SeasonSettings;
import fr.alexuslab.paperseasons.SeasonConfigCache.SubSeasonSettings;
import fr.alexuslab.paperseasons.SeasonManager.Season;
import fr.alexuslab.paperseasons.SeasonManager.SubSeason;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Moteur autonome de Spoofing Visuel 3D des 12 Sous-Saisons (Netty ChannelDuplexHandler + ChunkBiomeData).
 *
 * Pré-encode en RAM (EnumMap<SubSeason, byte[]>) les 12 sections de biomes au démarrage
 * et déclenche refreshChunksForAllPlayers() à chaque transition de sous-saison.
 */
public final class BiomePacketSpoofer implements Listener {

    private static final String NETTY_HANDLER_NAME = "paperseasons_biome_spoof";

    // Biomes de surface à préserver hors-hiver (les grottes Y < 48 sont déjà protégées par sectionBottomY < minSurfaceY)
    private static final List<String> PROTECTED_BIOME_KEYWORDS = List.of(
            "ocean", "frozen", "ice_spikes",
            "desert", "badlands", "mesa", "volcanic", "caldera",
            "nether", "basalt", "crimson", "warped", "soul_sand", "incendium",
            "end", "void"
    );

    private final PaperSeasonsPlugin plugin;
    private final SeasonConfigCache configCache;
    private final SeasonManager seasonManager;

    // Identifiants numériques de registre et conteneurs pré-encodés pour les 12 Sous-Saisons
    private final EnumMap<SubSeason, Integer> subSeasonBiomeIds = new EnumMap<>(SubSeason.class);
    private final EnumMap<SubSeason, byte[]> subSeasonSectionBytes = new EnumMap<>(SubSeason.class);

    // Biomes protégés (Océans, Déserts, Grottes Terralith/Vanilla, Nether/Incendium, End)
    private final BitSet protectedBiomeIds = new BitSet(1024);
    private final Set<Object> protectedBiomeHolders = new HashSet<>();
    private final Predicate<Object> protectedHolderPredicate = this.protectedBiomeHolders::contains;

    // Réflexion NMS mise en cache une seule fois au démarrage (Zéro lookup à chaud)
    private boolean operational = false;
    private String statusDescription = "Inactif";
    private byte[] singleValueTrailer = new byte[0];
    private boolean trailerProbed = false;

    private Method craftPlayerGetHandleMethod;
    private Field serverPlayerConnectionField;
    private Field networkConnectionField;
    private Field connectionChannelField;
    private Method sendPacketMethod;

    private Method craftChunkGetHandleMethod;
    private Object chunkStatusFull;
    private Method levelChunkGetSectionsMethod;
    private Method levelChunkGetMinSectionYMethod;
    private boolean minSectionReturnsBlockY = false;

    private Method sectionGetBiomesMethod;
    private Method biomesWriteMethod;
    private Method biomesMaybeHasMethod;

    private Constructor<?> friendlyByteBufConstructor;
    private Constructor<?> chunkPosConstructor;
    private Constructor<?> chunkBiomeDataConstructor;
    private Constructor<?> chunksBiomesPacketConstructor;

    private final ThreadLocal<Boolean> sendingSpoofedPacket = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public BiomePacketSpoofer(
            PaperSeasonsPlugin plugin,
            SeasonConfigCache configCache,
            SeasonManager seasonManager
    ) {
        this.plugin = plugin;
        this.configCache = configCache;
        this.seasonManager = seasonManager;
    }

    public void initialize() {
        this.subSeasonBiomeIds.clear();
        this.subSeasonSectionBytes.clear();
        this.protectedBiomeIds.clear();
        this.protectedBiomeHolders.clear();
        this.operational = false;

        if (!this.configCache.isPacketBiomeSpoofingEnabled()) {
            this.statusDescription = "Désactivé dans config.yml";
            return;
        }

        try {
            initNmsReflection();
            resolveBiomeRegistryIds();

            if (!this.subSeasonBiomeIds.isEmpty()) {
                rebuildPreEncodedSubSeasonBytes();
                this.operational = true;
                this.statusDescription = "Actif (Netty 3D Autonome, "
                        + this.subSeasonBiomeIds.size() + "/12 sous-saisons, Y >= "
                        + this.configCache.getMinSurfaceY()
                        + ", " + this.protectedBiomeHolders.size() + " biomes protégés)";
                this.plugin.getLogger().info("[PaperSeasons] Spoofing visuel 3D v1.1 initialisé : " + this.statusDescription);
            } else {
                this.statusDescription = "Data Pack paper_seasons_dp manquant (0/12 biomes trouvés)";
                this.plugin.getLogger().warning("[PaperSeasons] " + this.statusDescription
                        + ". Vérifiez que paper_seasons_dp.zip v1.1 est bien dans world/datapacks/.");
            }
        } catch (Throwable t) {
            this.operational = false;
            this.statusDescription = "Fallback passif (" + t.getClass().getSimpleName() + ")";
            this.plugin.getLogger().log(Level.WARNING,
                    "[PaperSeasons] Impossible d'initialiser le hook NMS/Netty de spoofing 3D", t);
        }

        if (this.operational) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                injectPlayerNettyHandler(player);
            }
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            removePlayerNettyHandler(player);
        }
    }

    private void initNmsReflection() throws Exception {
        String craftBukkitPkg = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftPlayerClass = Class.forName(craftBukkitPkg + ".entity.CraftPlayer");
        this.craftPlayerGetHandleMethod = craftPlayerClass.getMethod("getHandle");
        Class<?> serverPlayerClass = this.craftPlayerGetHandleMethod.getReturnType();

        for (Field f : serverPlayerClass.getDeclaredFields()) {
            if (f.getType().getSimpleName().contains("PacketListener")
                    || f.getName().equals("connection")) {
                f.setAccessible(true);
                this.serverPlayerConnectionField = f;
                break;
            }
        }
        if (this.serverPlayerConnectionField == null) {
            throw new NoSuchFieldException("ServerPlayer.connection introuvable");
        }

        Class<?> listenerClass = this.serverPlayerConnectionField.getType();
        this.networkConnectionField = findFieldByTypeName(listenerClass, "Connection");
        if (this.networkConnectionField == null) {
            throw new NoSuchFieldException("Connection introuvable dans " + listenerClass.getName());
        }

        Class<?> connectionClass = this.networkConnectionField.getType();
        this.connectionChannelField = findFieldByType(connectionClass, Channel.class);
        if (this.connectionChannelField == null) {
            throw new NoSuchFieldException("io.netty.channel.Channel introuvable dans Connection");
        }

        try {
            Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
            this.sendPacketMethod = listenerClass.getMethod("send", packetInterface);
        } catch (ReflectiveOperationException ignored) {
            this.sendPacketMethod = null;
        }

        Class<?> craftChunkClass = Class.forName(craftBukkitPkg + ".CraftChunk");
        try {
            Class<?> chunkStatusClass = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");
            Field fullField = chunkStatusClass.getField("FULL");
            this.chunkStatusFull = fullField.get(null);
            this.craftChunkGetHandleMethod = craftChunkClass.getMethod("getHandle", chunkStatusClass);
        } catch (ReflectiveOperationException ex) {
            this.chunkStatusFull = null;
            this.craftChunkGetHandleMethod = craftChunkClass.getMethod("getHandle");
        }

        Class<?> chunkAccessClass = Class.forName("net.minecraft.world.level.chunk.ChunkAccess");
        this.levelChunkGetSectionsMethod = chunkAccessClass.getMethod("getSections");
        try {
            this.levelChunkGetMinSectionYMethod = chunkAccessClass.getMethod("getMinSectionY");
            this.minSectionReturnsBlockY = false;
        } catch (NoSuchMethodException e1) {
            try {
                this.levelChunkGetMinSectionYMethod = chunkAccessClass.getMethod("getMinSection");
                this.minSectionReturnsBlockY = false;
            } catch (NoSuchMethodException e2) {
                this.levelChunkGetMinSectionYMethod = chunkAccessClass.getMethod("getMinY");
                this.minSectionReturnsBlockY = true;
            }
        }

        Class<?> sectionClass = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection");
        this.sectionGetBiomesMethod = sectionClass.getMethod("getBiomes");
        Class<?> palettedContainerROClass = this.sectionGetBiomesMethod.getReturnType();

        Class<?> friendlyByteBufClass = Class.forName("net.minecraft.network.FriendlyByteBuf");
        this.friendlyByteBufConstructor = friendlyByteBufClass.getConstructor(ByteBuf.class);
        this.biomesWriteMethod = palettedContainerROClass.getMethod("write", friendlyByteBufClass);
        this.biomesMaybeHasMethod = palettedContainerROClass.getMethod("maybeHas", Predicate.class);

        Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos");
        this.chunkPosConstructor = chunkPosClass.getConstructor(int.class, int.class);

        Class<?> chunkBiomeDataClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData"
        );
        this.chunkBiomeDataConstructor = chunkBiomeDataClass.getConstructor(chunkPosClass, byte[].class);

        Class<?> chunksBiomesPacketClass = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket"
        );
        this.chunksBiomesPacketConstructor = chunksBiomesPacketClass.getConstructor(List.class);
    }

    private void resolveBiomeRegistryIds() throws Exception {
        Class<?> mcServerClass = Class.forName("net.minecraft.server.MinecraftServer");
        Object mcServer = mcServerClass.getMethod("getServer").invoke(null);
        Object registryAccess = mcServerClass.getMethod("registryAccess").invoke(mcServer);

        Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries");
        Object biomeRegistryKey = registriesClass.getField("BIOME").get(null);

        Object biomeRegistry = null;
        for (Method m : registryAccess.getClass().getMethods()) {
            if ((m.getName().equals("registryOrThrow") || m.getName().equals("lookupOrThrow"))
                    && m.getParameterCount() == 1) {
                biomeRegistry = m.invoke(registryAccess, biomeRegistryKey);
                break;
            }
        }
        if (biomeRegistry == null) {
            throw new IllegalStateException("Registre BIOME introuvable dans RegistryAccess");
        }

        Class<?> registryClass = biomeRegistry.getClass();
        Method getKeyMethod = findMethodByNameAndParamCount(registryClass, "getKey", 1);
        Method getIdMethod = findMethodByNameAndParamCount(registryClass, "getId", 1);
        Method wrapAsHolderMethod = findMethodByNameAndParamCount(registryClass, "wrapAsHolder", 1);

        Map<String, Integer> registryKeyToId = new HashMap<>();

        Iterable<?> iterableRegistry = (Iterable<?>) biomeRegistry;
        for (Object biome : iterableRegistry) {
            Object resourceLoc = getKeyMethod.invoke(biomeRegistry, biome);
            if (resourceLoc == null) {
                continue;
            }
            String fullKey = resourceLoc.toString().toLowerCase(Locale.ROOT);
            int rawId = (int) getIdMethod.invoke(biomeRegistry, biome);
            Object holder = (wrapAsHolderMethod != null) ? wrapAsHolderMethod.invoke(biomeRegistry, biome) : null;

            registryKeyToId.put(fullKey, rawId);

            if (isBiomeKeyProtected(fullKey)) {
                if (rawId >= 0) {
                    this.protectedBiomeIds.set(rawId);
                }
                if (holder != null) {
                    this.protectedBiomeHolders.add(holder);
                }
            }
        }

        // Associe chacune des 12 sous-saisons à son ID de biome (avec fallback v1.0 si besoin)
        for (SubSeason sub : SubSeason.values()) {
            SubSeasonSettings subSettings = this.configCache.getSubSeasonSettings(sub);
            String desiredKey = (subSettings != null)
                    ? subSettings.clientBiomeKey().toLowerCase(Locale.ROOT)
                    : sub.getDefaultBiomeKey();
            Integer resolvedId = registryKeyToId.get(desiredKey);

            if (resolvedId == null) {
                // Fallback sur les 4 clés v1.0 (spring_tint, summer_tint, autumn_tint, winter_snow)
                Season parent = sub.getParentSeason();
                String legacyKey = (parent == Season.WINTER)
                        ? "paperseasons:winter_snow"
                        : "paperseasons:" + parent.name().toLowerCase(Locale.ROOT) + "_tint";
                resolvedId = registryKeyToId.get(legacyKey);
                if (resolvedId == null) {
                    SeasonSettings parentSettings = this.configCache.getSeasonSettings(parent);
                    if (parentSettings != null) {
                        resolvedId = registryKeyToId.get(parentSettings.clientBiomeKey().toLowerCase(Locale.ROOT));
                    }
                }
            }

            if (resolvedId != null) {
                this.subSeasonBiomeIds.put(sub, resolvedId);
            }
        }
    }

    private boolean isBiomeKeyProtected(String fullKey) {
        if (fullKey.startsWith("paperseasons:")) {
            return false;
        }
        if (this.configCache.isPreserveNetherAndEnd()
                && (fullKey.startsWith("incendium:") || fullKey.contains("nether") || fullKey.contains("end"))) {
            return true;
        }
        if (!this.configCache.isPreserveOceanAndCaveBiomes()) {
            return false;
        }
        for (String keyword : PROTECTED_BIOME_KEYWORDS) {
            if (fullKey.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildPreEncodedSubSeasonBytes() {
        for (SubSeason sub : SubSeason.values()) {
            Integer id = this.subSeasonBiomeIds.get(sub);
            if (id != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(8);
                out.write(0);
                writeVarInt(out, id);
                out.writeBytes(this.singleValueTrailer);
                this.subSeasonSectionBytes.put(sub, out.toByteArray());
            }
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(ByteBuf buf) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = buf.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt trop grand");
            }
        } while ((read & 0x80) != 0);
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        if (!this.operational) {
            return;
        }
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL
                || !this.configCache.isWorldEnabled(world.getName())) {
            return;
        }

        Chunk chunk = event.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        // Envoi différé de 2 ticks pour garantir que ClientboundChunksBiomesPacket arrive APRÈS ClientboundLevelChunkWithLightPacket
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline() && player.getWorld().equals(world) && world.isChunkLoaded(cx, cz)) {
                sendSpoofedChunkBiomes(player, List.of(world.getChunkAt(cx, cz)));
            }
        }, 2L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (this.operational) {
            Player player = event.getPlayer();
            injectPlayerNettyHandler(player);
            Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                if (player.isOnline()) {
                    refreshChunksForPlayer(player);
                }
            }, 15L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removePlayerNettyHandler(event.getPlayer());
    }

    public void refreshChunksForAllPlayers() {
        if (!this.operational) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshChunksForPlayer(player);
        }
    }

    public void refreshChunksForPlayer(Player player) {
        if (!this.operational || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL
                || !this.configCache.isWorldEnabled(world.getName())) {
            return;
        }
        Chunk center = player.getLocation().getChunk();
        int cx = center.getX();
        int cz = center.getZ();
        int radius = Math.min(10, Bukkit.getViewDistance());
        List<Chunk> batch = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (world.isChunkLoaded(cx + dx, cz + dz)) {
                    batch.add(world.getChunkAt(cx + dx, cz + dz));
                }
            }
        }
        if (!batch.isEmpty()) {
            sendSpoofedChunkBiomes(player, batch);
        }
    }

    public void sendSpoofedChunkBiomes(Player player, List<Chunk> chunks) {
        if (!this.operational || chunks.isEmpty()) {
            return;
        }
        SubSeason currentSubSeason = this.seasonManager.getCurrentSubSeason();
        Season currentSeason = this.seasonManager.getCurrentSeason();
        boolean isWinter = (currentSeason == Season.WINTER);
        try {
            Channel channel = getPlayerChannel(player);
            if (channel == null || !channel.isActive()) {
                return;
            }

            List<Object> chunkBiomeDataList = new ArrayList<>(chunks.size());
            int minSurfaceY = this.configCache.getMinSurfaceY();

            for (Chunk chunk : chunks) {
                Object nmsChunk = (this.chunkStatusFull != null)
                        ? this.craftChunkGetHandleMethod.invoke(chunk, this.chunkStatusFull)
                        : this.craftChunkGetHandleMethod.invoke(chunk);
                if (nmsChunk == null) {
                    continue;
                }

                Object[] sections = (Object[]) this.levelChunkGetSectionsMethod.invoke(nmsChunk);
                int minSectionRaw = (int) this.levelChunkGetMinSectionYMethod.invoke(nmsChunk);
                int minSectionY = this.minSectionReturnsBlockY ? (minSectionRaw >> 4) : minSectionRaw;

                if (!this.trailerProbed && sections.length > 0) {
                    probeSingleValueTrailer(sections);
                }

                byte[] preEncodedSurfaceSection = this.subSeasonSectionBytes.get(currentSubSeason);
                if (preEncodedSurfaceSection == null) {
                    return;
                }

                ByteBuf rawBuf = Unpooled.buffer(256);
                try {
                    Object friendlyBuf = this.friendlyByteBufConstructor.newInstance(rawBuf);

                    for (int i = 0; i < sections.length; i++) {
                        int sectionBottomY = (minSectionY + i) << 4;
                        Object biomesContainer = this.sectionGetBiomesMethod.invoke(sections[i]);

                        // Protection 3D des grottes (Y < 48)
                        if (sectionBottomY < minSurfaceY) {
                            this.biomesWriteMethod.invoke(biomesContainer, friendlyBuf);
                        } else if (isWinter) {
                            // En HIVER : force le biome neigeux sur 100% de la surface (Y >= 48) pour que la météo affiche de la neige partout
                            rawBuf.writeBytes(preEncodedSurfaceSection);
                        } else {
                            boolean hasProtected = (boolean) this.biomesMaybeHasMethod.invoke(
                                    biomesContainer, this.protectedHolderPredicate
                            );
                            if (hasProtected) {
                                this.biomesWriteMethod.invoke(biomesContainer, friendlyBuf);
                            } else {
                                rawBuf.writeBytes(preEncodedSurfaceSection);
                            }
                        }
                    }

                    byte[] sectionBytes = new byte[rawBuf.readableBytes()];
                    rawBuf.readBytes(sectionBytes);

                    Object chunkPos = this.chunkPosConstructor.newInstance(chunk.getX(), chunk.getZ());
                    Object chunkBiomeData = this.chunkBiomeDataConstructor.newInstance(chunkPos, sectionBytes);
                    chunkBiomeDataList.add(chunkBiomeData);
                } finally {
                    rawBuf.release();
                }
            }

            if (!chunkBiomeDataList.isEmpty()) {
                Object packet = this.chunksBiomesPacketConstructor.newInstance(
                        Collections.unmodifiableList(chunkBiomeDataList)
                );
                this.sendingSpoofedPacket.set(Boolean.TRUE);
                try {
                    if (this.sendPacketMethod != null) {
                        Object serverPlayer = this.craftPlayerGetHandleMethod.invoke(player);
                        Object listener = this.serverPlayerConnectionField.get(serverPlayer);
                        this.sendPacketMethod.invoke(listener, packet);
                    } else {
                        channel.writeAndFlush(packet);
                    }
                } finally {
                    this.sendingSpoofedPacket.set(Boolean.FALSE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private synchronized void probeSingleValueTrailer(Object[] sections) {
        if (this.trailerProbed) {
            return;
        }
        // Parcourt du haut (ciel Y=304, toujours uniforme bitsPerEntry == 0) vers le bas
        for (int i = sections.length - 1; i >= 0; i--) {
            ByteBuf buf = Unpooled.buffer(32);
            try {
                Object biomesContainer = this.sectionGetBiomesMethod.invoke(sections[i]);
                Object friendlyBuf = this.friendlyByteBufConstructor.newInstance(buf);
                this.biomesWriteMethod.invoke(biomesContainer, friendlyBuf);

                int bitsPerEntry = buf.readUnsignedByte();
                if (bitsPerEntry == 0) {
                    readVarInt(buf);
                    int remaining = buf.readableBytes();
                    byte[] trailer = new byte[remaining];
                    buf.readBytes(trailer);
                    this.singleValueTrailer = trailer;
                    this.trailerProbed = true;
                    rebuildPreEncodedSubSeasonBytes();
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                buf.release();
            }
        }
    }

    private void injectPlayerNettyHandler(Player player) {
        try {
            Channel channel = getPlayerChannel(player);
            if (channel == null || channel.pipeline().get(NETTY_HANDLER_NAME) != null) {
                return;
            }
            if (channel.pipeline().get("packet_handler") != null) {
                channel.pipeline().addBefore("packet_handler", NETTY_HANDLER_NAME, new ChannelDuplexHandler() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                        super.write(ctx, msg, promise);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private void removePlayerNettyHandler(Player player) {
        try {
            Channel channel = getPlayerChannel(player);
            if (channel != null && channel.pipeline().get(NETTY_HANDLER_NAME) != null) {
                channel.pipeline().remove(NETTY_HANDLER_NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    private Channel getPlayerChannel(Player player) throws Exception {
        Object serverPlayer = this.craftPlayerGetHandleMethod.invoke(player);
        Object listener = this.serverPlayerConnectionField.get(serverPlayer);
        if (this.networkConnectionField == null || listener == null) {
            return null;
        }
        Object connection = this.networkConnectionField.get(listener);
        return (Channel) this.connectionChannelField.get(connection);
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> targetType) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (targetType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findFieldByTypeName(Class<?> clazz, String simpleTypeName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals(simpleTypeName)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findMethodByNameAndParamCount(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    public boolean isOperational() {
        return this.operational;
    }

    public String getStatusDescription() {
        return this.statusDescription;
    }
}
