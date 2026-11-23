package uz.habibi.bakery.i18n;

import java.util.List;

/**
 * Amaldagi o'zbek lotin yozuvidan yangi alifboga (2026-yil 21-oktabr qonuni):
 * Oʻ → Ö, Gʻ → Ğ, Sh → Ş, Ch → Ç; tutuq belgisi → ʼ.
 * HTML teglar, {0} o'rinbosarlar, havolalar va brend nomlari o'zgarmaydi. Qayta qo'llansa natija o'zgarmaydi.
 */
public final class NewAlphabet {

    private static final List<String> PROTECTED = List.of(
            "Habibi Bakery", "Habibi", "Google Maps", "Yandex", "Telegram", "Click", "Payme",
            "Chocoboom", "chocoberry", "UzQR", "QR", "HTML");

    private static final String APOSTROPHES = "'’ʻʼ`‘";

    private NewAlphabet() {
    }

    public static String convert(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            int skip = skipLength(text, i);
            if (skip > 0) {
                out.append(text, i, i + skip);
                i += skip;
                continue;
            }
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            char lower = Character.toLowerCase(c);
            boolean upper = Character.isUpperCase(c);

            if ((lower == 'o' || lower == 'g') && isApostrophe(next)) {
                char r = lower == 'o' ? 'ö' : 'ğ';
                out.append(upper ? Character.toUpperCase(r) : r);
                i += 2;
                continue;
            }
            if ((lower == 's' || lower == 'c') && Character.toLowerCase(next) == 'h') {
                char r = lower == 's' ? 'ş' : 'ç';
                out.append(upper ? Character.toUpperCase(r) : r);
                i += 2;
                continue;
            }
            // tutuq belgisi (ma'lumot -> maʼlumot)
            if (isApostrophe(c) && c != 'ʼ' && i > 0 && Character.isLetter(text.charAt(i - 1)) && Character.isLetter(next)) {
                out.append('ʼ');
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** O'zgartirilmaydigan bo'lak uzunligi (teg, o'rinbosar, havola, brend), aks holda 0. */
    private static int skipLength(String text, int i) {
        char c = text.charAt(i);
        if (c == '<') {
            int end = text.indexOf('>', i);
            if (end > 0) {
                return end - i + 1;
            }
        }
        if (c == '{') {
            int end = text.indexOf('}', i);
            if (end > 0) {
                return end - i + 1;
            }
        }
        if (c == '&') {
            int end = text.indexOf(';', i);
            if (end > 0 && end - i <= 8) {
                return end - i + 1;
            }
        }
        if (text.startsWith("http", i) || text.startsWith("tg://", i)) {
            int end = i;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '"') {
                end++;
            }
            return end - i;
        }
        if (Character.isLetter(c) && (i == 0 || !Character.isLetter(text.charAt(i - 1)))) {
            for (String p : PROTECTED) {
                int end = i + p.length();
                if (text.startsWith(p, i) && (end >= text.length() || !Character.isLetter(text.charAt(end))
                        || isApostrophe(text.charAt(end)))) {
                    return p.length();
                }
            }
        }
        return 0;
    }

    private static boolean isApostrophe(char c) {
        return APOSTROPHES.indexOf(c) >= 0;
    }
}
