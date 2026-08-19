package com.lucasdunn;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

public final class BossCraftingPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final int PHYSICAL_MAX_GIVE_AMOUNT = 2304;

    private final Map<String, MaterialDef> materials =
            new LinkedHashMap<String, MaterialDef>();
    private final Map<String, RecipeDef> recipes =
            new LinkedHashMap<String, RecipeDef>();
    private final Map<String, RegisteredBatch> activeBatches =
            new LinkedHashMap<String, RegisteredBatch>();

    private String guiTitle;
    private int guiSize;
    private FileConfiguration activeConfiguration;
    private FileConfiguration loadingConfiguration;
    private File itemRegistryFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadItemRegistry();

        if (!loadConfiguration(super.getConfig())) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bosscrafting").setExecutor(this);
    }

    @Override
    public void onDisable() {
        saveItemRegistry();
    }

    private void loadItemRegistry() {
        itemRegistryFile = new File(getDataFolder(), "item-registry.yml");
        activeBatches.clear();

        if (!itemRegistryFile.isFile()) {
            return;
        }

        YamlConfiguration registry = YamlConfiguration.loadConfiguration(itemRegistryFile);
        ConfigurationSection items = registry.getConfigurationSection("active-items");
        if (items == null) {
            return;
        }

        for (String itemId : items.getKeys(false)) {
            if (!isUuid(itemId)) {
                continue;
            }

            if (items.isConfigurationSection(itemId)) {
                String materialId = items.getString(itemId + ".material");
                int amount = items.getInt(itemId + ".amount");
                if (materialId != null && amount > 0) {
                    activeBatches.put(
                            itemId, new RegisteredBatch(normalize(materialId), amount));
                }
            } else {
                // Migrate one-unit IDs issued by BossCrafting 1.1.0.
                String materialId = items.getString(itemId);
                if (materialId != null) {
                    activeBatches.put(
                            itemId, new RegisteredBatch(normalize(materialId), 1));
                }
            }
        }
    }

    private boolean saveItemRegistry() {
        YamlConfiguration registry = new YamlConfiguration();
        for (Map.Entry<String, RegisteredBatch> entry : activeBatches.entrySet()) {
            String path = "active-items." + entry.getKey();
            registry.set(path + ".material", entry.getValue().materialId);
            registry.set(path + ".amount", entry.getValue().remainingAmount);
        }

        File temporaryFile = new File(getDataFolder(), "item-registry.yml.tmp");
        try {
            registry.save(temporaryFile);
            try {
                Files.move(
                        temporaryFile.toPath(),
                        itemRegistryFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporaryFile.toPath(),
                        itemRegistryFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            getLogger().severe("Could not save item-registry.yml: " + exception.getMessage());
            if (temporaryFile.isFile() && !temporaryFile.delete()) {
                temporaryFile.deleteOnExit();
            }
            return false;
        }
    }

    private boolean loadConfiguration(FileConfiguration candidate) {
        Map<String, MaterialDef> previousMaterials =
                new LinkedHashMap<String, MaterialDef>(materials);
        Map<String, RecipeDef> previousRecipes =
                new LinkedHashMap<String, RecipeDef>(recipes);
        String previousTitle = guiTitle;
        int previousSize = guiSize;

        loadingConfiguration = candidate;
        boolean valid;
        try {
            valid = loadConfigurationValues();
        } catch (RuntimeException exception) {
            getLogger().severe(
                    "Could not parse the configuration: " + exception.getMessage());
            valid = false;
        } finally {
            loadingConfiguration = null;
        }

        if (valid) {
            activeConfiguration = candidate;
            return true;
        }

        materials.clear();
        materials.putAll(previousMaterials);
        recipes.clear();
        recipes.putAll(previousRecipes);
        guiTitle = previousTitle;
        guiSize = previousSize;
        return false;
    }

    private boolean loadConfigurationValues() {
        materials.clear();
        recipes.clear();

        guiTitle = color(configuration().getString("settings.gui-title", "&8Boss Forge"));
        guiSize = configuration().getInt("settings.gui-size", 27);

        if (!isValidInventorySize(guiSize)) {
            return configurationError(
                    "settings.gui-size must be 9, 18, 27, 36, 45 or 54");
        }

        if (guiTitle.length() > 32) {
            return configurationError(
                    "settings.gui-title must be 32 characters or fewer after colors");
        }

        if (!loadMaterials()) {
            return false;
        }

        return loadRecipes();
    }

    private boolean loadMaterials() {
        ConfigurationSection section = configuration().getConfigurationSection("materials");
        if (section == null) {
            return configurationError("No materials configured");
        }

        for (String configuredId : section.getKeys(false)) {
            ConfigurationSection materialSection = section.getConfigurationSection(configuredId);
            String id = normalize(configuredId);
            ItemStack item = createConfiguredItem(
                    materialSection, "material", "data", "name", "lore");

            if (item == null) {
                return false;
            }

            addConfiguredGlow(item, materialSection.getBoolean("glow"));

            String displayName = color(materialSection.getString("name", configuredId));
            materials.put(id, new MaterialDef(configuredId, displayName, item));
        }

        return true;
    }

    private boolean loadRecipes() {
        ConfigurationSection section = configuration().getConfigurationSection("recipes");
        if (section == null) {
            return configurationError("No recipes configured");
        }

        Set<Integer> usedSlots = new HashSet<Integer>();

        for (String configuredId : section.getKeys(false)) {
            ConfigurationSection recipeSection = section.getConfigurationSection(configuredId);
            int slot = recipeSection.getInt("display-slot", -1);

            if (slot < 0 || slot >= guiSize || !usedSlots.add(slot)) {
                return configurationError(
                        "Invalid or duplicate display-slot for " + configuredId);
            }

            ItemStack icon = createConfiguredItem(
                    recipeSection,
                    "display-material",
                    "display-data",
                    "display-name",
                    "display-lore");

            if (icon == null) {
                return false;
            }

            LinkedHashMap<String, Integer> ingredients =
                    loadIngredients(configuredId, recipeSection);
            if (ingredients == null) {
                return false;
            }

            appendIngredientsToLore(icon, ingredients);

            String displayName = color(
                    recipeSection.getString("display-name", configuredId));
            recipes.put(
                    normalize(configuredId),
                    new RecipeDef(configuredId, displayName, slot, icon, ingredients));
        }

        return true;
    }

    private LinkedHashMap<String, Integer> loadIngredients(
            String recipeId, ConfigurationSection recipeSection) {
        ConfigurationSection section = recipeSection.getConfigurationSection("ingredients");
        if (section == null) {
            configurationError("No ingredients for " + recipeId);
            return null;
        }

        LinkedHashMap<String, Integer> ingredients =
                new LinkedHashMap<String, Integer>();

        for (String configuredId : section.getKeys(false)) {
            String id = normalize(configuredId);
            int amount = section.getInt(configuredId);

            if (!materials.containsKey(id) || amount < 1) {
                configurationError(
                        "Invalid ingredient " + configuredId + " in " + recipeId);
                return null;
            }

            ingredients.put(id, amount);
        }

        return ingredients;
    }

    private boolean isValidInventorySize(int size) {
        return size >= 9 && size <= 54 && size % 9 == 0;
    }

    private boolean configurationError(String error) {
        getLogger().severe(error);
        return false;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createConfiguredItem(
            ConfigurationSection section,
            String materialKey,
            String dataKey,
            String nameKey,
            String loreKey) {
        Material material = Material.matchMaterial(section.getString(materialKey, ""));
        if (material == null) {
            configurationError(
                    "Unknown material "
                            + section.getString(materialKey)
                            + " at "
                            + section.getCurrentPath());
            return null;
        }

        ItemStack item = new ItemStack(
                material, 1, (short) section.getInt(dataKey, 0));
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(color(section.getString(nameKey, material.name())));
        meta.setLore(color(section.getStringList(loreKey)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);

        return item;
    }

    private void addConfiguredGlow(ItemStack item, boolean glow) {
        if (!glow) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }

    private void appendIngredientsToLore(
            ItemStack icon, LinkedHashMap<String, Integer> ingredients) {
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = meta.hasLore()
                ? new ArrayList<String>(meta.getLore())
                : new ArrayList<String>();

        lore.add("");
        for (Map.Entry<String, Integer> ingredient : ingredients.entrySet()) {
            MaterialDef definition = materials.get(ingredient.getKey());
            lore.add(color(
                    "&7- " + ingredient.getValue() + "x " + definition.name));
        }

        meta.setLore(lore);
        icon.setItemMeta(meta);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEliteBossesCommand(PlayerCommandPreprocessEvent event) {
        String rawCommand = event.getMessage().substring(1).trim();
        String[] arguments = rawCommand.split("\\s+");

        if (arguments.length < 2 || !isEliteBossesAlias(arguments[0])) {
            return;
        }

        String subcommand = normalize(arguments[1]);
        if (!subcommand.equals("craft")
                && !subcommand.equals("crafting")
                && !subcommand.equals("materials")) {
            return;
        }

        event.setCancelled(true);
        handleEliteBossesSubcommand(event.getPlayer(), arguments);
    }

    private boolean isEliteBossesAlias(String command) {
        return command.equalsIgnoreCase("eb")
                || command.equalsIgnoreCase("elitebosses")
                || command.equalsIgnoreCase("eliteboss")
                || command.equalsIgnoreCase("eliteb")
                || command.equalsIgnoreCase("eboss")
                || command.equalsIgnoreCase("boss")
                || command.equalsIgnoreCase("bosses");
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length == 0) {
            sendFallbackUsage(sender, label);
            return true;
        }

        String subcommand = normalize(arguments[0]);
        if (subcommand.equals("craft")) {
            return handleFallbackCraft(sender);
        }

        if (subcommand.equals("reload")) {
            return handleReload(sender);
        }

        if (subcommand.equals("open")) {
            return handleAdminOpen(sender, label, arguments);
        }

        if (subcommand.equals("createnpc")) {
            return handleCreateNpc(sender, arguments);
        }

        if (subcommand.equals("materials")) {
            return handleFallbackMaterialGive(sender, label, arguments);
        }

        sendFallbackUsage(sender, label);
        return true;
    }

    private boolean handleFallbackCraft(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }

        Player player = (Player) sender;
        if (!isCraftCommandEnabled()) {
            player.sendMessage(message("craft-command-disabled"));
            return true;
        }

        if (!checkPermission(player, craftPermission())) {
            return true;
        }

        openForge(player);
        return true;
    }

    private boolean handleAdminOpen(
            CommandSender sender, String label, String[] arguments) {
        // Citizens runs the configured NPC command as the console. Never allow a
        // player to invoke this internal command directly, even if they are an op.
        if (sender instanceof Player) {
            sender.sendMessage(message("craft-command-disabled"));
            return true;
        }

        if (!checkPermission(sender, adminPermission())) {
            return true;
        }

        if (arguments.length < 2) {
            sender.sendMessage(color("&cUsage: /" + label + " open <player>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(arguments[1]);
        if (target == null) {
            sender.sendMessage(color("&cPlayer not found."));
            return true;
        }

        openForge(target);
        return true;
    }

    private boolean handleCreateNpc(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }

        if (!checkPermission(sender, adminPermission())) {
            return true;
        }

        String citizensPluginName = configuration().getString(
                "npc.citizens-plugin-name", "Citizens");
        Plugin citizens = Bukkit.getPluginManager().getPlugin(citizensPluginName);
        if (citizens == null || !citizens.isEnabled()) {
            sender.sendMessage(message("citizens-missing"));
            return true;
        }

        String npcName = arguments.length > 1
                ? joinArguments(arguments, 1)
                : configuration().getString("npc.default-name", "&5&l»BossMage«");
        String attachedCommand = configuration().getString(
                "npc.open-command", "bosscrafting open <p>");
        Player player = (Player) sender;

        boolean created = Bukkit.dispatchCommand(player, "npc create " + npcName);
        boolean skinned = created && applyNpcSkin(player);
        boolean attached = skinned && Bukkit.dispatchCommand(
                player, "npc command add -r " + attachedCommand);

        if (!created || !skinned || !attached) {
            player.sendMessage(message("npc-create-failed"));
            return true;
        }

        player.sendMessage(color(format(message("npc-created"), "name", npcName)));
        return true;
    }

    private boolean applyNpcSkin(Player player) {
        String skinUrl = configuration().getString("npc.skin-url", "").trim();
        if (!skinUrl.isEmpty()) {
            return Bukkit.dispatchCommand(player, "npc skin --url " + skinUrl);
        }

        String skinName = configuration().getString("npc.skin-name", "Wizard").trim();
        if (skinName.isEmpty()) {
            return true;
        }
        return Bukkit.dispatchCommand(player, "npc skin " + skinName);
    }

    private boolean handleReload(CommandSender sender) {
        if (!checkPermission(sender, adminPermission())) {
            return true;
        }

        super.reloadConfig();
        if (loadConfiguration(super.getConfig())) {
            sender.sendMessage(message("reloaded"));
        } else {
            sender.sendMessage(message("reload-failed"));
        }
        return true;
    }

    private boolean handleFallbackMaterialGive(
            CommandSender sender, String label, String[] arguments) {
        if (!checkPermission(sender, adminPermission())) {
            return true;
        }

        if (arguments.length < 4 || !arguments[1].equalsIgnoreCase("give")) {
            sendMaterialUsage(sender, "/" + label + " materials give");
            return true;
        }

        int amount = arguments.length > 4 ? parseAmount(arguments[4]) : 1;
        giveConfiguredMaterial(sender, arguments[2], arguments[3], amount);
        return true;
    }

    private void handleEliteBossesSubcommand(Player player, String[] arguments) {
        String subcommand = normalize(arguments[1]);

        if (subcommand.equals("craft")) {
            if (!isCraftCommandEnabled()) {
                player.sendMessage(message("craft-command-disabled"));
                return;
            }

            if (checkPermission(player, craftPermission())) {
                openForge(player);
            }
            return;
        }

        if (subcommand.equals("crafting")
                && arguments.length >= 3
                && arguments[2].equalsIgnoreCase("reload")) {
            handleReload(player);
            return;
        }

        if (!checkPermission(player, adminPermission())) {
            return;
        }

        if (arguments.length < 5 || !arguments[2].equalsIgnoreCase("give")) {
            sendMaterialUsage(player, "/eb materials give");
            return;
        }

        int amount = arguments.length > 5 ? parseAmount(arguments[5]) : 1;
        giveConfiguredMaterial(player, arguments[3], arguments[4], amount);
    }

    private void giveConfiguredMaterial(
            CommandSender sender, String playerName, String materialId, int amount) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(color("&cPlayer not found."));
            return;
        }

        MaterialDef definition = materials.get(normalize(materialId));
        if (definition == null) {
            sender.sendMessage(format(
                    message("unknown-material"), "id", materialId));
            return;
        }

        int maximum = getMaximumGiveAmount();
        if (amount < 1 || amount > maximum) {
            sender.sendMessage(format(
                    message("invalid-amount"), "max", String.valueOf(maximum)));
            return;
        }

        if (!giveBatchItems(target, definition, amount)) {
            sender.sendMessage(message("registry-save-failed"));
            return;
        }
        sender.sendMessage(format(
                message("given"),
                "amount", String.valueOf(amount),
                "item", definition.name,
                "player", target.getName()));

        if (!target.equals(sender)) {
            target.sendMessage(format(
                    message("received"),
                    "amount", String.valueOf(amount),
                    "item", definition.name));
        }
    }

    private int parseAmount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void sendFallbackUsage(CommandSender sender, String label) {
        sender.sendMessage(color(
                "&cUsage: /" + label
                        + " <craft|open|createnpc|materials give|reload>"));
    }

    private void sendMaterialUsage(CommandSender sender, String commandPrefix) {
        sender.sendMessage(color(
                "&cUsage: " + commandPrefix + " <player> <material> [amount]"));
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }

        sender.sendMessage(message("no-permission"));
        return false;
    }

    private void openForge(Player player) {
        ForgeHolder holder = new ForgeHolder();
        Inventory inventory = Bukkit.createInventory(holder, guiSize, guiTitle);
        holder.setInventory(inventory);

        for (RecipeDef recipe : recipes.values()) {
            inventory.setItem(recipe.slot, recipe.icon.clone());
        }

        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onForgeClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ForgeHolder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }

        for (RecipeDef recipe : recipes.values()) {
            if (recipe.slot == event.getRawSlot()) {
                craft((Player) event.getWhoClicked(), recipe);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onForgeDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ForgeHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaterialPlace(BlockPlaceEvent event) {
        if (!isAnyCustomMaterial(event.getItemInHand())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(message("material-cannot-place"));
    }

    private void craft(Player player, RecipeDef recipe) {
        if (!checkPermission(player, craftPermission())) {
            return;
        }

        if (!isEliteBossesAvailable()) {
            player.sendMessage(message("dependency-missing"));
            return;
        }

        List<String> missingIngredients = findMissingIngredients(player, recipe);
        if (!missingIngredients.isEmpty()) {
            player.sendMessage(format(
                    message("missing"), "missing", join(missingIngredients)));
            return;
        }

        List<ItemStack> inventoryBackup = cloneInventory(player.getInventory().getContents());
        Map<String, Integer> consumedBatchAmounts = removeIngredients(player, recipe);

        ItemStack[] inventoryBeforeDelivery = cloneContents(
                player.getInventory().getContents());
        if (!hasEmptyInventorySlot(inventoryBeforeDelivery)) {
            restoreInventory(player, inventoryBackup);
            player.sendMessage(message("inventory-space"));
            return;
        }

        Map<String, RegisteredBatch> previousBatchStates =
                consumeRegisteredAmounts(consumedBatchAmounts);
        if (!saveItemRegistry()) {
            restoreBatchStates(previousBatchStates);
            restoreInventory(player, inventoryBackup);
            player.sendMessage(message("registry-save-failed"));
            return;
        }

        String outputCommand = format(
                configuration().getString(
                        "settings.output-command", "eb give {player} {boss}"),
                "player", player.getName(),
                "boss", recipe.id);

        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), outputCommand);
        } catch (RuntimeException exception) {
            restoreBatchStates(previousBatchStates);
            saveItemRegistry();
            restoreInventory(player, inventoryBackup);
            player.sendMessage(message("output-failed"));
            getLogger().severe(
                    "EliteBosses output command failed for "
                            + player.getName()
                            + ": "
                            + exception.getMessage());
            return;
        }

        if (!hasInventoryIncrease(
                inventoryBeforeDelivery, player.getInventory().getContents())) {
            restoreBatchStates(previousBatchStates);
            saveItemRegistry();
            restoreInventory(player, inventoryBackup);
            player.sendMessage(message("output-failed"));
            return;
        }

        player.updateInventory();
        player.sendMessage(format(message("crafted"), "boss", recipe.name));

        if (configuration().getBoolean("settings.close-after-craft")) {
            player.closeInventory();
        }
    }

    private boolean isEliteBossesAvailable() {
        if (!configuration().getBoolean("settings.require-elitebosses", true)) {
            return true;
        }

        String pluginName = configuration().getString(
                "settings.elitebosses-plugin-name", "EliteBosses");
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        return plugin != null && plugin.isEnabled();
    }

    private List<String> findMissingIngredients(Player player, RecipeDef recipe) {
        List<String> missing = new ArrayList<String>();

        for (Map.Entry<String, Integer> ingredient : recipe.ingredients.entrySet()) {
            int available = countMaterial(player, ingredient.getKey());
            int required = ingredient.getValue();

            if (available < required) {
                MaterialDef definition = materials.get(ingredient.getKey());
                missing.add((required - available) + "x " + definition.name);
            }
        }

        return missing;
    }

    private Map<String, Integer> removeIngredients(Player player, RecipeDef recipe) {
        Map<String, Integer> consumedBatchAmounts =
                new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Integer> ingredient : recipe.ingredients.entrySet()) {
            removeMaterial(
                    player,
                    ingredient.getKey(),
                    ingredient.getValue(),
                    consumedBatchAmounts);
        }
        return consumedBatchAmounts;
    }

    private Map<String, RegisteredBatch> consumeRegisteredAmounts(
            Map<String, Integer> consumedAmounts) {
        Map<String, RegisteredBatch> previousStates =
                new LinkedHashMap<String, RegisteredBatch>();

        for (Map.Entry<String, Integer> consumed : consumedAmounts.entrySet()) {
            RegisteredBatch batch = activeBatches.get(consumed.getKey());
            if (batch != null) {
                previousStates.put(consumed.getKey(), batch.copy());
                batch.consume(consumed.getValue());
                if (batch.remainingAmount <= 0) {
                    activeBatches.remove(consumed.getKey());
                }
            }
        }
        return previousStates;
    }

    private void restoreBatchStates(Map<String, RegisteredBatch> previousStates) {
        for (Map.Entry<String, RegisteredBatch> entry : previousStates.entrySet()) {
            activeBatches.put(entry.getKey(), entry.getValue());
        }
    }

    private boolean isCustomMaterial(ItemStack item, String materialId) {
        MaterialDef definition = materials.get(materialId);
        if (definition == null
                || item == null
                || item.getType() == Material.AIR
                || !materialId.equals(HiddenItemData.getMaterialId(item))) {
            return false;
        }

        String instanceId = getInstanceId(item);
        RegisteredBatch batch = instanceId == null ? null : activeBatches.get(instanceId);
        if (batch == null
                || batch.remainingAmount < 1
                || !materialId.equals(batch.materialId)) {
            return false;
        }

        ItemStack singleItem = HiddenItemData.withoutBossCraftingData(item);
        singleItem.setAmount(1);
        return singleItem.isSimilar(definition.item);
    }

    private String getInstanceId(ItemStack item) {
        String instanceId = HiddenItemData.getBatchId(item);
        return instanceId != null && isUuid(instanceId) ? instanceId : null;
    }

    private boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isAnyCustomMaterial(ItemStack item) {
        for (String materialId : materials.keySet()) {
            if (isCustomMaterial(item, materialId)) {
                return true;
            }
        }
        return false;
    }

    private int countMaterial(Player player, String materialId) {
        Map<String, Integer> physicalAmounts = new HashMap<String, Integer>();

        for (ItemStack item : player.getInventory().getContents()) {
            String instanceId = getInstanceId(item);
            if (isCustomMaterial(item, materialId)) {
                Integer current = physicalAmounts.get(instanceId);
                physicalAmounts.put(
                        instanceId, (current == null ? 0 : current) + item.getAmount());
            }
        }

        int total = 0;
        for (Map.Entry<String, Integer> physical : physicalAmounts.entrySet()) {
            RegisteredBatch batch = activeBatches.get(physical.getKey());
            total += batch.spendableAmount(physical.getValue());
        }
        return total;
    }

    private void removeMaterial(
            Player player,
            String materialId,
            int amount,
            Map<String, Integer> consumedBatchAmounts) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = amount;

        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            String instanceId = getInstanceId(item);
            if (!isCustomMaterial(item, materialId)) {
                continue;
            }

            RegisteredBatch batch = activeBatches.get(instanceId);
            Integer alreadyConsumed = consumedBatchAmounts.get(instanceId);
            int available = batch.remainingAmount
                    - (alreadyConsumed == null ? 0 : alreadyConsumed);
            int removed = Math.min(remaining, Math.min(item.getAmount(), available));
            if (removed <= 0) {
                continue;
            }

            item.setAmount(item.getAmount() - removed);
            remaining -= removed;
            consumedBatchAmounts.put(
                    instanceId, (alreadyConsumed == null ? 0 : alreadyConsumed) + removed);

            if (item.getAmount() <= 0) {
                contents[slot] = null;
            }
        }

        player.getInventory().setContents(contents);
    }

    private List<ItemStack> cloneInventory(ItemStack[] contents) {
        List<ItemStack> copy = new ArrayList<ItemStack>();
        for (ItemStack item : contents) {
            copy.add(item == null ? null : item.clone());
        }
        return copy;
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private boolean hasEmptyInventorySlot(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInventoryIncrease(ItemStack[] before, ItemStack[] after) {
        for (ItemStack candidate : after) {
            if (candidate == null || candidate.getType() == Material.AIR) {
                continue;
            }

            if (countSimilar(after, candidate) > countSimilar(before, candidate)) {
                return true;
            }
        }
        return false;
    }

    private int countSimilar(ItemStack[] contents, ItemStack candidate) {
        int amount = 0;
        for (ItemStack item : contents) {
            if (item != null && item.isSimilar(candidate)) {
                amount += item.getAmount();
            }
        }
        return amount;
    }

    private void restoreInventory(Player player, List<ItemStack> backup) {
        player.getInventory().setContents(
                backup.toArray(new ItemStack[backup.size()]));
        player.updateInventory();
    }

    private boolean giveBatchItems(Player player, MaterialDef definition, int amount) {
        List<ItemStack> issuedItems = new ArrayList<ItemStack>();
        List<String> issuedBatchIds = new ArrayList<String>();
        int remaining = amount;

        while (remaining > 0) {
            String itemId;
            do {
                itemId = UUID.randomUUID().toString();
            } while (activeBatches.containsKey(itemId));

            ItemStack item = definition.item.clone();
            int stackAmount = Math.min(item.getMaxStackSize(), remaining);
            item.setAmount(stackAmount);
            remaining -= stackAmount;

            item = HiddenItemData.apply(item, normalize(definition.id), itemId);

            activeBatches.put(
                    itemId,
                    new RegisteredBatch(normalize(definition.id), stackAmount));
            issuedBatchIds.add(itemId);
            issuedItems.add(item);
        }

        if (!saveItemRegistry()) {
            for (String itemId : issuedBatchIds) {
                activeBatches.remove(itemId);
            }
            return false;
        }

        boolean droppedItems = false;

        for (ItemStack item : issuedItems) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack overflowItem : overflow.values()) {
                droppedItems = true;
                player.getWorld().dropItemNaturally(player.getLocation(), overflowItem);
            }
        }

        if (droppedItems) {
            player.sendMessage(message("inventory-full"));
        }
        return true;
    }

    private int getMaximumGiveAmount() {
        int configuredMaximum = configuration().getInt("settings.max-give-amount", 64);
        return Math.max(1, Math.min(PHYSICAL_MAX_GIVE_AMOUNT, configuredMaximum));
    }

    private String craftPermission() {
        return configuredPermission("permissions.craft", "bosscrafting.craft");
    }

    private String adminPermission() {
        return configuredPermission("permissions.admin", "bosscrafting.admin");
    }

    private String configuredPermission(String path, String defaultPermission) {
        String permission = configuration().getString(path, defaultPermission);
        if (permission == null || permission.trim().isEmpty()) {
            return defaultPermission;
        }
        return permission.trim();
    }

    private boolean isCraftCommandEnabled() {
        return configuration().getBoolean("settings.craft-command-enabled", false);
    }

    private String message(String key) {
        return color(configuration().getString("messages.prefix", ""))
                + color(configuration().getString("messages." + key, key));
    }

    private FileConfiguration configuration() {
        if (loadingConfiguration != null) {
            return loadingConfiguration;
        }
        return activeConfiguration != null ? activeConfiguration : super.getConfig();
    }

    private String format(String text, String... replacements) {
        String formatted = text;
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            formatted = formatted.replace(
                    "{" + replacements[index] + "}", replacements[index + 1]);
        }
        return formatted;
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(color("&7, &f"));
            }
            result.append(value);
        }
        return result.toString();
    }

    private String joinArguments(String[] arguments, int startIndex) {
        StringBuilder result = new StringBuilder();
        for (int index = startIndex; index < arguments.length; index++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(arguments[index]);
        }
        return result.toString();
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ENGLISH);
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    private List<String> color(List<String> values) {
        List<String> colored = new ArrayList<String>();
        for (String value : values) {
            colored.add(color(value));
        }
        return colored;
    }

    private static final class ForgeHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
