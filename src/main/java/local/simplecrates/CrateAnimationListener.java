package local.simplecrates;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class CrateAnimationListener implements Listener {

    private static final String OPENING_TITLE = "§6§lCrate Opening";
    private static final String PICK_TITLE = "§e§lPick 5 Rewards";
    private static final int PICK_COUNT = 5;
    private static final int CHEST_SIZE = 54;
    private static final int ANIMATION_TICKS = 100;

    private final SimpleCratesPlugin plugin;
    private final CrateManager crateManager;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Players currently in the middle of async reward rolling — prevents double-opens. */
    private final Set<UUID> pending = new HashSet<>();

    public CrateAnimationListener(SimpleCratesPlugin plugin, CrateManager crateManager) {
        this.plugin = plugin;
        this.crateManager = crateManager;
    }

    public void startOpening(Player player, CrateTier tier) {
        UUID uid = player.getUniqueId();
        if (sessions.containsKey(uid) || pending.contains(uid)) {
            player.sendMessage("§cYou are already opening a crate.");
            return;
        }
        pending.add(uid);

        // Open the inventory in the VERY NEXT TICK (just after the interact event finishes)
        // using placeholder panes so there is zero perceptible delay.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(uid);
            if (!player.isOnline()) return;

            // Placeholder display pool — cheap glass panes that animate immediately
            List<ItemStack> displayPool = new ArrayList<>(CHEST_SIZE);
            for (int i = 0; i < CHEST_SIZE; i++) {
                displayPool.add(pane(Material.CYAN_STAINED_GLASS_PANE, "§b§lOpening Crate..."));
            }

            Inventory opening = Bukkit.createInventory(null, CHEST_SIZE, OPENING_TITLE);
            animateFrame(opening, displayPool);

            // Session starts in ROLLING stage — rewards not yet ready
            Session session = new Session(uid, tier, new ArrayList<>(), opening, displayPool);
            session.stage = Stage.ROLLING;
            sessions.put(uid, session);

            player.openInventory(opening);

            // Animation ticker: runs during both ROLLING and ANIMATING stages.
            // Only transitions to PICKING once rewards are loaded and tick budget exhausted.
            new BukkitRunnable() {
                int ticksPassed = 0;

                @Override
                public void run() {
                    Session s = sessions.get(uid);
                    if (s == null || s.stage == Stage.PICKING) { cancel(); return; }
                    if (!player.isOnline()) { sessions.remove(uid); cancel(); return; }

                    animateFrame(s.inventory, s.displayPool);

                    // Only count down once rewards are loaded
                    if (s.stage == Stage.ANIMATING) {
                        ticksPassed += 4;
                        if (ticksPassed >= ANIMATION_TICKS) {
                            s.stage = Stage.PICKING;
                            openPickGui(player, s);
                            cancel();
                        }
                    } else if (s.stage == Stage.ROLLING) {
                        // Add a timeout for rolling to prevent infinite waiting on cyan glass panes
                        ticksPassed += 4;
                        if (ticksPassed >= 200) { // 10 seconds timeout
                            plugin.getLogger().warning("Crate rolling timeout for player " + player.getName());
                            sessions.remove(uid);
                            player.closeInventory();
                            player.sendMessage("§cCrate opening timeout. Your crate has been refunded.");
                            player.getInventory().addItem(crateManager.createCrateItem(tier, 1));
                            cancel();
                        }
                    }
                }
            }.runTaskTimer(plugin, 0L, 4L);

            // Roll rewards in background — will update the session once done
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                List<CrateManager.RewardDraw> rolled;
                List<ItemStack> realPool;
                try {
                    rolled = crateManager.rollRewardChoices(tier, CHEST_SIZE);
                    realPool = new ArrayList<>(CHEST_SIZE);
                    for (CrateManager.RewardDraw draw : rolled) {
                        realPool.add(draw.preview().clone());
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error rolling crate rewards: " + e.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sessions.remove(uid);
                        if (player.isOnline()) {
                            player.closeInventory();
                            player.sendMessage("§cAn error occurred. Your crate has been refunded.");
                            player.getInventory().addItem(crateManager.createCrateItem(tier, 1));
                        }
                    });
                    return;
                }

                final List<CrateManager.RewardDraw> finalRolled = rolled;
                final List<ItemStack> finalPool = realPool;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Session s = sessions.get(uid);
                    if (s == null) return; // player closed before roll finished
                    // Replace placeholder pool with real items and mark ready
                    s.rewards.addAll(finalRolled);
                    s.displayPool.clear();
                    s.displayPool.addAll(finalPool);
                    s.stage = Stage.ANIMATING; // animation ticker picks this up
                });
            });
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        String title = event.getView().getTitle();
        if (!OPENING_TITLE.equals(title) && !PICK_TITLE.equals(title)) return;

        event.setCancelled(true);

        if (session.stage != Stage.PICKING || !PICK_TITLE.equals(title)) {
            return;
        }

        int slot = event.getRawSlot();
        if (!session.slotToReward.containsKey(slot)) {
            return;
        }

        if (session.pickedSlots.contains(slot)) {
            player.sendMessage("§cYou already picked this slot.");
            return;
        }

        if (session.picks >= PICK_COUNT) {
            player.closeInventory();
            finishSession(player.getUniqueId());
            return;
        }

        CrateManager.RewardDraw reward = session.slotToReward.get(slot);
        session.pickedSlots.add(slot);
        session.picks++;

        if (!session.baseMoneyGranted) {
            crateManager.grantBaseMoney(player, session.tier);
            session.baseMoneyGranted = true;
        }

        reward.grant().accept(player);
        player.sendMessage("§ePick " + session.picks + "/" + PICK_COUNT + ": §f" + reward.displayName());

        ItemStack revealed = reward.preview().clone();
        ItemMeta meta = revealed.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
            lore.add("§7Reward claimed:");
            lore.add("§f" + reward.displayName());
            lore.add("§aSELECTED");
            meta.setLore(lore);
            revealed.setItemMeta(meta);
        }
        session.revealedItems.put(slot, revealed.clone());
        event.getInventory().setItem(slot, revealed);

        if (session.picks >= PICK_COUNT) {
            player.sendMessage("§aYou selected all " + PICK_COUNT + " rewards.");
            Bukkit.getScheduler().runTaskLater(plugin, player::closeInventory, 20L);
            finishSession(player.getUniqueId());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;

        String closedTitle = event.getView().getTitle();
        if (OPENING_TITLE.equals(closedTitle) && session.stage == Stage.PICKING) {
            return;
        }

        finishSession(player.getUniqueId());

        if (session.picks == 0) {
            ItemStack refund = crateManager.createCrateItem(session.tier, 1);
            HashMap<Integer, ItemStack> left = player.getInventory().addItem(refund);
            if (!left.isEmpty()) {
                for (ItemStack drop : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            player.sendMessage("§eCrate opening cancelled. Your crate was refunded.");
            return;
        }

        if (session.picks < PICK_COUNT) {
            player.sendMessage("§7Crate closed. Unpicked rewards were removed.");
        }
    }

    private void openPickGui(Player player, Session session) {
        Inventory pickInv = Bukkit.createInventory(null, CHEST_SIZE, PICK_TITLE);

        ItemStack hidden = pane(Material.WHITE_STAINED_GLASS_PANE, "§fClick to reveal");
        for (int i = 0; i < pickInv.getSize(); i++) {
            pickInv.setItem(i, hidden);
        }

        session.slotToReward.clear();
        for (int slot = 0; slot < pickInv.getSize(); slot++) {
            CrateManager.RewardDraw reward = session.rewards.get(slot);
            session.slotToReward.put(slot, reward);

            if (session.pickedSlots.contains(slot)) {
                ItemStack revealed = session.revealedItems.get(slot);
                if (revealed != null) {
                    pickInv.setItem(slot, revealed.clone());
                }
            }
        }

        session.inventory = pickInv;
        player.openInventory(pickInv);
        if (!session.pickPromptSent) {
            player.sendMessage("§6Pick " + PICK_COUNT + " rewards by clicking any white pane.");
            session.pickPromptSent = true;
        }
    }

    private void fillOpening(Inventory inventory, String name) {
        ItemStack pane = pane(Material.GRAY_STAINED_GLASS_PANE, name);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, pane);
        }
    }

    private void animateFrame(Inventory inventory, List<ItemStack> displayPool) {
        // Shuffle the pre-built pool and update slots — no reward re-rolling, very cheap
        List<ItemStack> shuffled = new ArrayList<>(displayPool);
        Collections.shuffle(shuffled);
        for (int i = 0; i < inventory.getSize() && i < shuffled.size(); i++) {
            inventory.setItem(i, shuffled.get(i));
        }
    }

    private ItemStack pane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private void finishSession(UUID playerId) {
        sessions.remove(playerId);
    }

    private enum Stage {
        ROLLING,   // inventory open; rewards still being generated async
        ANIMATING, // rewards ready; animation counting down
        PICKING
    }

    private static class Session {
        private final UUID playerId;
        private final CrateTier tier;
        private final List<CrateManager.RewardDraw> rewards;
        private final List<ItemStack> displayPool;
        private Inventory inventory;
        private Stage stage;
        private int picks;
        private boolean pickPromptSent;
        private boolean baseMoneyGranted;
        private final Set<Integer> pickedSlots = new HashSet<>();
        private final Map<Integer, CrateManager.RewardDraw> slotToReward = new HashMap<>();
        private final Map<Integer, ItemStack> revealedItems = new HashMap<>();

        private Session(UUID playerId, CrateTier tier, List<CrateManager.RewardDraw> rewards, Inventory inventory, List<ItemStack> displayPool) {
            this.playerId = playerId;
            this.tier = tier;
            this.rewards = rewards;
            this.inventory = inventory;
            this.displayPool = displayPool;
            this.stage = Stage.ANIMATING;
            this.picks = 0;
            this.pickPromptSent = false;
            this.baseMoneyGranted = false;
        }
    }
}
