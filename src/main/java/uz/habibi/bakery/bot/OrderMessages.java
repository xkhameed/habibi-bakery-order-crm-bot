package uz.habibi.bakery.bot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.config.Money;
import uz.habibi.bakery.domain.DeliveryType;
import uz.habibi.bakery.domain.OrderStatus;
import uz.habibi.bakery.domain.PaymentMethod;
import uz.habibi.bakery.domain.PaymentStatus;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.service.AdminAction;
import uz.habibi.bakery.service.OrderDetails;

/**
 * Bot xabarlari matni va tugmalari (HTML formatda).
 * Admin xabarlari doim o'zbek lotinchasida, mijoz xabarlari — mijoz tanlagan tilda.
 */
final class OrderMessages {

    private static final String LINE = "━━━━━━━━━━━━━━";

    private OrderMessages() {
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String month(int monthValue) {
        String m = I18n.t(Lang.UZ, "months").split(",")[monthValue - 1];
        return Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    static String date(LocalDate d) {
        return date(d, Lang.UZ);
    }

    /** 9-noyabr / 9 ноября / 9-ноябр */
    static String date(LocalDate d, Lang lang) {
        String m = I18n.t(lang, "months").split(",")[d.getMonthValue() - 1];
        return I18n.t(lang, "date.format", d.getDayOfMonth(), m);
    }

    /** «11-noyabr, 13:00–15:00» yoki vaqt tanlanmagan bo'lsa faqat sana. */
    static String when(OrderDetails o, Lang lang) {
        return date(o.deliveryDate(), lang) + (o.deliveryTime() == null ? "" : ", " + esc(o.deliveryTime()));
    }

    static String when(OrderDetails o) {
        return when(o, Lang.UZ);
    }

    static String items(OrderDetails o, Lang lang) {
        StringBuilder sb = new StringBuilder();
        for (OrderDetails.Line l : o.items()) {
            sb.append("• ").append(esc(l.name(lang)));
            if (l.variantName() != null) {
                sb.append(" (").append(esc(l.variant(lang))).append(")");
            }
            sb.append(" × ").append(l.quantity()).append(" — ").append(Money.format(l.total(), lang)).append('\n');
        }
        return sb.toString();
    }

    private static String schedule(OrderDetails o, Lang lang) {
        StringBuilder sb = new StringBuilder();
        if (o.deliveryTime() != null) {
            sb.append("📅 ").append(when(o, lang)).append('\n');
        }
        sb.append(I18n.t(lang, "delivery." + o.deliveryType().name()));
        if (o.deliveryType() == DeliveryType.DELIVERY) {
            sb.append(": ").append(esc(o.address()));
        }
        return sb.append('\n').toString();
    }

    // ================================================================ admin (o'zbekcha)

    /** Admin uchun to'liq buyurtma kartochkasi. */
    static String adminCard(OrderDetails o) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧁 <b>Buyurtma #").append(o.id()).append("</b>\n").append(LINE).append('\n');
        sb.append(items(o, Lang.UZ));
        sb.append(LINE).append('\n');
        sb.append("💰 <b>Jami: ").append(Money.format(o.itemsTotal())).append("</b>\n");
        sb.append(schedule(o, Lang.UZ));
        if (o.hasLocation()) {
            sb.append("📍 <a href=\"").append(o.yandexMapUrl()).append("\">Yandex xarita</a> · <a href=\"")
                    .append(o.googleMapUrl()).append("\">Google Maps</a>\n");
        }
        sb.append("💳 ").append(o.paymentMethod().label()).append('\n');
        sb.append(receiptState(o)).append('\n');
        sb.append("👤 <a href=\"tg://user?id=").append(o.customerTelegramId()).append("\">")
                .append(esc(o.customerName())).append("</a>, ").append(esc(o.phone()));
        if (o.customerUsername() != null) {
            sb.append(" (@").append(esc(o.customerUsername())).append(")");
        }
        sb.append('\n');
        sb.append("🌐 Mijoz tili: ").append(o.customerLanguage().title()).append('\n');
        if (o.comment() != null) {
            sb.append("💬 ").append(esc(o.comment())).append('\n');
        }
        if (o.trackingUrl() != null) {
            sb.append("📍 <a href=\"").append(esc(o.trackingUrl())).append("\">Kuryerni kuzatiş</a>\n");
        }
        if (o.inscription() != null) {
            sb.append("✍️ Tortga yozuv: <b>").append(esc(o.inscription())).append("</b>\n");
        }
        sb.append("\n📌 Holat: <b>").append(o.status().label()).append("</b>");
        String note = deliveryNote(o);
        if (note != null) {
            sb.append('\n').append(note);
        }
        if (o.rating() != null) {
            sb.append("\n").append(stars(o.rating())).append(" Mijoz bahosi: <b>").append(o.rating()).append("/5</b>");
            if (o.ratingComment() != null && !o.ratingComment().isBlank()) {
                sb.append("\n💬 <i>").append(esc(o.ratingComment())).append("</i>");
            }
        }
        return sb.toString();
    }

    static InlineKeyboardMarkup adminKeyboard(OrderDetails o) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        OrderStatus s = o.status();
        switch (s) {
            case NEW -> rows.add(row(btn("✅ Qabul qiliş", o, AdminAction.ACCEPT), btn("❌ Rad etiş", o, AdminAction.REJECT)));
            case CONFIRMED -> rows.add(row(btn("👩‍🍳 Tayyorlaş boşlandi", o, AdminAction.PREPARING)));
            case PREPARING -> rows.add(o.deliveryType() == DeliveryType.DELIVERY
                    ? row(btn("🚕 Yölga çiqdi", o, AdminAction.ON_WAY))
                    : row(btn("📦 Tayyor, olib ketsa böladi", o, AdminAction.READY)));
            case ON_THE_WAY, READY -> rows.add(row(btn("🎉 Topşirildi", o, AdminAction.DONE)));
            default -> {
            }
        }
        boolean waitingPayment = o.paymentStatus() == PaymentStatus.AWAITING_RECEIPT
                || o.paymentStatus() == PaymentStatus.RECEIPT_SENT;
        if (!s.isFinal() && o.paymentStatus() == PaymentStatus.AWAITING_RECEIPT) {
            rows.add(row(cb("🔔 Çekni eslatiş", "remind:" + o.id())));
        }
        if (!s.isFinal() && waitingPayment) {
            rows.add(row(btn("💳 Tölovni tasdiqlaş", o, AdminAction.PAY_OK)));
        }
        if (s == OrderStatus.CONFIRMED || s == OrderStatus.PREPARING) {
            rows.add(row(btn("❌ Bekor qiliş", o, AdminAction.REJECT)));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    static InlineKeyboardMarkup receiptKeyboard(OrderDetails o) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(btn("✅ Tölov tasdiqlandi", o, AdminAction.PAY_OK), btn("🚫 Rad etiş", o, AdminAction.PAY_NO)))
                .build();
    }

    /** Chek holati admin uchun: tasdiqlangan / tekshirilmagan / rad etilgan / yuborilmagan. */
    static String receiptState(OrderDetails o) {
        return switch (o.paymentStatus()) {
            case PAID -> "✅ Çek tasdiqlangan — tölov qabul qilindi";
            case RECEIPT_SENT -> "🧾 Çek yuborilgan, hali tekşirilmagan";
            case AWAITING_RECEIPT -> o.receiptFileId() != null
                    ? "❌ Çek rad etilgan — mijozdan yangi çek kutilmoqda"
                    : "⏳ Çek hali yuborilmagan";
            case PAY_ON_DELIVERY -> o.paymentStatus().label();
        };
    }

    static String receiptCaption(OrderDetails o) {
        return "🧾 <b>Buyurtma #" + o.id() + " uçun çek</b>\n"
                + "💰 Kutilgan summa: <b>" + Money.format(o.itemsTotal()) + "</b>\n"
                + "👤 " + esc(o.customerName()) + ", " + esc(o.phone()) + "\n\n"
                + "Çekdagi summa va sanani tekşiring.";
    }

    // ================================================================ mijoz (o'z tilida)

    /** Buyurtma berilgandan keyin mijozga boradigan xabar. */
    static String customerCreated(OrderDetails o, AppProperties.Shop shop) {
        Lang lang = o.customerLanguage();
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.t(lang, "order.thanks", esc(o.customerName()))).append("\n\n");
        sb.append(I18n.t(lang, "order.title", o.id())).append('\n');
        sb.append(items(o, lang));
        sb.append(I18n.t(lang, "order.total", Money.format(o.itemsTotal(), lang))).append('\n');
        sb.append(schedule(o, lang));
        if (o.inscription() != null) {
            sb.append(I18n.t(lang, "bot.inscription_line", esc(o.inscription()))).append('\n');
        }
        if (o.deliveryType() == DeliveryType.DELIVERY) {
            sb.append("<i>").append(I18n.t(lang, "shop.delivery_note")).append("</i>\n");
        }
        // manzil/raqamni botda o'zgartirib bo'lmaydi — biz bilan bog'lanish kerak
        sb.append(shop.phone() == null || shop.phone().isBlank()
                ? I18n.t(lang, "order.change_hint_noph")
                : I18n.t(lang, "order.change_hint", esc(shop.phone()))).append('\n');
        sb.append('\n');
        if (o.paymentMethod() != PaymentMethod.CASH) {
            sb.append(paymentInstructions(o, shop));
        } else {
            sb.append(I18n.t(lang, "order.cash"));
        }
        return sb.toString();
    }

    static String paymentInstructions(OrderDetails o, AppProperties.Shop shop) {
        Lang lang = o.customerLanguage();
        String sum = I18n.t(lang, "pay.sum", Money.format(o.itemsTotal(), lang));
        StringBuilder sb = new StringBuilder();
        if (shop.cardNumber() == null || shop.cardNumber().isBlank()) {
            sb.append(I18n.t(lang, "pay.card_by_operator")).append('\n');
        } else {
            sb.append(I18n.t(lang, "pay.card_title")).append("\n<code>").append(esc(shop.cardNumber())).append("</code>\n");
            if (shop.cardHolder() != null && !shop.cardHolder().isBlank()) {
                sb.append(esc(shop.cardHolder())).append('\n');
            }
        }
        sb.append(sum).append("\n\n").append(I18n.t(lang, "pay.send_receipt"));
        return sb.toString();
    }

    /** Holat o'zgarganda mijozga xabar. null bo'lsa xabar yuborilmaydi. */
    static String customerStatus(OrderDetails o, AppProperties.Shop shop) {
        Lang lang = o.customerLanguage();
        String phone = shop.phone() == null || shop.phone().isBlank() ? "" : "\n📞 " + esc(shop.phone());
        return switch (o.status()) {
            case CONFIRMED -> {
                String text = I18n.t(lang, "status.msg.CONFIRMED", o.id(), Money.format(o.itemsTotal(), lang));
                if (o.paymentStatus() == PaymentStatus.AWAITING_RECEIPT) {
                    text += "\n\n" + paymentInstructions(o, shop);
                }
                yield text;
            }
            case READY -> I18n.t(lang, "status.msg.READY", o.id()) + phone;
            case CANCELLED -> I18n.t(lang, "status.msg.CANCELLED", o.id())
                    + (phone.isEmpty() ? "" : "\n" + I18n.t(lang, "status.contact") + phone);
            case PREPARING, ON_THE_WAY, DELIVERED -> I18n.t(lang, "status.msg." + o.status().name(), o.id());
            case NEW -> null;
        };
    }

    /** Yo'ldagi buyurtma: «📍 Kuryerni xaritada kuzatish» (havola bo'lsa). */
    static InlineKeyboardMarkup trackingKeyboard(OrderDetails o) {
        if (o.status() != OrderStatus.ON_THE_WAY) {
            return null;
        }
        var kb = InlineKeyboardMarkup.builder();
        if (o.trackingUrl() != null) {
            kb.keyboardRow(row(InlineKeyboardButton.builder().text(I18n.t(o.customerLanguage(), "bot.btn.track"))
                    .url(o.trackingUrl()).build()));
        }
        kb.keyboardRow(row(cb(I18n.t(o.customerLanguage(), "bot.btn.received"), "dlv:yes:" + o.id())));
        return kb.build();
    }

    /** «Buyurtmangiz yetib keldimi?» — Ha / Yo'q (+ kuzatish havolasi). */
    static InlineKeyboardMarkup deliveryAskKeyboard(OrderDetails o) {
        Lang lang = o.customerLanguage();
        var kb = InlineKeyboardMarkup.builder()
                .keyboardRow(row(cb(I18n.t(lang, "bot.btn.delivery_yes"), "dlv:yes:" + o.id()),
                        cb(I18n.t(lang, "bot.btn.delivery_no"), "dlv:no:" + o.id())));
        if (o.trackingUrl() != null) {
            kb.keyboardRow(row(InlineKeyboardButton.builder().text(I18n.t(lang, "bot.btn.track")).url(o.trackingUrl()).build()));
        }
        return kb.build();
    }

    /** «Buyurtma qanday bo'ldi?» — 1–5 yulduz. */
    static InlineKeyboardMarkup ratingKeyboard(OrderDetails o) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            row.add(cb(i + " ⭐", "rt:" + o.id() + ":" + i));
        }
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(row)).build();
    }

    static String stars(int n) {
        return "⭐".repeat(Math.max(0, n)) + "☆".repeat(Math.max(0, 5 - n));
    }

    /** Yetkazishni kim tasdiqlagani / mijoz «hali kelmadi» degani (admin kartochkasi uchun). */
    static String deliveryNote(OrderDetails o) {
        if (o.status() == OrderStatus.DELIVERED && "CUSTOMER".equals(o.deliveredBy())) {
            return "✅ Mijoz qabul qilganini tasdiqladi";
        }
        if (o.status() == OrderStatus.DELIVERED && "AUTO".equals(o.deliveredBy())) {
            return "🤖 Avtomatik yopildi — mijoz 3 soat içida tasdiqlamadi";
        }
        if (o.status() == OrderStatus.ON_THE_WAY && o.notArrivedAt() != null) {
            return "⚠️ Mijoz: <b>hali kelmadi</b> ("
                    + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(o.notArrivedAt().atZone(java.time.ZoneId.of("Asia/Tashkent"))) + ")";
        }
        return null;
    }

    /** Chek qabul qilingach mijozga: boshqa chek yuborish va buyurtmalarim. */
    static InlineKeyboardMarkup afterReceiptKeyboard(OrderDetails o) {
        Lang lang = o.customerLanguage();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(cb(I18n.t(lang, "bot.btn.another_receipt"), "rcpt:" + o.id())))
                .keyboardRow(row(cb(I18n.t(lang, "bot.btn.my_orders"), "myorders")))
                .build();
    }

    /** Buyurtma xabari ostida: 5 daqiqa ichida bekor qilish tugmasi. */
    static InlineKeyboardMarkup cancelKeyboard(OrderDetails o) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(cb(I18n.t(o.customerLanguage(), "bot.btn.cancel_order"), "ccl:" + o.id())))
                .build();
    }

    /** Chek eslatmasi ostidagi tugma: shu buyurtmaga chek yuborish. */
    static InlineKeyboardMarkup sendReceiptKeyboard(OrderDetails o) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(row(cb(I18n.t(o.customerLanguage(), "bot.kb.receipt_old"), "rcpt:" + o.id())))
                .build();
    }

    /** «Buyurtmalarim»: oxirgi buyurtmalar ro'yxati (mijoz tilida). */
    /** «📋 Buyurtmalarim» bosh sahifasi: har guruhda nechta buyurtma borligi. */
    static String myOrdersHome(java.util.Map<OrderDetails.Group, Long> counts, long all, Lang lang) {
        if (all == 0) {
            return I18n.t(lang, "bot.my_orders.empty");
        }
        return I18n.t(lang, "bot.my_orders.title") + "\n\n"
                + I18n.t(lang, "bot.my_orders.summary", all,
                        counts.getOrDefault(OrderDetails.Group.PENDING, 0L),
                        counts.getOrDefault(OrderDetails.Group.CONFIRMED, 0L),
                        counts.getOrDefault(OrderDetails.Group.CANCELLED, 0L))
                + "\n\n" + I18n.t(lang, "bot.my_orders.choose");
    }

    /** Tanlangan filtr bo'yicha buyurtmalar — barcha ma'lumotlari bilan. */
    static String myOrders(List<OrderDetails> list, String titleKey, int hidden, java.time.ZoneId zone, Lang lang) {
        StringBuilder sb = new StringBuilder("<b>").append(I18n.t(lang, titleKey)).append("</b>\n\n");
        if (list.isEmpty()) {
            return sb.append(I18n.t(lang, "bot.my_orders.none")).toString();
        }
        for (OrderDetails o : list) {
            java.time.ZonedDateTime at = o.createdAt().atZone(zone);
            sb.append("🧾 <b>").append(I18n.t(lang, "app.order_n", o.id())).append("</b>\n")
                    .append("📅 ").append(date(at.toLocalDate(), lang))
                    .append(" · 🕐 ").append(String.format("%02d:%02d", at.getHour(), at.getMinute())).append('\n');
            for (OrderDetails.Line l : o.items()) {
                sb.append("• ").append(esc(l.name(lang)));
                if (l.variantName() != null) {
                    sb.append(" (").append(esc(l.variant(lang))).append(")");
                }
                sb.append(" × ").append(l.quantity()).append(" — ").append(Money.format(l.total(), lang)).append('\n');
            }
            sb.append("💰 ").append(I18n.t(lang, "app.total")).append(": <b>").append(Money.format(o.itemsTotal(), lang)).append("</b>\n")
                    .append(I18n.t(lang, "method." + o.paymentMethod().name())).append(" · ")
                    .append(I18n.t(lang, "payment." + o.paymentStatus().name())).append('\n')
                    .append(I18n.t(lang, "bot.my_orders.status")).append(": ")
                    .append(I18n.t(lang, "status." + o.status().name())).append("\n\n");
        }
        if (hidden > 0) {
            sb.append(I18n.t(lang, "bot.my_orders.more", hidden));
        }
        return sb.toString().trim();
    }

    static InlineKeyboardButton cb(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    static InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    private static InlineKeyboardButton btn(String text, OrderDetails o, AdminAction action) {
        return InlineKeyboardButton.builder().text(text).callbackData("o:" + o.id() + ":" + action.name()).build();
    }
}
