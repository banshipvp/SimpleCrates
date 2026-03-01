package local.simplecrates;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class RankVoucherCommand implements CommandExecutor {

    private final CrateManager crateManager;

    public RankVoucherCommand(CrateManager crateManager) {
        this.crateManager = crateManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("simplecrates.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§6/rankvoucher give <player> <rank> [amount]");
            sender.sendMessage("§7Ranks: " + String.join(", ", crateManager.getServerRanks()));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
        }

        String rank = args[2].toLowerCase();
        if (!crateManager.isValidRank(rank)) {
            sender.sendMessage("§cInvalid rank: " + rank);
            sender.sendMessage("§7Ranks: " + String.join(", ", crateManager.getServerRanks()));
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cAmount must be a number.");
                return true;
            }
        }

        ItemStack voucher = crateManager.createRankVoucher(rank);
        voucher.setAmount(Math.min(amount, voucher.getMaxStackSize()));

        int remaining = amount;
        while (remaining > 0) {
            int stack = Math.min(remaining, voucher.getMaxStackSize());
            ItemStack give = voucher.clone();
            give.setAmount(stack);
            target.getInventory().addItem(give);
            remaining -= stack;
        }

        sender.sendMessage("§aGave §e" + amount + "x §f" + rank + " §aRank Voucher to " + target.getName());
        target.sendMessage("§aYou received §e" + amount + "x §f" + rank + " §aRank Voucher");
        return true;
    }
}
