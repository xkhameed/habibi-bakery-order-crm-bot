package uz.habibi.bakery.i18n;

import java.util.List;

/**
 * O'zbek lotin yozuvidan kirill yozuviga o'girish.
 * HTML teglar, {0} kabi o'rinbosarlar, havolalar va brend nomlari o'zgarmaydi.
 */
public final class Translit {

    /** Kirillga o'girilmaydigan so'zlar (brendlar). Uzunroqlari oldin turishi kerak. */
    private static final List<String> PROTECTED = List.of(
            "Habibi Bakery", "Habibi", "Google Maps", "Yandex", "Telegram", "Click", "Payme",
            "Chocoboom", "chocoberry", "UzQR", "QR");

    /**
     * Ruschadan kirgan, kirillda boshqacha yoziladigan so'zlar (ь, я bilan).
     * Kalit — kichik harfli lotincha so'z boshi; so'zning qolgan qo'shimchasi odatdagidek o'giriladi.
     */
    private static final java.util.Map<String, String> WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("yanvar", "январь"), java.util.Map.entry("fevral", "февраль"),
            java.util.Map.entry("aprel", "апрель"), java.util.Map.entry("iyun", "июнь"),
            java.util.Map.entry("iyul", "июль"), java.util.Map.entry("sentabr", "сентябрь"),
            java.util.Map.entry("oktabr", "октябрь"), java.util.Map.entry("noyabr", "ноябрь"),
            java.util.Map.entry("dekabr", "декабрь"), java.util.Map.entry("kuryer", "курьер"),
            java.util.Map.entry("palchik", "пальчик"), java.util.Map.entry("palçik", "пальчик"), java.util.Map.entry("aksiya", "акция"),
            java.util.Map.entry("statistika", "статистика"));

    private static final String APOSTROPHES = "'’ʻʼ`‘";

    private Translit() {
    }

    public static String toCyrillic(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // HTML teg yoki entity
            if (c == '<') {
                int end = text.indexOf('>', i);
                if (end > 0) {
                    out.append(text, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '&') {
                int end = text.indexOf(';', i);
                if (end > 0 && end - i <= 8) {
                    out.append(text, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            // {0} o'rinbosar
            if (c == '{') {
                int end = text.indexOf('}', i);
                if (end > 0) {
                    out.append(text, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            // havola
            if (text.startsWith("http", i) || text.startsWith("tg://", i)) {
                int end = i;
                while (end < n && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != '"') {
                    end++;
                }
                out.append(text, i, end);
                i = end;
                continue;
            }
            // brend nomlari va maxsus so'zlar (so'z boshida)
            if (Character.isLetter(c) && (i == 0 || !Character.isLetter(text.charAt(i - 1)))) {
                String brand = protectedAt(text, i);
                if (brand != null) {
                    out.append(brand);
                    i += brand.length();
                    // brenddan keyingi tutuq belgisi o'zgarmaydi: Habibi Bakery'ni -> Habibi Bakery'ни
                    if (i < text.length() && isApostrophe(text.charAt(i))) {
                        out.append(text.charAt(i));
                        i++;
                    }
                    continue;
                }
                int consumed = specialWordAt(text, i, out);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            i = convertLetter(text, i, out);
        }
        return out.toString();
    }

    private static String protectedAt(String text, int i) {
        for (String p : PROTECTED) {
            if (text.startsWith(p, i)) {
                int end = i + p.length();
                if (end >= text.length() || !isWordChar(text.charAt(end))) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Lug'atdagi so'z (yoki uning qo'shimchali shakli, masalan "noyabrda") bo'lsa, o'giradi. */
    private static int specialWordAt(String text, int i, StringBuilder out) {
        for (var e : WORDS.entrySet()) {
            String key = e.getKey();
            if (text.regionMatches(true, i, key, 0, key.length())) {
                String cyr = e.getValue();
                if (Character.isUpperCase(text.charAt(i))) {
                    cyr = Character.toUpperCase(cyr.charAt(0)) + cyr.substring(1);
                }
                int end = i + key.length();
                // qo'shimcha bo'lsa ("noyabrda"), ь tashlab qo'shimchani alohida o'giramiz
                if (end < text.length() && Character.isLetter(text.charAt(end)) && cyr.endsWith("ь")) {
                    cyr = cyr.substring(0, cyr.length() - 1);
                }
                out.append(cyr);
                return key.length();
            }
        }
        return 0;
    }

    /** Harfmi (tutuq belgisi ʼ Unicode'da harf hisoblansa ham, bu yerda so'z chegarasi). */
    private static boolean isWordChar(char c) {
        return Character.isLetter(c) && !isApostrophe(c);
    }

    private static boolean isApostrophe(char c) {
        return APOSTROPHES.indexOf(c) >= 0;
    }

    private static boolean isLatinVowel(char c) {
        return "aeiouAEIOU".indexOf(c) >= 0;
    }

    /** Bitta harf (yoki harf birikmasini) o'girib, keyingi indeksni qaytaradi. */
    private static int convertLetter(String s, int i, StringBuilder out) {
        char c = s.charAt(i);
        char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
        char lower = Character.toLowerCase(c);
        boolean upper = Character.isUpperCase(c);
        boolean nextUpper = Character.isUpperCase(next);

        // yangi alifbo harflari
        switch (lower) {
            case 'ö' -> { out.append(upper ? "Ў" : "ў"); return i + 1; }
            case 'ğ' -> { out.append(upper ? "Ғ" : "ғ"); return i + 1; }
            case 'ş' -> { out.append(upper ? "Ш" : "ш"); return i + 1; }
            case 'ç' -> { out.append(upper ? "Ч" : "ч"); return i + 1; }
            default -> {
            }
        }
        // o' -> ў, g' -> ғ
        if ((lower == 'o' || lower == 'g') && isApostrophe(next)) {
            String r = lower == 'o' ? "ў" : "ғ";
            out.append(upper ? r.toUpperCase() : r);
            return i + 2;
        }
        // harf birikmalari
        String pair = ("" + lower + Character.toLowerCase(next));
        String digraph = switch (pair) {
            case "sh" -> "ш";
            case "ch" -> "ч";
            case "yo" -> (i + 2 < s.length() && isApostrophe(s.charAt(i + 2))) ? null : "ё";
            case "yu" -> "ю";
            case "ya" -> "я";
            case "ye" -> "е";
            default -> null;
        };
        if (digraph != null) {
            if (upper && (nextUpper || !Character.isLetter(next))) {
                out.append(digraph.toUpperCase());
            } else if (upper) {
                out.append(Character.toUpperCase(digraph.charAt(0)));
            } else {
                out.append(digraph);
            }
            return i + 2;
        }
        // e: so'z boshida yoki unlidan keyin -> э
        if (lower == 'e') {
            boolean wordStart = i == 0 || !Character.isLetter(s.charAt(i - 1));
            boolean afterVowel = i > 0 && isLatinVowel(s.charAt(i - 1));
            String r = wordStart || afterVowel ? "э" : "е";
            out.append(upper ? r.toUpperCase() : r);
            return i + 1;
        }
        // so'z oxirida unlidan keyingi i -> й (Dubai -> Дубай)
        if (lower == 'i' && i > 0 && "aouAOU".indexOf(s.charAt(i - 1)) >= 0 && !Character.isLetter(next)) {
            out.append(upper ? "Й" : "й");
            return i + 1;
        }
        // tutuq belgisi -> ъ
        if (isApostrophe(c) && i > 0 && Character.isLetter(s.charAt(i - 1)) && Character.isLetter(next)) {
            out.append('ъ');
            return i + 1;
        }
        String single = switch (lower) {
            case 'a' -> "а";
            case 'b' -> "б";
            case 'c' -> "ц";
            case 'd' -> "д";
            case 'f' -> "ф";
            case 'g' -> "г";
            case 'h' -> "ҳ";
            case 'i' -> "и";
            case 'j' -> "ж";
            case 'k' -> "к";
            case 'l' -> "л";
            case 'm' -> "м";
            case 'n' -> "н";
            case 'o' -> "о";
            case 'p' -> "п";
            case 'q' -> "қ";
            case 'r' -> "р";
            case 's' -> "с";
            case 't' -> "т";
            case 'u' -> "у";
            case 'v' -> "в";
            case 'w' -> "в";
            case 'x' -> "х";
            case 'y' -> "й";
            case 'z' -> "з";
            default -> null;
        };
        if (single == null) {
            out.append(c);
        } else {
            out.append(upper ? single.toUpperCase() : single);
        }
        return i + 1;
    }
}
