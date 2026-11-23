package uz.habibi.bakery.i18n;

/** Bot va Mini App tillari. */
public enum Lang {
    UZ("uz", "🇺🇿 Özbekça"),
    CYR("cyr", "🇺🇿 Ўзбекча"),
    RU("ru", "🇷🇺 Русский");

    private final String code;
    private final String title;

    Lang(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    /** Til o'z tilida yozilgan nomi (tanlash tugmalari uchun). */
    public String title() {
        return title;
    }

    public static Lang fromCode(String code) {
        if (code != null) {
            for (Lang l : values()) {
                if (l.code.equalsIgnoreCase(code.trim())) {
                    return l;
                }
            }
        }
        return UZ;
    }

    /** Telegram'dagi foydalanuvchi tili (language_code) bo'yicha taxmin. */
    public static Lang fromTelegram(String languageCode) {
        return languageCode != null && languageCode.toLowerCase().startsWith("ru") ? RU : UZ;
    }
}
