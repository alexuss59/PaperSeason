package fr.alexuslab.paperseasons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Artisanat de survie thermique v1.3.0 (sans mod ni resource pack) :
 * - Doublures d'armure WARM / COOL via l'Enclume (PersistentDataContainer "thermal_lining")
 * - Recette ShapedRecipe de la Soupe Forestière Épicée
 * - Fabrique des objets de cueillette saisonnière (tag PDC "season_food")
 */
public final class SeasonCraftingManager implements Listener {

    public static final String LINING_WARM = "WARM";
    public static final String LINING_COOL = "COOL";
    public static final double LINING_CELSIUS = 2.5D;

    public static final String FOOD_SPICY_SOUP = "SPICY_SOUP";
    public static final String FOOD_GOLDEN_MUSHROOM = "GOLDEN_MUSHROOM";
    public static final String FOOD_WILD_BERRIES = "WILD_BERRIES";
    public static final String FOOD_SUMMER_APPLE = "SUMMER_APPLE";
    public static final String FOOD_ICE_CATCH = "ICE_CATCH";

    private static final String LORE_WARM = "<aqua>❄️ Doublure Polaire (+2.5°C)</aqua>";
    private static final String LORE_COOL = "<gold>☀️ Doublure Respirante (-2.5°C)</gold>";
    private static final int ANVIL_LEVEL_COST = 3;

    private final PaperSeasonsPlugin plugin;
    private final NamespacedKey liningKey;
    private final NamespacedKey foodKey;
    private final NamespacedKey soupRecipeKey;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public SeasonCraftingManager(PaperSeasonsPlugin plugin) {
        this.plugin = plugin;
        this.liningKey = new NamespacedKey(plugin, "thermal_lining");
        this.foodKey = new NamespacedKey(plugin, "season_food");
        this.soupRecipeKey = new NamespacedKey(plugin, "spicy_forest_soup");
    }

    public NamespacedKey getLiningKey() {
        return this.liningKey;
    }

    public NamespacedKey getFoodKey() {
        return this.foodKey;
    }

    // =========================================================================
    // RECETTES
    // =========================================================================
    public void registerRecipes() {
        unregisterRecipes();
        ShapedRecipe soup = new ShapedRecipe(this.soupRecipeKey, createSpicyForestSoup());
        soup.shape("MPS", " B ");
        soup.setIngredient('M', new RecipeChoice.MaterialChoice(Material.BROWN_MUSHROOM, Material.RED_MUSHROOM));
        soup.setIngredient('P', Material.BAKED_POTATO);
        soup.setIngredient('S', Material.SWEET_BERRIES);
        soup.setIngredient('B', Material.BOWL);
        Bukkit.addRecipe(soup, true);
    }

    public void unregisterRecipes() {
        if (Bukkit.getRecipe(this.soupRecipeKey) != null) {
            Bukkit.removeRecipe(this.soupRecipeKey, true);
        }
    }

    // =========================================================================
    // DOUBLURES D'ARMURE À L'ENCLUME (PrepareAnvilEvent)
    // =========================================================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack armor = inv.getFirstItem();
        ItemStack material = inv.getSecondItem();
        if (armor == null || material == null || armor.getAmount() != 1 || !isArmorPiece(armor.getType())) {
            return;
        }

        String lining = resolveLiningFromMaterial(material.getType());
        if (lining == null) {
            return;
        }
        String existing = getLining(armor);
        if (lining.equals(existing)) {
            event.setResult(null);
            return;
        }

        ItemStack result = armor.clone();
        applyLining(result, lining);
        event.setResult(result);

        AnvilView view = event.getView();
        view.setRepairCost(ANVIL_LEVEL_COST);
        view.setRepairItemCountCost(1);
    }

    public static boolean isArmorPiece(Material type) {
        String n = type.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }

    private String resolveLiningFromMaterial(Material type) {
        if (Tag.WOOL.isTagged(type) || type == Material.RABBIT_HIDE) {
            return LINING_WARM;
        }
        if (type == Material.SNOWBALL || type == Material.ICE || type == Material.BAMBOO) {
            return LINING_COOL;
        }
        return null;
    }

    public String getLining(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(this.liningKey, PersistentDataType.STRING);
    }

    private void applyLining(ItemStack stack, String lining) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(this.liningKey, PersistentDataType.STRING, lining);

        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        lore.removeIf(line -> {
            String txt = plain.serialize(line);
            return txt.contains("Doublure Polaire") || txt.contains("Doublure Respirante");
        });
        lore.add(this.mm.deserialize(LINING_WARM.equals(lining) ? LORE_WARM : LORE_COOL)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
    }

    // =========================================================================
    // OBJETS DE SAISON (tag PDC season_food)
    // =========================================================================
    public String getFoodTag(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(this.foodKey, PersistentDataType.STRING);
    }

    public ItemStack createSpicyForestSoup() {
        return createFood(Material.MUSHROOM_STEW, FOOD_SPICY_SOUP, null,
                "<gradient:#FF6D00:#FFAB00><bold>🍲 Soupe Forestière Épicée</bold></gradient>",
                List.of(
                        "<gray>Champignons, patate rôtie et baies sucrées.</gray>",
                        "<gold>🔥 +6.0°C pendant 3 minutes</gold> <dark_gray>(hors été)</dark_gray>"
                ), ItemRarity.RARE);
    }

    public ItemStack createGoldenMushroom() {
        return createFood(Material.BAKED_POTATO, FOOD_GOLDEN_MUSHROOM, NamespacedKey.minecraft("brown_mushroom"),
                "<gradient:#FFD600:#FF8F00><bold>🍄 Champignon d'Automne Doré</bold></gradient>",
                List.of(
                        "<gray>Une cueillette rare des sous-bois d'automne.</gray>",
                        "<gold>🔥 +3.0°C pendant 90 secondes</gold>"
                ), ItemRarity.UNCOMMON);
    }

    public ItemStack createWildBerries(int amount) {
        ItemStack stack = createFood(Material.SWEET_BERRIES, FOOD_WILD_BERRIES, null,
                "<gradient:#FF1744:#F50057><bold>🍒 Baies Sauvages Sucrées</bold></gradient>",
                List.of(
                        "<gray>Gorgées du dernier soleil d'automne.</gray>",
                        "<gold>🔥 +3.0°C pendant 90 secondes</gold>"
                ), ItemRarity.UNCOMMON);
        stack.setAmount(Math.max(1, amount));
        return stack;
    }

    public ItemStack createSummerApple() {
        return createFood(Material.APPLE, FOOD_SUMMER_APPLE, null,
                "<gradient:#76FF03:#FFEA00><bold>🍏 Pomme Juteuse d'Été</bold></gradient>",
                List.of(
                        "<gray>Fraîche et désaltérante.</gray>",
                        "<aqua>🧊 -4.0°C pendant 90 secondes</aqua>",
                        "<aqua>✦ Dissipe la fatigue de canicule</aqua>"
                ), ItemRarity.UNCOMMON);
    }

    public void tagAsIceCatch(ItemStack fish) {
        ItemMeta meta = fish.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(this.foodKey, PersistentDataType.STRING, FOOD_ICE_CATCH);
        meta.displayName(this.mm.deserialize("<gradient:#80D8FF:#2979FF><bold>🐟 Prise des Glaces</bold></gradient>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                this.mm.deserialize("<gray>Pêchée sous la glace en plein hiver.</gray>").decoration(TextDecoration.ITALIC, false),
                this.mm.deserialize("<green>🍖 Nourriture +4 ｜ Saturation +4</green>").decoration(TextDecoration.ITALIC, false)
        ));
        meta.setRarity(ItemRarity.UNCOMMON);
        fish.setItemMeta(meta);
    }

    private ItemStack createFood(Material base, String tag, NamespacedKey itemModel, String nameMini,
                                 List<String> loreMini, ItemRarity rarity) {
        ItemStack stack = new ItemStack(base);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(this.foodKey, PersistentDataType.STRING, tag);
            meta.displayName(this.mm.deserialize(nameMini).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>(loreMini.size());
            for (String line : loreMini) {
                lore.add(this.mm.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            if (itemModel != null) {
                meta.setItemModel(itemModel);
            }
            meta.setRarity(rarity);
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
