package local.simplecrates;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class CrateListener implements Listener {

    private final CrateManager crateManager;
    private final CrateAnimationListener animationListener;

    public CrateListener(CrateManager crateManager, CrateAnimationListener animationListener) {
        this.crateManager = crateManager;
        this.animationListener = animationListener;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!rightClick && !leftClick) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        Player player = event.getPlayer();

        if (crateManager.isCrate(item)) {
            event.setCancelled(true);

            CrateTier tier = crateManager.getTier(item);
            if (tier == null) {
                player.sendMessage("§cInvalid crate item.");
                return;
            }

            if (leftClick) {
                player.openInventory(crateManager.createPreviewInventory(tier));
                player.sendMessage("§ePreviewing " + tier.getDisplayName() + " §erewards.");
                return;
            }

            consumeOne(event, item);
            animationListener.startOpening(player, tier);
            return;
        }

        if (rightClick && crateManager.isRankVoucher(item)) {
            event.setCancelled(true);
            String rank = crateManager.getVoucherRank(item);
            if (rank == null) {
                player.sendMessage("§cInvalid rank voucher.");
                return;
            }

            if (crateManager.applyRankVoucher(player, rank)) {
                consumeOne(event, item);
            }
        }
    }

    @EventHandler
    public void onPreviewClick(InventoryClickEvent event) {
        if (crateManager.isPreviewTitle(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    private void consumeOne(PlayerInteractEvent event, ItemStack item) {
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            return;
        }

        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        } else {
            event.getPlayer().getInventory().setItemInMainHand(null);
        }
    }
}
