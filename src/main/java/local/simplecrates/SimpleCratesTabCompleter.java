package local.simplecrates;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SimpleCratesTabCompleter implements TabCompleter {

    private final CrateManager crateManager;

    public SimpleCratesTabCompleter(CrateManager crateManager) {
        this.crateManager = crateManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "crate" -> completeCrate(sender, args);
            case "rankvoucher" -> completeRankVoucher(sender, args);
            default -> List.of();
        };
    }

    private List<String> completeCrate(CommandSender sender, String[] args) {
        boolean admin = hasAdmin(sender);

        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            out.add("open");
            if (admin) out.add("give");
            return filter(out, args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            if (!admin) return List.of();
            return filter(onlinePlayers(), args[1]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            return filter(tiers(), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            if (!admin) return List.of();
            return filter(tiers(), args[2]);
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            if (!admin) return List.of();
            return filter(List.of("1", "2", "5", "10", "16", "32", "64"), args[3]);
        }

        return List.of();
    }

    private List<String> completeRankVoucher(CommandSender sender, String[] args) {
        if (!hasAdmin(sender)) return List.of();

        if (args.length == 1) return filter(List.of("give"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return filter(onlinePlayers(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(crateManager.getServerRanks(), args[2]);
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) return filter(List.of("1", "2", "5", "10", "16", "32", "64"), args[3]);

        return List.of();
    }

    private List<String> tiers() {
        List<String> out = new ArrayList<>();
        for (CrateTier tier : CrateTier.values()) {
            out.add(tier.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private List<String> onlinePlayers() {
        List<String> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            out.add(player.getName());
        }
        return out;
    }

    private boolean hasAdmin(CommandSender sender) {
        return sender.hasPermission("simplecrates.admin") || sender.isOp();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
