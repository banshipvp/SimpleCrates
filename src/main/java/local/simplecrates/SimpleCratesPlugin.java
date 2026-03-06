package local.simplecrates;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleCratesPlugin extends JavaPlugin {

    private Economy economy;
    private CrateManager crateManager;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found, disabling SimpleCrates.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        crateManager = new CrateManager(this, economy);
        CrateAnimationListener animationListener = new CrateAnimationListener(this, crateManager);
        SimpleCratesTabCompleter tabCompleter = new SimpleCratesTabCompleter(crateManager);

        getCommand("crate").setExecutor(new CrateCommand(crateManager, animationListener));
        getCommand("crate").setTabCompleter(tabCompleter);
        getCommand("rankvoucher").setExecutor(new RankVoucherCommand(crateManager));
        getCommand("rankvoucher").setTabCompleter(tabCompleter);
        Bukkit.getPluginManager().registerEvents(animationListener, this);
        Bukkit.getPluginManager().registerEvents(new CrateListener(crateManager, animationListener), this);

        getLogger().info("SimpleCrates enabled with " + CrateTier.values().length + " crate tiers.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }

        economy = rsp.getProvider();
        return economy != null;
    }
}
