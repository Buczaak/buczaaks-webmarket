package pl.kryptogielda.core;

/**
 * Definicja kryptowaluty (z config.yml).
 *
 * @param startPrice cena startowa w groszach
 * @param volMin     minimalny mnożnik zmiany ceny w cyklu symulacji, np. 0.96
 * @param volMax     maksymalny mnożnik, np. 1.04
 * @param liquidity  ile sztuk przesuwa cenę o ok. 1% (0 = wylicz z ceny startowej i ustawień rynku)
 */
public record CoinDef(String id, long startPrice, String icon, double volMin, double volMax, double liquidity) {
    public CoinDef(String id, long startPrice, String icon, double volMin, double volMax) {
        this(id, startPrice, icon, volMin, volMax, 0);
    }
}
