package pl.kryptogielda.core;

/**
 * Parametry rynku (z config.yml). Kwoty w groszach.
 *
 * @param impactValueForOnePercent wartość transakcji (w groszach), która przesuwa cenę o ok. 1% (głębokość rynku)
 * @param maxBuyMove               największy jednorazowy wzrost ceny po kupnie, np. 1.0 = +100% (większe zlecenia są odrzucane)
 */
public record Settings(
        String currency,
        long minPrice,
        int sellFeeBp,
        long impactValueForOnePercent,
        double maxBuyMove,
        int maxOrdersPerPlayer,
        long minTradeValue,
        long historyRetentionMs,
        long botStartBalance
) {
    public static Settings defaults() {
        return new Settings("PLN", 50, 200, 1_000_000, 1.0, 20, 1, 30L * 24 * 3600 * 1000, 15_000_000);
    }
}
