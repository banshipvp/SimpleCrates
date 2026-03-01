package local.simplecrates;

import local.simpleeconomy.SimpleEconomyPlugin;
import local.simplekits.GKit;
import local.simplekits.GKitGemManager;
import local.simplekits.SimpleKitsPlugin;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.function.Consumer;

public class CrateManager {

    private final SimpleCratesPlugin plugin;
    private final Economy economy;
    private final Random random = new Random();
    private final NamespacedKey crateKey;
    private final NamespacedKey rankVoucherKey;
    private static final List<String> SERVER_RANKS = List.of("default", "scout", "militant", "tactician", "warlord", "sovereign");

    public CrateManager(SimpleCratesPlugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.crateKey = new NamespacedKey(plugin, "crate_tier");
        this.rankVoucherKey = new NamespacedKey(plugin, "rank_voucher");
    }

    public ItemStack createCrateItem(CrateTier tier, int amount) {
        ItemStack crate = new ItemStack(Material.CHEST, Math.max(1, amount));
        ItemMeta meta = crate.getItemMeta();

        meta.setDisplayName(tier.getDisplayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7Right-click to open this crate");
        lore.add("§7Left-click to preview rewards");
        lore.add("§7");
        lore.add("§eTier: §f" + tier.getId());
        lore.add("§aMoney Range: §e$" + format(tier.getMinMoney()) + " - $" + format(tier.getMaxMoney()));
        lore.add("§7Higher tiers = better rewards");
        lore.add("§7");
        lore.add("§6✦ Single Use ✦");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.STRING, tier.getId());
        crate.setItemMeta(meta);
        return crate;
    }

    public Inventory createPreviewInventory(CrateTier tier) {
        List<RewardOption> pool = buildPool(tier);
        int size = pool.size() <= 27 ? 27 : 54;
        Inventory inventory = Bukkit.createInventory(null, size, previewTitle(tier));

        int totalWeight = pool.stream().mapToInt(RewardOption::weight).sum();
        for (int i = 0; i < pool.size() && i < size; i++) {
            RewardOption option = pool.get(i);
            RewardDraw reward = option.reward().create();

            ItemStack preview = reward.preview().clone();
            ItemMeta meta = preview.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
                if (!lore.isEmpty()) {
                    lore.add("§7");
                }
                lore.add("§eReward: §f" + reward.displayName());
                double chance = (option.weight() * 100.0) / totalWeight;
                lore.add("§7Chance: §f" + String.format(Locale.ROOT, "%.1f", chance) + "%");
                meta.setLore(lore);
                preview.setItemMeta(meta);
            }

            inventory.setItem(i, preview);
        }

        return inventory;
    }

    public String previewTitle(CrateTier tier) {
        return "§b§lCrate Preview: §f" + pretty(tier.getId());
    }

    public boolean isPreviewTitle(String title) {
        return title != null && title.startsWith("§b§lCrate Preview: §f");
    }

    public boolean isCrate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(crateKey, PersistentDataType.STRING);
    }

    public CrateTier getTier(ItemStack item) {
        if (!isCrate(item)) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(crateKey, PersistentDataType.STRING);
        return id == null ? null : CrateTier.from(id);
    }

    public void openCrate(Player player, CrateTier tier) {
        grantBaseMoney(player, tier);

        int rolls = switch (tier) {
            case SIMPLE -> 2;
            case UNIQUE -> 2;
            case ELITE -> 3;
            case ULTIMATE -> 3;
            case LEGENDARY -> 4;
            case GODLY -> 5;
        };

        for (int i = 0; i < rolls; i++) {
            RewardDraw reward = rollReward(tier);
            reward.grant().accept(player);
            player.sendMessage("§eReward: §f" + reward.displayName());
        }

        player.sendMessage("§6Opened: " + tier.getDisplayName());
    }

    public int grantBaseMoney(Player player, CrateTier tier) {
        int money = randomRange(tier.getMinMoney(), tier.getMaxMoney());
        economy.depositPlayer(player, money);
        player.sendMessage("§a+$" + format(money) + " §7from " + tier.getDisplayName());
        return money;
    }

    public List<RewardDraw> rollRewardChoices(CrateTier tier, int count) {
        List<RewardDraw> rewards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rewards.add(rollReward(tier));
        }
        return rewards;
    }

    private RewardDraw rollReward(CrateTier tier) {
        List<RewardOption> pool = buildPool(tier);
        int totalWeight = pool.stream().mapToInt(r -> r.weight).sum();
        int pick = random.nextInt(totalWeight) + 1;

        int cursor = 0;
        for (RewardOption option : pool) {
            cursor += option.weight;
            if (pick <= cursor) {
                return option.reward.create();
            }
        }

        return itemReward(new ItemStack(Material.BREAD, 1), "Bread").create();
    }

    private List<RewardOption> buildPool(CrateTier tier) {
        List<RewardOption> list = new ArrayList<>();

        switch (tier) {
            case SIMPLE -> {
                list.add(weight(45, itemReward(simpleGear())));
                list.add(weight(30, itemReward(new ItemStack(Material.EXPERIENCE_BOTTLE, 16), "§bXP Bottles x16")));
                list.add(weight(15, itemReward(createRankNote("scout", 1), "§aScout Rank Note")));
                list.add(weight(10, itemReward(createRandomSpawnerByTier(CrateTier.SIMPLE), "§fRandom Simple Spawner")));
            }
            case UNIQUE -> {
                list.add(weight(35, itemReward(midGear(Material.IRON_SWORD, Material.IRON_CHESTPLATE, "§a", "Unique"))));
                list.add(weight(35, itemReward(createRandomSpawnerByTier(CrateTier.UNIQUE), "§aRandom Unique Spawner")));
                list.add(weight(15, itemReward(createRankVoucher("militant"), "§eMilitant Rank Voucher")));
                list.add(weight(15, itemReward(createXPBottle(1500), "§bXP Bottle 1500")));
            }
            case ELITE -> {
                list.add(weight(25, itemReward(midGear(Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE, "§e", "Elite"))));
                list.add(weight(20, itemReward(createRandomSpawnerByTier(CrateTier.ELITE), "§eRandom Elite Spawner")));
                list.add(weight(20, itemReward(createGKitGem(), "§dGKit Gem")));
                list.add(weight(15, itemReward(createRandomGKitGear(), "§dRandom GKit Gear")));
                list.add(weight(15, itemReward(createRankVoucher("tactician"), "§6Tactician Voucher")));
                list.add(weight(20, itemReward(createCrateItem(CrateTier.UNIQUE, 1), "§aUnique Crate")));
            }
            case ULTIMATE -> {
                list.add(weight(25, itemReward(goodGear())));
                list.add(weight(20, itemReward(createRandomSpawnerByTier(CrateTier.ULTIMATE), "§5Random Ultimate Spawner")));
                list.add(weight(20, itemReward(createGKitGem(), "§dGKit Gem")));
                list.add(weight(18, itemReward(createRandomGKitGear(), "§dRandom GKit Gear")));
                list.add(weight(20, itemReward(createRankVoucher("warlord"), "§5Warlord Voucher")));
                list.add(weight(15, itemReward(createCrateItem(CrateTier.ELITE, 1), "§bElite Crate")));
            }
            case LEGENDARY -> {
                list.add(weight(28, itemReward(greatGear())));
                list.add(weight(20, itemReward(createRandomSpawnerByTier(CrateTier.LEGENDARY), "§6Random Legendary Spawner")));
                list.add(weight(17, itemReward(createGKitGem(), "§dGKit Gem")));
                list.add(weight(20, itemReward(createRandomGKitGear(), "§dRandom GKit Gear")));
                list.add(weight(15, itemReward(createRankVoucher("sovereign"), "§cSovereign Voucher")));
                list.add(weight(20, itemReward(createCrateItem(CrateTier.ULTIMATE, 1), "§5Ultimate Crate")));
            }
            case GODLY -> {
                list.add(weight(32, itemReward(godGear())));
                list.add(weight(18, itemReward(createRandomSpawnerByTier(CrateTier.GODLY), "§dRandom Godly Spawner")));
                list.add(weight(18, itemReward(createGKitGem(), "§dGKit Gem")));
                list.add(weight(22, itemReward(createRandomGKitGear(), "§dRandom GKit Gear")));
                list.add(weight(12, itemReward(createRankVoucher("sovereign"), "§cSovereign Voucher")));
                list.add(weight(20, itemReward(createCrateItem(CrateTier.LEGENDARY, 1), "§6Legendary Crate")));
            }
        }

        return list;
    }

    private RewardOption weight(int weight, Reward reward) {
        return new RewardOption(weight, reward);
    }

    private Reward itemReward(ItemStack item, String display) {
        ItemStack preview = item.clone();
        return () -> new RewardDraw(preview, display, player -> giveItem(player, item.clone()));
    }

    private Reward itemReward(List<ItemStack> items) {
        List<ItemStack> previews = new ArrayList<>();
        for (ItemStack item : items) {
            previews.add(item.clone());
        }

        ItemStack display = previews.isEmpty() ? new ItemStack(Material.CHEST) : previews.get(0).clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7Contains a gear bundle");
            lore.add("§7Items: §f" + previews.size());
            meta.setLore(lore);
            display.setItemMeta(meta);
        }

        return () -> new RewardDraw(display, "Gear bundle", player -> {
            for (ItemStack item : previews) {
                giveItem(player, item.clone());
            }
        });
    }

    private void giveItem(Player player, ItemStack item) {
        HashMap<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (!left.isEmpty()) {
            for (ItemStack drop : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }

    public ItemStack createRankVoucher(String rank) {
        String normalizedRank = rank.toLowerCase(Locale.ROOT);
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String color = getRankColor(normalizedRank);
        String prettyRank = pretty(normalizedRank);
        meta.setDisplayName(color + "§l✦ " + prettyRank + " Rank Voucher §l✦");
        meta.setLore(List.of(
            "§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
            "§7Redeems rank: " + color + prettyRank,
            "§7",
            "§eRight-click §7to apply voucher",
            "§7Tradeable item",
            "§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
        ));
        meta.getPersistentDataContainer().set(rankVoucherKey, PersistentDataType.STRING, normalizedRank);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRankVoucher(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(rankVoucherKey, PersistentDataType.STRING);
    }

    public String getVoucherRank(ItemStack item) {
        if (!isRankVoucher(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(rankVoucherKey, PersistentDataType.STRING);
    }

    public boolean applyRankVoucher(Player player, String rank) {
        String targetRank = rank.toLowerCase(Locale.ROOT);
        if (!isValidRank(targetRank)) return false;

        String currentRank = getCurrentRank(player);
        int currentIndex = SERVER_RANKS.indexOf(currentRank);
        int targetIndex = SERVER_RANKS.indexOf(targetRank);

        if (targetIndex <= currentIndex) {
            player.sendMessage("§cYou cannot apply this voucher.");
            player.sendMessage("§7Current rank: " + getRankColor(currentRank) + pretty(currentRank));
            player.sendMessage("§7You can only redeem vouchers for ranks above your current rank.");
            return false;
        }

        String command = "lp user " + player.getName() + " parent add " + targetRank;
        boolean ok = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        if (ok) {
            player.sendMessage("§aRank applied: " + getRankColor(targetRank) + pretty(targetRank));
        } else {
            player.sendMessage("§cFailed to apply rank. Check LuckPerms / console.");
        }
        return ok;
    }

    public boolean isValidRank(String rank) {
        return SERVER_RANKS.contains(rank.toLowerCase(Locale.ROOT));
    }

    public List<String> getServerRanks() {
        return SERVER_RANKS;
    }

    private String getCurrentRank(Player player) {
        for (int i = SERVER_RANKS.size() - 1; i >= 0; i--) {
            String rank = SERVER_RANKS.get(i);
            if (rank.equals("default")) continue;

            if (player.hasPermission("group." + rank)
                    || player.hasPermission("simplekits.rank." + rank)
                    || player.hasPermission("rank." + rank)) {
                return rank;
            }
        }
        return "default";
    }

    private String getRankColor(String rank) {
        return switch (rank.toLowerCase(Locale.ROOT)) {
            case "sovereign" -> "§c";
            case "warlord" -> "§5";
            case "tactician" -> "§6";
            case "militant" -> "§e";
            case "scout" -> "§a";
            default -> "§7";
        };
    }

    private ItemStack createRankNote(String rank, int count) {
        ItemStack item = new ItemStack(Material.PAPER, count);
        ItemMeta meta = item.getItemMeta();
        String normalizedRank = rank.toLowerCase(Locale.ROOT);
        String color = getRankColor(normalizedRank);
        String prettyRank = pretty(normalizedRank);
        meta.setDisplayName(color + "§l✦ " + prettyRank + " Rank Note §l✦");
        meta.setLore(List.of(
                "§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                "§7Contains rank: " + color + prettyRank,
                "§7",
                "§eRedeemable rank item",
                "§7Tradeable note",
                "§8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createXPBottle(int amount) {
        if (plugin.getServer().getPluginManager().getPlugin("SimpleEconomy") instanceof SimpleEconomyPlugin simpleEconomy) {
            return simpleEconomy.getXPBottleManager().createXPBottle(amount);
        }
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE, 1);
        ItemMeta meta = bottle.getItemMeta();
        meta.setDisplayName("§b§lXP Bottle - " + amount + " XP");
        meta.setLore(List.of("§7Right-click to consume", "§7Restores §b" + amount + "§7 experience"));
        bottle.setItemMeta(meta);
        return bottle;
    }

    private ItemStack createGKitGem() {
        if (plugin.getServer().getPluginManager().getPlugin("SimpleKits") instanceof SimpleKitsPlugin kitsPlugin) {
            GKitGemManager gemManager = kitsPlugin.getGKitGemManager();
            List<GKit> all = new ArrayList<>(kitsPlugin.getKitManager().getAllKits());
            if (!all.isEmpty()) {
                GKit randomKit = all.get(random.nextInt(all.size()));
                ItemStack gem = gemManager.getGem(randomKit.getName());
                if (gem != null) {
                    return gem;
                }
            }
        }

        ItemStack fallback = new ItemStack(Material.DIAMOND);
        ItemMeta meta = fallback.getItemMeta();
        meta.setDisplayName("§d§lRandom GKit Gem");
        meta.setLore(List.of("§7Fallback gem (SimpleKits not loaded)"));
        fallback.setItemMeta(meta);
        return fallback;
    }

    private ItemStack createRandomGKitGear() {
        String kitName = "random";
        String kitDisplay = "§dRandom GKit";

        if (plugin.getServer().getPluginManager().getPlugin("SimpleKits") instanceof SimpleKitsPlugin kitsPlugin) {
            List<GKit> all = new ArrayList<>(kitsPlugin.getKitManager().getAllKits());
            if (!all.isEmpty()) {
                GKit randomKit = all.get(random.nextInt(all.size()));
                kitName = randomKit.getName().toLowerCase(Locale.ROOT);
                kitDisplay = randomKit.getDisplayName();
            }
        }

        Material[] choices = {
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS,
                Material.DIAMOND_SWORD,
                Material.DIAMOND_PICKAXE
        };
        Material material = choices[random.nextInt(choices.length)];
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(kitDisplay + " §fGear");
            item.setItemMeta(meta);
        }

        applyBaseGearEnchants(item, material);
        List<Enchantment> bonus = applyRandomKitBonusEnchants(item, material, kitName);

        ItemMeta updatedMeta = item.getItemMeta();
        if (updatedMeta != null) {
            List<String> lore = new ArrayList<>();
            for (Enchantment enchantment : bonus) {
                lore.add(randomLoreForEnchant(enchantment));
            }
            if (!lore.isEmpty()) {
                updatedMeta.setLore(lore);
                item.setItemMeta(updatedMeta);
            }
        }

        return item;
    }

    private void applyBaseGearEnchants(ItemStack item, Material material) {
        if (material.name().contains("HELMET") || material.name().contains("CHESTPLATE") || material.name().contains("LEGGINGS") || material.name().contains("BOOTS")) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4 + random.nextInt(3));
            item.addUnsafeEnchantment(Enchantment.DURABILITY, 3 + random.nextInt(2));
            return;
        }

        if (material == Material.DIAMOND_SWORD) {
            item.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 4 + random.nextInt(3));
            item.addUnsafeEnchantment(Enchantment.DURABILITY, 3 + random.nextInt(2));
            return;
        }

        if (material == Material.DIAMOND_PICKAXE) {
            item.addUnsafeEnchantment(Enchantment.DIG_SPEED, 5 + random.nextInt(2));
            item.addUnsafeEnchantment(Enchantment.LOOT_BONUS_BLOCKS, 2 + random.nextInt(2));
            item.addUnsafeEnchantment(Enchantment.DURABILITY, 3 + random.nextInt(2));
        }
    }

    private List<Enchantment> applyRandomKitBonusEnchants(ItemStack item, Material material, String kitName) {
        List<Enchantment> pool = new ArrayList<>();

        if (material == Material.DIAMOND_SWORD) {
            pool.add(Enchantment.FIRE_ASPECT);
            pool.add(Enchantment.KNOCKBACK);
            pool.add(Enchantment.SWEEPING_EDGE);
        } else if (material == Material.DIAMOND_PICKAXE) {
            pool.add(Enchantment.MENDING);
            pool.add(Enchantment.SILK_TOUCH);
        } else if (material == Material.DIAMOND_HELMET) {
            pool.add(Enchantment.OXYGEN);
            pool.add(Enchantment.WATER_WORKER);
            pool.add(Enchantment.PROTECTION_PROJECTILE);
        } else if (material == Material.DIAMOND_CHESTPLATE) {
            pool.add(Enchantment.THORNS);
            pool.add(Enchantment.PROTECTION_FIRE);
            pool.add(Enchantment.PROTECTION_EXPLOSIONS);
        } else if (material == Material.DIAMOND_LEGGINGS) {
            pool.add(Enchantment.SWIFT_SNEAK);
            pool.add(Enchantment.PROTECTION_EXPLOSIONS);
        } else if (material == Material.DIAMOND_BOOTS) {
            pool.add(Enchantment.PROTECTION_FALL);
            pool.add(Enchantment.DEPTH_STRIDER);
            pool.add(Enchantment.FROST_WALKER);
            pool.add(Enchantment.SOUL_SPEED);
        }

        switch (kitName) {
            case "fire" -> {
                pool.add(Enchantment.FIRE_ASPECT);
                pool.add(Enchantment.PROTECTION_FIRE);
            }
            case "ice" -> pool.add(Enchantment.FROST_WALKER);
            case "assassin" -> {
                pool.add(Enchantment.PROTECTION_FALL);
                pool.add(Enchantment.SWIFT_SNEAK);
            }
            case "tank" -> {
                pool.add(Enchantment.THORNS);
                pool.add(Enchantment.PROTECTION_EXPLOSIONS);
            }
            case "miner" -> {
                if (material == Material.DIAMOND_PICKAXE) {
                    pool.add(Enchantment.MENDING);
                }
            }
            default -> {
            }
        }

        Collections.shuffle(pool, random);
        int count = 1 + random.nextInt(2);
        List<Enchantment> applied = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            Enchantment enchantment = pool.get(i);
            if (item.getEnchantments().containsKey(enchantment)) {
                continue;
            }
            item.addUnsafeEnchantment(enchantment, randomLevel(enchantment));
            applied.add(enchantment);
        }

        return applied;
    }

    private int randomLevel(Enchantment enchantment) {
        return switch (enchantment.getKey().getKey()) {
            case "fire_aspect", "knockback", "sweeping", "frost_walker", "swift_sneak" -> 1 + random.nextInt(2);
            case "thorns", "protection", "fire_protection", "blast_protection", "projectile_protection", "feather_falling", "depth_strider", "soul_speed", "respiration" -> 1 + random.nextInt(3);
            default -> 1;
        };
    }

    private String randomLoreForEnchant(Enchantment enchantment) {
        return switch (enchantment.getKey().getKey()) {
            case "fire_aspect" -> pick("§dBlazing edge", "§dFlamekiss", "§dInferno touch");
            case "knockback" -> pick("§dImpact burst", "§dForce pulse", "§dShockwave");
            case "sweeping" -> pick("§dCleave arc", "§dWide slash", "§dCrowd breaker");
            case "thorns" -> pick("§dReactive spikes", "§dBarbed guard", "§dPain mirror");
            case "fire_protection" -> pick("§dHeat ward", "§dAsh shield", "§dInferno guard");
            case "blast_protection" -> pick("§dBlast shell", "§dDetonation ward", "§dImpact guard");
            case "projectile_protection" -> pick("§dArrow ward", "§dRanged guard", "§dVolley shield");
            case "protection" -> pick("§dHardened weave", "§dGuardian layer", "§dAegis field");
            case "respiration" -> pick("§dDeep breath", "§dAqua lungs", "§dOcean lungs");
            case "aqua_affinity" -> pick("§dTidal focus", "§dWave focus", "§dAqua control");
            case "swift_sneak" -> pick("§dShadow step", "§dSilent pace", "§dGhost stride");
            case "feather_falling" -> pick("§dSoftfall", "§dSky cushion", "§dGravity dampen");
            case "depth_strider" -> pick("§dTide runner", "§dWavewalk", "§dAqua stride");
            case "frost_walker" -> pick("§dIce tread", "§dGlacial step", "§dFrost path");
            case "soul_speed" -> pick("§dSoul rush", "§dSpirit stride", "§dNether sprint");
            case "mending" -> pick("§dSelf repair", "§dReforged bond", "§dEverlasting edge");
            case "silk_touch" -> pick("§dSilken harvest", "§dGentle hand", "§dPreserve touch");
            default -> "§dEnchanted trait";
        };
    }

    private String pick(String... values) {
        return values[random.nextInt(values.length)];
    }

    private ItemStack createSpawner(EntityType type) {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawner.getItemMeta();
        if (meta instanceof BlockStateMeta blockStateMeta) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof CreatureSpawner creatureSpawner) {
                creatureSpawner.setSpawnedType(type);
                blockStateMeta.setBlockState(creatureSpawner);
            }
            blockStateMeta.setDisplayName("§6" + pretty(type.name()) + " Spawner");
            blockStateMeta.setLore(List.of("§7Place to spawn §f" + pretty(type.name())));
            spawner.setItemMeta(blockStateMeta);
        }
        return spawner;
    }

    private ItemStack createRandomSpawnerByTier(CrateTier tier) {
        List<SpawnerChoice> pool = getSpawnerPoolByTier(tier);
        if (pool.isEmpty()) {
            return createSpawner(EntityType.PIG);
        }

        int total = pool.stream().mapToInt(SpawnerChoice::weight).sum();
        int pick = random.nextInt(total) + 1;
        int cursor = 0;
        for (SpawnerChoice choice : pool) {
            cursor += choice.weight();
            if (pick <= cursor) {
                return createSpawner(choice.type());
            }
        }

        return createSpawner(pool.get(0).type());
    }

    private List<SpawnerChoice> getSpawnerPoolByTier(CrateTier tier) {
        return switch (tier) {
            case SIMPLE -> List.of(
                    new SpawnerChoice(EntityType.CHICKEN, 30),
                    new SpawnerChoice(EntityType.COW, 25),
                    new SpawnerChoice(EntityType.SHEEP, 20),
                    new SpawnerChoice(EntityType.PIG, 15),
                    new SpawnerChoice(EntityType.RABBIT, 10)
            );
            case UNIQUE -> List.of(
                    new SpawnerChoice(EntityType.CHICKEN, 20),
                    new SpawnerChoice(EntityType.COW, 20),
                    new SpawnerChoice(EntityType.SHEEP, 18),
                    new SpawnerChoice(EntityType.PIG, 12),
                    new SpawnerChoice(EntityType.BLAZE, 12),
                    new SpawnerChoice(EntityType.ENDERMAN, 10),
                    new SpawnerChoice(EntityType.CREEPER, 8)
            );
            case ELITE -> List.of(
                    new SpawnerChoice(EntityType.BLAZE, 20),
                    new SpawnerChoice(EntityType.ENDERMAN, 20),
                    new SpawnerChoice(EntityType.CREEPER, 15),
                    new SpawnerChoice(EntityType.GHAST, 12),
                    new SpawnerChoice(EntityType.GUARDIAN, 10),
                    new SpawnerChoice(EntityType.MAGMA_CUBE, 10),
                    new SpawnerChoice(EntityType.IRON_GOLEM, 8),
                    new SpawnerChoice(EntityType.MUSHROOM_COW, 5)
            );
            case ULTIMATE -> List.of(
                    new SpawnerChoice(EntityType.ENDERMAN, 20),
                    new SpawnerChoice(EntityType.GHAST, 18),
                    new SpawnerChoice(EntityType.BLAZE, 15),
                    new SpawnerChoice(EntityType.IRON_GOLEM, 14),
                    new SpawnerChoice(EntityType.MUSHROOM_COW, 10),
                    new SpawnerChoice(EntityType.DOLPHIN, 10),
                    new SpawnerChoice(EntityType.WARDEN, 5),
                    new SpawnerChoice(EntityType.GUARDIAN, 8)
            );
            case LEGENDARY -> List.of(
                    new SpawnerChoice(EntityType.GHAST, 18),
                    new SpawnerChoice(EntityType.IRON_GOLEM, 18),
                    new SpawnerChoice(EntityType.MUSHROOM_COW, 16),
                    new SpawnerChoice(EntityType.DOLPHIN, 14),
                    new SpawnerChoice(EntityType.WARDEN, 10),
                    new SpawnerChoice(EntityType.ENDERMAN, 10),
                    new SpawnerChoice(EntityType.GUARDIAN, 8),
                    new SpawnerChoice(EntityType.BLAZE, 6)
            );
            case GODLY -> List.of(
                    new SpawnerChoice(EntityType.WARDEN, 18),
                    new SpawnerChoice(EntityType.IRON_GOLEM, 16),
                    new SpawnerChoice(EntityType.GHAST, 14),
                    new SpawnerChoice(EntityType.MUSHROOM_COW, 14),
                    new SpawnerChoice(EntityType.DOLPHIN, 12),
                    new SpawnerChoice(EntityType.GUARDIAN, 10),
                    new SpawnerChoice(EntityType.ENDERMAN, 10),
                    new SpawnerChoice(EntityType.BLAZE, 6)
            );
        };
    }

    private List<ItemStack> simpleGear() {
        ItemStack sword = new ItemStack(Material.STONE_SWORD);
        ItemMeta sm = sword.getItemMeta();
        sm.setDisplayName("§7Simple Blade");
        sword.setItemMeta(sm);

        ItemStack chest = new ItemStack(Material.CHAINMAIL_CHESTPLATE);
        ItemMeta cm = chest.getItemMeta();
        cm.setDisplayName("§7Simple Chestpiece");
        chest.setItemMeta(cm);

        return List.of(sword, chest);
    }

    private List<ItemStack> midGear(Material swordType, Material chestType, String color, String tierName) {
        ItemStack sword = new ItemStack(swordType);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, 4);
        ItemMeta sm = sword.getItemMeta();
        sm.setDisplayName(color + tierName + " Blade");
        sword.setItemMeta(sm);

        ItemStack chest = new ItemStack(chestType);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        ItemMeta cm = chest.getItemMeta();
        cm.setDisplayName(color + tierName + " Chestplate");
        chest.setItemMeta(cm);

        return List.of(sword, chest, createXPBottle(2000));
    }

    private List<ItemStack> goodGear() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, 6);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 3);
        ItemMeta sm = sword.getItemMeta();
        sm.setDisplayName("§5Ultimate Reaver");
        sword.setItemMeta(sm);

        ItemStack chest = new ItemStack(Material.DIAMOND_CHESTPLATE);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, 5);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 3);
        ItemMeta cm = chest.getItemMeta();
        cm.setDisplayName("§5Ultimate Guard");
        chest.setItemMeta(cm);

        return List.of(sword, chest, createXPBottle(5000));
    }

    private List<ItemStack> greatGear() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, 7);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 4);
        ItemMeta sm = sword.getItemMeta();
        sm.setDisplayName("§6Legend Edge");
        sword.setItemMeta(sm);

        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, 6);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 4);
        ItemMeta cm = chest.getItemMeta();
        cm.setDisplayName("§6Legend Guard");
        chest.setItemMeta(cm);

        return List.of(sword, chest, createXPBottle(8000), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
    }

    private List<ItemStack> godGear() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL, 8);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 5);
        ItemMeta sm = sword.getItemMeta();
        sm.setDisplayName("§cGodslayer");
        sword.setItemMeta(sm);

        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION_ENVIRONMENTAL, 7);
        chest.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 5);
        ItemMeta cm = chest.getItemMeta();
        cm.setDisplayName("§cGodly Aegis");
        chest.setItemMeta(cm);

        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.ARROW_DAMAGE, 7);
        bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 5);
        ItemMeta bm = bow.getItemMeta();
        bm.setDisplayName("§cGodly Longbow");
        bow.setItemMeta(bm);

        return List.of(sword, chest, bow, createXPBottle(12000), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 5));
    }

    private int randomRange(int min, int max) {
        return min + random.nextInt((max - min) + 1);
    }

    private String format(int value) {
        if (value >= 1_000_000) {
            return String.format("%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format("%.0fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    private String pretty(String input) {
        String lower = input.toLowerCase(Locale.ROOT).replace('_', ' ');
        String[] parts = lower.split(" ");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
        }
        return result.toString().trim();
    }

    private record RewardOption(int weight, Reward reward) {}
    private record SpawnerChoice(EntityType type, int weight) {}

    private interface Reward {
        RewardDraw create();
    }

    public record RewardDraw(ItemStack preview, String displayName, Consumer<Player> grant) {}
}
