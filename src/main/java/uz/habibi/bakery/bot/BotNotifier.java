package uz.habibi.bakery.bot;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.telegram.telegrambots.meta.api.methods.send.SendLocation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.service.OrderDetails;
import uz.habibi.bakery.service.OrderEvent;
import uz.habibi.bakery.service.OrderService;

/** Buyurtma voqealarini admin va mijozga Telegram xabari qilib yuboradi. */
@Component
@ConditionalOnExpression(BotConfig.ENABLED)
public class BotNotifier {

    private static final Logger log = LoggerFactory.getLogger(BotNotifier.class);

    private final TelegramClient client;
    private final AppProperties props;
    private final OrderService orders;
    private final uz.habibi.bakery.service.SettingsService settings;

    public BotNotifier(TelegramClient client, AppProperties props, OrderService orders,
                       uz.habibi.bakery.service.SettingsService settings) {
        this.settings = settings;
        this.client = client;
        this.props = props;
        this.orders = orders;
    }

    @Async
    @TransactionalEventListener
    public void on(OrderEvent event) {
        OrderDetails o = event.order();
        AppProperties.Shop shop = settings.shop();
        switch (event.kind()) {
            case CREATED -> {
                // Buyurtma adminlarga darhol boradi; karta bo'lsa chek keyin alohida keladi
                send(o.customerTelegramId(), OrderMessages.customerCreated(o, shop), null);
                sendAdminCard(o);
            }
            case RECEIPT_HELD -> send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.receipt_received"),
                    OrderMessages.afterReceiptKeyboard(o));
            case RELEASED -> {
                java.util.Map<Long, Integer> cards = sendAdminCard(o);
                sendReceiptToAdmin(o, event, cards);
            }
            case CUSTOMER_CANCELLED -> send(o.customerTelegramId(),
                    I18n.t(o.customerLanguage(), "bot.order_cancelled_by_you", o.id()), null);
            case EXPIRED -> {
                send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.order_expired", o.id()), null);
                updateAdminCard(o);
                notifyAdmins("⌛️ <b>#" + o.id() + "</b> — 30 daqiqa içida çek kelmadi, buyurtma avtomatik bekor qilindi.\n"
                        + "👤 " + OrderMessages.esc(o.customerName()) + ", " + OrderMessages.esc(o.phone()), o);
            }
            case DELIVERY_ASK -> send(o.customerTelegramId(),
                    I18n.t(o.customerLanguage(), "bot.delivery_ask", o.id()), OrderMessages.deliveryAskKeyboard(o));
            case NOT_ARRIVED -> {
                updateAdminCard(o);
                notifyAdmins("⚠️ <b>#" + o.id() + "</b> — mijoz buyurtma <b>hali kelmaganini</b> aytdi! Kuryer bilan boğlaning.\n"
                        + "👤 " + OrderMessages.esc(o.customerName()) + ", " + OrderMessages.esc(o.phone()), o);
                send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.delivery_sorry"), null);
            }
            case RECEIVED -> {
                updateAdminCard(o);
                notifyAdmins("✅ <b>#" + o.id() + "</b> — mijoz buyurtmani qabul qilganini tasdiqladi ("
                        + java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.ZonedDateTime.now(props.zone())) + ")", o);
                send(o.customerTelegramId(), OrderMessages.customerStatus(o, shop), null);
                askRating(o);
            }
            case RATED -> {
                updateAdminCard(o);
                if (o.rating() != null && o.rating() <= 3) {
                    notifyAdmins("⚠️ <b>#" + o.id() + "</b> — mijoz past baho berdi: " + OrderMessages.stars(o.rating())
                            + " (" + o.rating() + "/5)\n👤 " + OrderMessages.esc(o.customerName()) + ", " + OrderMessages.esc(o.phone()), o);
                }
            }
            case RATING_COMMENT -> {
                updateAdminCard(o);
                notifyAdmins("💬 <b>#" + o.id() + "</b> — mijoz izohi (" + (o.rating() == null ? "?" : o.rating()) + "/5):\n<i>"
                        + OrderMessages.esc(o.ratingComment()) + "</i>\n👤 " + OrderMessages.esc(o.customerName()) + ", " + OrderMessages.esc(o.phone()), o);
            }
            case AUTO_DELIVERED -> {
                updateAdminCard(o);
                send(o.customerTelegramId(), OrderMessages.customerStatus(o, shop), null);
            }
            case STATUS_CHANGED -> {
                updateAdminCard(o);
                String text = OrderMessages.customerStatus(o, shop);
                if (text != null) {
                    send(o.customerTelegramId(), text, OrderMessages.trackingKeyboard(o));
                }
                if (o.status() == uz.habibi.bakery.domain.OrderStatus.DELIVERED) {
                    askRating(o);   // admin «Topshirildi» bosdi
                }
            }
            case RECEIPT_SENT -> {
                sendReceiptToAdmin(o, event, java.util.Map.of());
                updateAdminCard(o);
                send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.receipt_received"),
                        OrderMessages.afterReceiptKeyboard(o));
            }
            case PAYMENT_CONFIRMED -> {
                updateAdminCard(o);
                send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.payment_confirmed", o.id()), null);
            }
            case PAYMENT_REJECTED -> {
                updateAdminCard(o);
                send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.payment_rejected", o.id()) + "\n\n"
                        + OrderMessages.paymentInstructions(o, shop), null);
            }
        }
    }

    /** Chek rasmini barcha adminlarga yuboradi (har birida o'z kartochkasiga javob sifatida, agar u hozirgina yuborilgan bo'lsa). */
    private void sendReceiptToAdmin(OrderDetails o, OrderEvent event, java.util.Map<Long, Integer> replyTo) {
        if (event.receiptBytes() == null && event.receiptFileId() == null) {
            return;
        }
        String fileId = event.receiptFileId();
        for (Long chat : settings.adminChats()) {
            try {
                InputFile photo = fileId != null ? new InputFile(fileId)
                        : new InputFile(new java.io.ByteArrayInputStream(event.receiptBytes()), "chek-" + o.id() + ".jpg");
                Message m = client.execute(SendPhoto.builder()
                        .chatId(chat)
                        .photo(photo)
                        .caption(OrderMessages.receiptCaption(o))
                        .parseMode("HTML")
                        .replyToMessageId(replyTo.get(chat))
                        .replyMarkup(OrderMessages.receiptKeyboard(o))
                        .build());
                // keyingi adminlarga baytlarni qayta yuklamaslik uchun Telegram file_id dan foydalanamiz
                if (fileId == null && m != null && m.hasPhoto()) {
                    fileId = m.getPhoto().stream().max(java.util.Comparator.comparing(
                            org.telegram.telegrambots.meta.api.objects.photo.PhotoSize::getFileSize,
                            java.util.Comparator.nullsFirst(Integer::compare))).map(
                            org.telegram.telegrambots.meta.api.objects.photo.PhotoSize::getFileId).orElse(null);
                }
            } catch (TelegramApiException e) {
                log.error("Çekni adminga yuborib bölmadi (#{}, çat {}): {}", o.id(), chat, e.getMessage());
            }
        }
        if (fileId != null) {
            orders.saveReceiptFileId(o.id(), fileId);
        }
    }

    /** Admin buyurtmani ochdi: kartochka (lokatsiya bilan) va chek rasmi — shu chatga. */
    public void showFull(OrderDetails o, Long chat) {
        Integer card = sendCardTo(o, chat);
        if (o.receiptFileId() == null) {
            if (o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.AWAITING_RECEIPT) {
                send(chat, "🧾 #" + o.id() + " uçun çek hali yuborilmagan", null);
            }
            return;
        }
        try {
            client.execute(SendPhoto.builder()
                    .chatId(chat)
                    .photo(new InputFile(o.receiptFileId()))
                    .caption(OrderMessages.receiptCaption(o) + "\n\n<b>" + OrderMessages.receiptState(o) + "</b>")
                    .parseMode("HTML")
                    .replyToMessageId(card)
                    .replyMarkup(o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.RECEIPT_SENT
                            ? OrderMessages.receiptKeyboard(o) : null)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Çekni körsatib bölmadi (#{}): {}", o.id(), e.getMessage());
        }
    }

    /** Buyurtma kartochkasini barcha adminlarga yuboradi. Qaytaradi: chatId → messageId. */
    java.util.Map<Long, Integer> sendAdminCard(OrderDetails o) {
        java.util.Map<Long, Integer> sent = new java.util.LinkedHashMap<>();
        List<Long> chats = settings.adminChats();
        if (chats.isEmpty()) {
            log.warn("ADMIN_CHAT_ID sozlanmagan, buyurtma #{} haqida admin xabardor qilinmadi", o.id());
            return sent;
        }
        for (Long chat : chats) {
            Integer id = sendCardTo(o, chat);
            if (id != null) {
                sent.put(chat, id);
            }
        }
        return sent;
    }

    /** Kartochkani bitta admin chatiga yuboradi va eslab qoladi (holat o'zgarsa, shu xabar yangilanadi). */
    Integer sendCardTo(OrderDetails o, Long chat) {
        Message m = send(chat, OrderMessages.adminCard(o), OrderMessages.adminKeyboard(o));
        if (m == null) {
            return null;
        }
        if (settings.isMainAdmin(chat)) {
            orders.saveAdminMessageId(o.id(), m.getMessageId());
        } else {
            orders.saveAdminCopy(o.id(), chat, m.getMessageId());
        }
        if (o.hasLocation()) {
            try {
                client.execute(SendLocation.builder()
                        .chatId(chat)
                        .latitude(o.latitude()).longitude(o.longitude())
                        .replyToMessageId(m.getMessageId())
                        .build());
            } catch (TelegramApiException e) {
                log.warn("Lokatsiyani yuborib bölmadi (#{}): {}", o.id(), e.getMessage());
            }
        }
        return m.getMessageId();
    }

    /**
     * Admin «🔔 Çekni eslatiş» bosdi: mijozga eslatma + to'lov ma'lumotlari va «Chek yuborish» tugmasi.
     * Mijoz botni bloklagan bo'lsa false.
     */
    public boolean remindReceipt(OrderDetails o) {
        String text = I18n.t(o.customerLanguage(), "bot.receipt_reminder", o.id()) + "\n\n"
                + OrderMessages.paymentInstructions(o, settings.shop());
        return send(o.customerTelegramId(), text, OrderMessages.sendReceiptKeyboard(o)) != null;
    }

    /** «⭐ Buyurtma qanday bo'ldi?» — mijozga. */
    private void askRating(OrderDetails o) {
        if (o.rating() == null) {
            send(o.customerTelegramId(), I18n.t(o.customerLanguage(), "bot.rate_ask", o.id()), OrderMessages.ratingKeyboard(o));
        }
    }

    /** Barcha adminlarga qisqa xabar (buyurtmani ochish tugmasi bilan). */
    private void notifyAdmins(String html, OrderDetails o) {
        var kb = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                .keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("📋 #" + o.id() + " ni oçiş").callbackData("show:" + o.id()).build()))
                .build();
        for (Long chat : settings.adminChats()) {
            send(chat, html, kb);
        }
    }

    /** Barcha adminlardagi kartochkani yangi holat bilan yangilaydi. */
    private void updateAdminCard(OrderDetails o) {
        java.util.Map<Long, Integer> cards = new java.util.LinkedHashMap<>(o.adminCopyMap());
        if (props.bot().hasAdmin() && o.adminMessageId() != null) {
            cards.put(props.bot().adminChatId(), o.adminMessageId());
        }
        if (cards.isEmpty()) {
            sendAdminCard(o);
            return;
        }
        for (var e : cards.entrySet()) {
            if (!settings.isAdmin(e.getKey())) {
                continue; // adminlikdan olingan
            }
            try {
                client.execute(EditMessageText.builder()
                        .chatId(e.getKey())
                        .messageId(e.getValue())
                        .text(OrderMessages.adminCard(o))
                        .parseMode("HTML")
                        .replyMarkup(OrderMessages.adminKeyboard(o))
                        .build());
            } catch (TelegramApiException ex) {
                if (!String.valueOf(ex.getMessage()).contains("message is not modified")) {
                    log.warn("Admin kartoçkasini yangilab bölmadi (#{}, çat {}): {}", o.id(), e.getKey(), ex.getMessage());
                }
            }
        }
    }

    Message send(Long chatId, String html, ReplyKeyboard keyboard) {
        try {
            return client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(html)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Xabar yuborilmadi (çat {}): {}", chatId, e.getMessage());
            return null;
        }
    }
}
