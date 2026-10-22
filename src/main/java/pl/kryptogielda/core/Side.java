package pl.kryptogielda.core;

/** Kierunek transakcji / zlecenia. */
public enum Side {
    BUY, SELL;

    public static Side parse(String s) {
        if (s == null) return null;
        switch (s.trim().toUpperCase()) {
            case "BUY": case "KUPNO": return BUY;
            case "SELL": case "SPRZEDAZ": case "SPRZEDAŻ": return SELL;
            default: return null;
        }
    }
}
