package local.simplecrates;

public enum CrateTier {
    SIMPLE("simple", "§7§lSimple Crate", 10_000, 25_000),
    UNIQUE("unique", "§a§lUnique Crate", 25_000, 60_000),
    ELITE("elite", "§b§lElite Crate", 60_000, 140_000),
    ULTIMATE("ultimate", "§5§lUltimate Crate", 140_000, 300_000),
    LEGENDARY("legendary", "§6§lLegendary Crate", 300_000, 500_000),
    GODLY("godly", "§d§lGodly Crate", 500_000, 1_000_000);

    private final String id;
    private final String displayName;
    private final int minMoney;
    private final int maxMoney;

    CrateTier(String id, String displayName, int minMoney, int maxMoney) {
        this.id = id;
        this.displayName = displayName;
        this.minMoney = minMoney;
        this.maxMoney = maxMoney;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getMinMoney() {
        return minMoney;
    }

    public int getMaxMoney() {
        return maxMoney;
    }

    public static CrateTier from(String input) {
        for (CrateTier tier : values()) {
            if (tier.id.equalsIgnoreCase(input)) {
                return tier;
            }
        }
        return null;
    }
}
