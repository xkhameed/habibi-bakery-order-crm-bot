package uz.habibi.bakery.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Tarjimalar: i18n/messages_uz.properties va messages_ru.properties (UTF-8).
 * Kirill varianti o'zbek lotinchasidan avtomatik o'giriladi.
 * O'rinbosarlar: {0}, {1} ... (MessageFormat ishlatilmaydi, chunki o'zbekcha apostroflar uni buzadi).
 */
public final class I18n {

    private static final Map<Lang, Properties> BUNDLES = new EnumMap<>(Lang.class);

    static {
        BUNDLES.put(Lang.UZ, load("i18n/messages_uz.properties"));
        BUNDLES.put(Lang.RU, load("i18n/messages_ru.properties"));
    }

    private I18n() {
    }

    private static Properties load(String path) {
        Properties p = new Properties();
        try (InputStream in = I18n.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Tarjima fayli topilmadi: " + path);
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    public static boolean has(String key) {
        return BUNDLES.get(Lang.UZ).containsKey(key);
    }

    /** Kalit topilmasa, kalitning o'zi matn sifatida ishlatiladi. */
    public static String t(Lang lang, String key, Object... args) {
        Lang source = lang == Lang.RU ? Lang.RU : Lang.UZ;
        String text = BUNDLES.get(source).getProperty(key);
        if (text == null) {
            text = BUNDLES.get(Lang.UZ).getProperty(key, key);
        }
        // Avval shablon o'giriladi, keyin qiymatlar qo'yiladi — ism, manzil kabi qiymatlar o'zgarmaydi
        if (lang == Lang.CYR) {
            text = Translit.toCyrillic(text);
        } else if (lang == Lang.UZ) {
            text = NewAlphabet.convert(text);
        }
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /** Ma'lumotlar bazasidagi matn (mahsulot nomi va h.k.): ruscha bo'lmasa o'zbekchasi, kirill — avtomatik. */
    public static String content(Lang lang, String uz, String ru) {
        if (uz == null) {
            return null;
        }
        return switch (lang) {
            case RU -> ru == null || ru.isBlank() ? uz : ru;
            case CYR -> Translit.toCyrillic(uz);
            case UZ -> NewAlphabet.convert(uz);
        };
    }

    public static Map<Object, Object> raw(Lang lang) {
        return BUNDLES.get(lang);
    }
}
