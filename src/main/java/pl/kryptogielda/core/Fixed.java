package pl.kryptogielda.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Arytmetyka stałoprzecinkowa. Cała ekonomia liczona jest na liczbach całkowitych:
 * <ul>
 *   <li>pieniądze i ceny: grosze (1 PLN = 100)</li>
 *   <li>ilości kryptowalut: 1/10000 sztuki (1 szt. = 10000)</li>
 * </ul>
 * Dzięki temu nie ma błędów zaokrągleń typu double, na których można by żerować.
 */
public final class Fixed {
    private Fixed() {}

    public static final long MONEY_SCALE = 100L;
    public static final long QTY_SCALE = 10_000L;

    /** Maksymalna ilość w jednej operacji: 1 mld sztuk. */
    public static final long MAX_QTY = 1_000_000_000L * QTY_SCALE;
    /** Maksymalna kwota pojedynczej operacji: 10 mld jednostek waluty. */
    public static final long MAX_MONEY = 10_000_000_000L * MONEY_SCALE;

    private static final BigDecimal BD_MONEY = BigDecimal.valueOf(MONEY_SCALE);
    private static final BigDecimal BD_QTY = BigDecimal.valueOf(QTY_SCALE);

    /** Format "1234.56" (bez separatorów tysięcy, kropka dziesiętna). */
    public static String money(long cents) {
        boolean neg = cents < 0;
        long abs = Math.abs(cents);
        String s = (abs / MONEY_SCALE) + "." + String.format("%02d", abs % MONEY_SCALE);
        return neg ? "-" + s : s;
    }

    /** Format z separatorem tysięcy (spacja), do czatu / GUI: "1 234 567.89". */
    public static String moneyPretty(long cents) {
        boolean neg = cents < 0;
        long abs = Math.abs(cents);
        String whole = Long.toString(abs / MONEY_SCALE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) sb.append(' ');
            sb.append(whole.charAt(i));
        }
        sb.append('.').append(String.format("%02d", abs % MONEY_SCALE));
        return (neg ? "-" : "") + sb;
    }

    /** Format ilości bez zbędnych zer: 12, 0.5, 3.1415. */
    public static String qty(long q) {
        boolean neg = q < 0;
        long abs = Math.abs(q);
        long whole = abs / QTY_SCALE;
        long frac = abs % QTY_SCALE;
        String s = Long.toString(whole);
        if (frac != 0) {
            String f = String.format("%04d", frac);
            int end = f.length();
            while (end > 0 && f.charAt(end - 1) == '0') end--;
            s += "." + f.substring(0, end);
        }
        return neg ? "-" + s : s;
    }

    /**
     * Parsuje kwotę ("12", "12.5", "12,50"). Maksymalnie 2 miejsca po przecinku.
     * @return grosze lub {@code Long.MIN_VALUE} gdy tekst jest niepoprawny.
     */
    public static long parseMoney(String s) {
        return parseScaled(s, 2, MONEY_SCALE, MAX_MONEY, true);
    }

    /**
     * Parsuje ilość ("1", "0.25", "3,1415"). Maksymalnie 4 miejsca po przecinku, wartość dodatnia.
     * @return ilość w jednostkach 1/10000 lub {@code Long.MIN_VALUE} gdy tekst jest niepoprawny.
     */
    public static long parseQty(String s) {
        return parseScaled(s, 4, QTY_SCALE, MAX_QTY, false);
    }

    public static final long INVALID = Long.MIN_VALUE;

    private static long parseScaled(String s, int maxDecimals, long scale, long max, boolean allowZero) {
        if (s == null) return INVALID;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty() || t.length() > 24) return INVALID;
        // tylko cyfry i jedna kropka - żadnych wykładników, minusów, spacji itp.
        int dots = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '.') { dots++; } else if (c < '0' || c > '9') { return INVALID; }
        }
        if (dots > 1 || t.equals(".")) return INVALID;
        BigDecimal bd;
        try {
            bd = new BigDecimal(t);
        } catch (NumberFormatException e) {
            return INVALID;
        }
        if (bd.scale() > maxDecimals && bd.stripTrailingZeros().scale() > maxDecimals) return INVALID;
        BigInteger scaled = bd.multiply(BigDecimal.valueOf(scale)).setScale(0, RoundingMode.UNNECESSARY).toBigInteger();
        if (scaled.bitLength() > 62) return INVALID;
        long v = scaled.longValueExact();
        if (v > max) return INVALID;
        if (v == 0 && !allowZero) return INVALID;
        return v;
    }

    /** Wartość = ilość (1/10000) * cena (grosze) -> grosze, zaokrąglenie wg trybu. */
    public static long value(long qty, long priceCents, RoundingMode mode) {
        BigInteger prod = BigInteger.valueOf(qty).multiply(BigInteger.valueOf(priceCents));
        BigDecimal v = new BigDecimal(prod).divide(BD_QTY, 0, mode);
        if (v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw new ArithmeticException("overflow");
        return v.longValueExact();
    }

    /** Ceny wewnątrz rynku są trzymane z dokładnością 1e-6 waluty (1 grosz = 10000 jednostek). */
    public static final long MICRO_PER_CENT = 10_000L;

    public static long microOf(long cents) { return Math.multiplyExact(cents, MICRO_PER_CENT); }

    /** Cena w groszach (zaokrąglona do najbliższego grosza) z ceny wewnętrznej. */
    public static long centsOf(long micro) { return (micro + MICRO_PER_CENT / 2) / MICRO_PER_CENT; }

    /** Wartość = ilość (1/10000) * cena wewnętrzna (1e-6 waluty) -> grosze. */
    public static long valueMicro(long qty, long micro, RoundingMode mode) {
        BigInteger prod = BigInteger.valueOf(qty).multiply(BigInteger.valueOf(micro));
        BigDecimal v = new BigDecimal(prod).divide(BigDecimal.valueOf(100_000_000L), 0, mode);
        if (v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw new ArithmeticException("overflow");
        return v.longValueExact();
    }

    /** Wartość * mnożnik (BigDecimal) -> grosze. */
    public static long scale(long cents, BigDecimal factor, RoundingMode mode) {
        BigDecimal v = BigDecimal.valueOf(cents).multiply(factor).setScale(0, mode);
        if (v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) throw new ArithmeticException("overflow");
        return v.longValueExact();
    }

    /** a * b / c na liczbach dowolnej wielkości (bez przepełnienia). */
    public static long mulDiv(long a, long b, long c, RoundingMode mode) {
        if (c == 0) return 0;
        BigInteger prod = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
        return new BigDecimal(prod).divide(BigDecimal.valueOf(c), 0, mode).longValueExact();
    }

    public static double toDouble(long cents) { return cents / (double) MONEY_SCALE; }
}
