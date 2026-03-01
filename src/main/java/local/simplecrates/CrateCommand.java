package local.simplecrates;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CrateCommand implements CommandExecutor {

    private final CrateManager crateManager;
    private final CrateAnimationListener animationListener;

    public CrateCommand(CrateManager crateManager, CrateAnimationListener animationListener) {
        this.crateManager = crateManager;
        this.animationListener = animationListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/crate give <player> <tier> [amount]");
            sender.sendMessage("§6/crate open <tier>");
            sender.sendMessage("§7Tiers: simple, unique, elite, ultimate, legendary, godly");
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("simplecrates.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§cUsage: /crate give <player> <tier> [amount]");
                return true;
            }

            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }

            CrateTier tier = CrateTier.from(args[2]);
            if (tier == null) {
                sender.sendMessage("§cInvalid tier.");
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

            target.getInventory().addItem(crateManager.createCrateItem(tier, amount));
            sender.sendMessage("§aGave §e" + amount + "x §f" + tier.getDisplayName() + " §ato " + target.getName());
            target.sendMessage("§aYou received §e" + amount + "x §f" + tier.getDisplayName());
            return true;
        }

        if (args[0].equalsIgnoreCase("open")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cPlayers only.");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage("§cUsage: /crate open <tier>");
                return true;
            }

            CrateTier tier = CrateTier.from(args[1]);
            if (tier == null) {
                player.sendMessage("§cInvalid tier.");
                return true;
            }

            animationListener.startOpening(player, tier);
            return true;
        }

        sender.sendMessage("§cUnknown subcommand.");
        return true;
    }
}
