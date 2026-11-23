package uz.habibi.bakery.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class I18nTest {

    @Test
    void transliteratesUzbekLatinToCyrillic() {
        assertThat(Translit.toCyrillic("Buyurtma")).isEqualTo("Буюртма");
        assertThat(Translit.toCyrillic("so'm")).isEqualTo("сўм");
        assertThat(Translit.toCyrillic("O'rtacha")).isEqualTo("Ўртача");
        assertThat(Translit.toCyrillic("Sovg'a setlari")).isEqualTo("Совға сетлари");
        assertThat(Translit.toCyrillic("ma'lumot")).isEqualTo("маълумот");
        assertThat(Translit.toCyrillic("Yetkazib berish")).isEqualTo("Етказиб бериш");
        assertThat(Translit.toCyrillic("tayyorlanadi")).isEqualTo("тайёрланади");
        assertThat(Translit.toCyrillic("yo'q")).isEqualTo("йўқ");
        assertThat(Translit.toCyrillic("Menyu")).isEqualTo("Меню");
        assertThat(Translit.toCyrillic("Shokoladli qulupnay")).isEqualTo("Шоколадли қулупнай");
        assertThat(Translit.toCyrillic("Toshkent bo'ylab")).isEqualTo("Тошкент бўйлаб");
        assertThat(Translit.toCyrillic("Ertaga")).isEqualTo("Эртага");
        assertThat(Translit.toCyrillic("mehr bilan")).isEqualTo("меҳр билан");
        assertThat(Translit.toCyrillic("Kuryer aniq joyni topadi")).isEqualTo("Курьер аниқ жойни топади");
        assertThat(Translit.toCyrillic("noyabr,dekabr")).isEqualTo("ноябрь,декабрь");
        assertThat(Translit.toCyrillic("9-sentabrda")).isEqualTo("9-сентябрда");
        assertThat(Translit.toCyrillic("Damskiy palchik")).isEqualTo("Дамский пальчик");
        assertThat(Translit.toCyrillic("QR kodni")).isEqualTo("QR кодни");
    }

    @Test
    void keepsHtmlPlaceholdersLinksAndBrands() {
        assertThat(Translit.toCyrillic("<b>Jami: {0}</b>")).isEqualTo("<b>Жами: {0}</b>");
        assertThat(Translit.toCyrillic("Habibi Bakery — Click va Payme")).isEqualTo("Habibi Bakery — Click ва Payme");
        assertThat(Translit.toCyrillic("havola https://yandex.uz/maps")).isEqualTo("ҳавола https://yandex.uz/maps");
        assertThat(Translit.toCyrillic("&amp; bor")).isEqualTo("&amp; бор");
        assertThat(Translit.toCyrillic("Habibi set №3")).isEqualTo("Habibi сет №3");
    }

    @Test
    void argumentsAreNotTransliterated() {
        // Mijoz ismi va manzili lotincha qolishi kerak
        assertThat(I18n.t(Lang.CYR, "order.thanks", "Ali")).isEqualTo("💖 Раҳмат, Ali! Буюртмангиз қабул қилинди.");
        assertThat(I18n.t(Lang.UZ, "order.thanks", "Shahnoza")).isEqualTo("💖 Rahmat, Shahnoza! Buyurtmangiz qabul qilindi.");
        assertThat(I18n.t(Lang.RU, "order.thanks", "Ali")).isEqualTo("💖 Спасибо, Ali! Ваш заказ принят.");
    }

    @Test
    void contentFallsBackToUzbek() {
        assertThat(I18n.content(Lang.RU, "Chocoboom", null)).isEqualTo("Chocoboom");
        assertThat(I18n.content(Lang.RU, "Dubai torti", "Торт «Дубай»")).isEqualTo("Торт «Дубай»");
        assertThat(I18n.content(Lang.CYR, "Dubai torti", "Торт «Дубай»")).isEqualTo("Дубай торти");
        assertThat(I18n.content(Lang.UZ, "Dubai torti", "Торт «Дубай»")).isEqualTo("Dubai torti");
    }

    @Test
    void russianFileHasEveryUzbekKey() {
        Set<Object> missing = new TreeSet<>(I18n.raw(Lang.UZ).keySet());
        missing.removeAll(I18n.raw(Lang.RU).keySet());
        missing.remove("lang.choose"); // uchala tilda yozilgan, umumiy
        assertThat(missing).isEmpty();
    }

    @Test
    void newUzbekAlphabet() {
        assertThat(NewAlphabet.convert("O'zbekcha")).isEqualTo("Özbekça");
        assertThat(NewAlphabet.convert("Sovg'a setlari")).isEqualTo("Sovğa setlari");
        assertThat(NewAlphabet.convert("Shokoladli shirinliklar")).isEqualTo("Şokoladli şirinliklar");
        assertThat(NewAlphabet.convert("ma'lumot")).isEqualTo("maʼlumot");
        assertThat(NewAlphabet.convert("Chocoboom va Dubai chocoberry")).isEqualTo("Chocoboom va Dubai chocoberry");
        assertThat(NewAlphabet.convert("<b>Click</b> https://yandex.uz/maps?ch=1")).isEqualTo("<b>Click</b> https://yandex.uz/maps?ch=1");
        // qayta qo'llash xavfsiz
        assertThat(NewAlphabet.convert(NewAlphabet.convert("Toshkent bo'ylab"))).isEqualTo("Toşkent böylab");
    }

    @Test
    void cyrillicFromNewAlphabet() {
        assertThat(Translit.toCyrillic("Toşkent böylab")).isEqualTo("Тошкент бўйлаб");
        assertThat(Translit.toCyrillic("Sovğa")).isEqualTo("Совға");
        assertThat(Translit.toCyrillic("Çek rasmini")).isEqualTo("Чек расмини");
        assertThat(Translit.toCyrillic("maʼlumot")).isEqualTo("маълумот");
        assertThat(Translit.toCyrillic("Damskiy palçik")).isEqualTo("Дамский пальчик");
    }

    @Test
    void uzbekMessagesUseNewAlphabet() {
        assertThat(I18n.t(Lang.UZ, "bot.btn.share_phone")).isEqualTo("📱 Raqamni yuboriş");
        assertThat(I18n.t(Lang.CYR, "bot.btn.share_phone")).isEqualTo("📱 Рақамни юбориш");
        assertThat(I18n.content(Lang.UZ, "Shokoladli qulupnay", null)).isEqualTo("Şokoladli qulupnay");
        for (Object k : I18n.raw(Lang.UZ).keySet()) {
            String v = I18n.t(Lang.UZ, (String) k);
            assertThat(v).as(k.toString()).doesNotContain("o'", "g'", "O'", "G'");
        }
    }

    @Test
    void confirmedMessageLooksLikeTemplate() {
        assertThat(I18n.t(Lang.UZ, "status.msg.CONFIRMED", 1024, "150 000 söm")).isEqualTo("""
                🍰 <b>Habibi Bakery</b>
                Buyurtmangiz qabul qilindi!

                🧾 Buyurtma: <b>#1024</b>
                💰 Jami: <b>150 000 söm</b>

                Buyurtmangiz tayyor bölganda sizga xabar beramiz.
                Rahmat, Habibi Bakeryʼni tanlaganingiz uçun! ❤️""");
        assertThat(I18n.t(Lang.CYR, "status.msg.CONFIRMED", 1024, "150 000 сўм"))
                .doesNotContain("Ҳолати")
                .contains("Буюртмангиз қабул қилинди!", "Раҳмат, Habibi Bakeryʼни танлаганингиз учун! ❤️");
        assertThat(I18n.t(Lang.RU, "status.msg.CONFIRMED", 1024, "150 000 сум"))
                .doesNotContain("Статус").contains("Ваш заказ принят!", "Спасибо, что выбрали Habibi Bakery! ❤️");
    }

    @Test
    void blockedMessage() {
        assertThat(I18n.t(Lang.UZ, "bot.blocked", I18n.t(Lang.UZ, "bot.blocked_contact", "+998 99 827 93 47")))
                .isEqualTo("⛔ Hisobingiz çeklangan. Buyurtma beriş imkoniyati vaqtinça öçirilgan.\nSavollar böyiça: +998 99 827 93 47");
        assertThat(I18n.t(Lang.RU, "bot.blocked", "")).startsWith("⛔ Ваш аккаунт ограничен.");
    }

    @Test
    void langCodes() {
        assertThat(Lang.fromCode("ru")).isEqualTo(Lang.RU);
        assertThat(Lang.fromCode("cyr")).isEqualTo(Lang.CYR);
        assertThat(Lang.fromCode(null)).isEqualTo(Lang.UZ);
        assertThat(Lang.fromTelegram("ru-RU")).isEqualTo(Lang.RU);
        assertThat(Lang.fromTelegram("en")).isEqualTo(Lang.UZ);
    }
}
