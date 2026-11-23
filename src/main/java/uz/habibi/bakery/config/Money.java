package uz.habibi.bakery.config;

public final class Money {
    private Money() {
    }

    /** 450000 -> "450 000 so'm" */
    public static String format(long amount) {
        return format(amount, uz.habibi.bakery.i18n.Lang.UZ);
    }

    /** 450000 -> "450 000 сум" (tilga qarab) */
    public static String format(long amount, uz.habibi.bakery.i18n.Lang lang) {
        return String.format(java.util.Locale.US, "%,d", amount).replace(',', ' ') + " "
                + uz.habibi.bakery.i18n.I18n.t(lang, "som");
    }
}
