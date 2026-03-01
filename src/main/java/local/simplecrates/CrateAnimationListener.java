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

    public CrateAnimationListener(SimpleCratesPlugin plugin, CrateManager crateManager) {
        this.plugin = plugin;
        this.crateManager = crateManager;
    }

    public void startOpening(Player player, CrateTier tier) {
        if (sessions.containsKey(player.getUniqueId())) {
            player.sendMessage("§cYou are already opening a crate.");
            return;
        }

        Inventory opening = Bukkit.createInventory(null, CHEST_SIZE, OPENING_TITLE);
        fillOpening(opening, "§7Randomizing rewards...");
        player.openInventory(opening);

        List<CrateManager.RewardDraw> rolled = crateManager.rollRewardChoices(tier, CHEST_SIZE);
        Session session = new Session(player.getUniqueId(), tier, rolled, opening);
        sessions.put(player.getUniqueId(), session);

        new BukkitRunnable() {
            int ticksPassed = 0;

            @Override
            public void run() {
                Session s = sessions.get(player.getUniqueId());
                if (s == null || s.stage != Stage.ANIMATING) {
                    cancel();
                    return;
                }

                if (!player.isOnline()) {
                    sessions.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                animateFrame(s.inventory, s.tier);
                ticksPassed += 4;

                if (ticksPassed >= ANIMATION_TICKS) {
                    s.stage = Stage.PICKING;
                    openPickGui(player, s);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 4L);
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

    private void animateFrame(Inventory inventory, CrateTier tier) {
        List<CrateManager.RewardDraw> frameRewards = crateManager.rollRewardChoices(tier, inventory.getSize());
        for (int i = 0; i < inventory.getSize(); i++) {
            CrateManager.RewardDraw reward = frameRewards.get(i);
            inventory.setItem(i, reward.preview().clone());
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
        ANIMATING,
        PICKING
    }

    private static class Session {
        private final UUID playerId;
        private final CrateTier tier;
        private final List<CrateManager.RewardDraw> rewards;
        private Inventory inventory;
        private Stage stage;
        private int picks;
        private boolean pickPromptSent;
        private boolean baseMoneyGranted;
        private final Set<Integer> pickedSlots = new HashSet<>();
        private final Map<Integer, CrateManager.RewardDraw> slotToReward = new HashMap<>();
        private final Map<Integer, ItemStack> revealedItems = new HashMap<>();

        private Session(UUID playerId, CrateTier tier, List<CrateManager.RewardDraw> rewards, Inventory inventory) {
            this.playerId = playerId;
            this.tier = tier;
            this.rewards = rewards;
            this.inventory = inventory;
            this.stage = Stage.ANIMATING;
            this.picks = 0;
            this.pickPromptSent = false;
            this.baseMoneyGranted = false;
        }
    }
}
