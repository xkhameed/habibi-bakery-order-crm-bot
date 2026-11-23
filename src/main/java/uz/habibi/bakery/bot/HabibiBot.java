package uz.habibi.bakery.bot;

import static uz.habibi.bakery.bot.OrderMessages.esc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.config.Money;
import uz.habibi.bakery.domain.Category;
import uz.habibi.bakery.domain.PaymentMethod;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.domain.Product;
import uz.habibi.bakery.domain.ProductVariant;
import uz.habibi.bakery.service.*;

/** Telegram'dan kelgan xabar va tugma bosishlarni qayta ishlaydi. */
@Component
@ConditionalOnExpression(BotConfig.ENABLED)
public class HabibiBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(HabibiBot.class);

    // ---- Admin paneli tugmalari (pastki doimiy klaviatura) ----
    static final String BTN_ORDERS = "📋 Buyurtmalar";
    static final String BTN_STATS = "📊 Statistika";
    static final String BTN_STOPLIST = "⛔ Mahsulot tugadi";
    static final String BTN_SOLDOUT = "🚫 Tugagan mahsulotlar";
    /** Eski klaviaturadagi nomi (yangilanmagan admin klaviaturasi uchun). */
    static final String BTN_STOPLIST_OLD = "⛔ Stop-list";
    static final String BTN_PRODUCTS = "📦 Mahsulotlar";
    static final String BTN_PRICE = "💰 Narxni özgartiriş";
    static final String BTN_PHOTO = "📷 Rasm qöyiş";
    static final String BTN_CUSTOMERS = "👥 Mijozlar";
    static final String BTN_PROMO = "🏷 Aksiya";
    static final String BTN_BANNER = "🖼 Bosh rasm";
    static final String BTN_HELP = "❓ Yordam";
    static final String BTN_SET = "🎁 Set tarkibi";
    static final String BTN_SETTINGS = "⚙️ Sozlamalar";
    static final String BTN_BROADCAST = "📢 Xabar yuborish";

    /** Admin hozir nimani kutayapti: yangi narxni yoki mahsulot rasmini. */
    private sealed interface Pending permits AwaitPrice, AwaitPhoto, AwaitDiscount, AwaitBanner, AwaitContents,
            AwaitSearch, AwaitCustomerSearch, AwaitTracking, AwaitAddSize, AwaitCategory, AwaitCategoryRename, AwaitNewProduct, AwaitRename, AwaitHours, AwaitCard, AwaitBroadcast, AwaitBroadcastConfirm, AwaitAdminPhone {
    }

    private record AwaitSearch() implements Pending {
    }

    private record AwaitCustomerSearch() implements Pending {
    }

    private record AwaitTracking(Long orderId) implements Pending {
    }

    private record AwaitAddSize(Long productId, String size) implements Pending {
    }

    /** Yangi kategoriya: name null — nomi kutilmoqda, aks holda emoji tanlanmoqda. */
    private record AwaitCategory(String name) implements Pending {
    }

    private record AwaitCategoryRename(Long categoryId) implements Pending {
    }

    private static final List<String> CATEGORY_EMOJI = List.of("🎂", "🍰", "🧁", "🍪", "🍩", "🍫", "🥐", "🍮", "🍓", "🎁", "☕", "🥧");

    /**
     * Yangi mahsulot: 1 — nomi, 2 — tavsifi, 3 — narxlari, 4 — rasmi.
     * Set bo'lsa (set = true): tavsifdan keyin 10 — tarkibi, keyin 3 — bitta narx, 4 — rasmi.
     */
    private record AwaitNewProduct(int step, Long categoryId, String name, String description,
                                   List<MenuService.NewVariant> sizes, boolean set, String contents) implements Pending {
        AwaitNewProduct next(int step) {
            return new AwaitNewProduct(step, categoryId, name, description, sizes, set, contents);
        }
    }

    private record AwaitRename(Long productId, boolean description) implements Pending {
    }

    private record AwaitHours() implements Pending {
    }

    /** number null — karta raqami kutilmoqda, aks holda — karta egasining ismi. */
    private record AwaitCard(String number) implements Pending {
    }

    private record AwaitBroadcast() implements Pending {
    }

    private record AwaitBroadcastConfirm(Long fromChat, Integer messageId) implements Pending {
    }

    private record AwaitAdminPhone() implements Pending {
    }

    private record AwaitDiscount(Long productId, String title, Long categoryId) implements Pending {
    }

    private record AwaitBanner() implements Pending {
    }

    private record AwaitContents(Long productId, String title, Long categoryId) implements Pending {
    }


    private record AwaitPrice(Long variantId, String title) implements Pending {
    }

    private record AwaitPhoto(Long productId, String title) implements Pending {
    }

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** Chek eslatmasi oxirgi marta qachon yuborilgan (orderId → vaqt), ketma-ket bosishdan himoya. */
    private final Map<Long, Long> lastReminder = new ConcurrentHashMap<>();

    /** Mijoz «Boshqa chek yuborish» bosgan: keyingi rasm shu buyurtmaga boradi (chatId → orderId). */
    private final Map<Long, Long> receiptTarget = new ConcurrentHashMap<>();
    /** Past baho bergan mijozdan izoh kutilmoqda: chatId → buyurtma. */
    private final Map<Long, Long> ratingComment = new ConcurrentHashMap<>();
    /** Bir nechta buyurtma kutilayotganda yuborilgan chek — qaysi buyurtmaga tanlanguncha (chatId → file_id). */
    private final Map<Long, String> pendingReceiptPhoto = new ConcurrentHashMap<>();

    /** /mijoz — admin o'z chatida mijoz bo'lib sinab ko'rmoqda (buyurtma tugmalari baribir ishlaydi). */
    private final java.util.Set<Long> customerMode = ConcurrentHashMap.newKeySet();

    private final TelegramClient client;
    private final TelegramBotsLongPollingApplication botsApp;
    private final AppProperties props;
    private final MenuService menu;
    private final OrderService orders;
    private final BotNotifier notifier;
    private final CustomerService customers;
    @org.springframework.beans.factory.annotation.Autowired
    private HomeBannerService banner;

    private final uz.habibi.bakery.service.SettingsService settings;

    public HabibiBot(TelegramClient client, TelegramBotsLongPollingApplication botsApp, AppProperties props,
                     MenuService menu, OrderService orders, BotNotifier notifier,
                     CustomerService customers, uz.habibi.bakery.service.SettingsService settings) {
        this.settings = settings;
        this.customers = customers;
        this.client = client;
        this.botsApp = botsApp;
        this.props = props;
        this.menu = menu;
        this.orders = orders;
        this.notifier = notifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws TelegramApiException {
        botsApp.registerBot(props.bot().token(), this);
        if (props.bot().hasWebApp()) {
            // Chat pastidagi "Menyu" tugmasi Mini App'ni ochadi
            client.execute(SetChatMenuButton.builder()
                    .menuButton(MenuButtonWebApp.builder().text("🧁 Menyu").webAppInfo(new WebAppInfo(props.bot().webAppUrl())).build())
                    .build());
            refreshChatMenuButtons();
        } else {
            log.warn("WEB_APP_URL https bilan boşlanmaydi — Mini App tugmasi örnatilmadi");
        }
        for (Lang l : List.of(Lang.UZ, Lang.RU)) {
            client.execute(SetMyCommands.builder()
                    .languageCode(l == Lang.RU ? "ru" : null)
                    .command(new BotCommand("start", I18n.t(l, "bot.cmd.start")))
                    .command(new BotCommand("orders", I18n.t(l, "bot.cmd.orders")))
                    .command(new BotCommand("lang", I18n.t(l, "bot.cmd.lang")))
                    .build());
        }
        if (props.bot().hasAdmin()) {
            // Admin uchun ham faqat /start — qolgan hamma narsa tugmalarda
            client.execute(SetMyCommands.builder()
                    .scope(new BotCommandScopeChat(String.valueOf(props.bot().adminChatId())))
                    .command(new BotCommand("start", "Admin panelni oçiş"))
                    .build());
        }
        log.info("Telegram bot işga tuşdi");
    }

    /** Bloklangan mijozga cheklov xabari oxirgi marta qachon yuborilgan (spamdan himoya). */
    private final Map<Long, Long> lastBlockedNotice = new ConcurrentHashMap<>();

    /** Bloklangan mijoz bo'lsa — cheklov xabarini beradi va true qaytaradi (update qayta ishlanmaydi). */
    private boolean rejectIfBlocked(Update update) {
        org.telegram.telegrambots.meta.api.objects.User from = update.hasCallbackQuery() ? update.getCallbackQuery().getFrom()
                : update.hasMessage() ? update.getMessage().getFrom() : null;
        if (from == null || isAdmin(from.getId()) || !customers.isBlocked(from.getId())) {
            return false;
        }
        String text = blockedText(langOf(from));
        if (update.hasCallbackQuery()) {
            try {
                client.execute(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId())
                        .text(text.replaceAll("<[^>]+>", "")).showAlert(true).build());
            } catch (TelegramApiException e) {
                log.debug("Callback javobi yuborilmadi: {}", e.getMessage());
            }
        } else {
            Long last = lastBlockedNotice.get(from.getId());
            if (last == null || System.currentTimeMillis() - last > 10 * 60_000) {
                lastBlockedNotice.put(from.getId(), System.currentTimeMillis());
                reply(update.getMessage().getChatId(), text,
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder().removeKeyboard(true).build());
            }
        }
        return true;
    }

    private String blockedText(Lang lang) {
        String phone = props.shop().phone() == null || props.shop().phone().isBlank() ? ""
                : I18n.t(lang, "bot.blocked_contact", esc(props.shop().phone()));
        return I18n.t(lang, "bot.blocked", phone);
    }

    @Override
    public void consume(Update update) {
        try {
            if (rejectIfBlocked(update)) {
                return;
            }
            if (update.hasCallbackQuery()) {
                onCallback(update.getCallbackQuery());
            } else if (update.hasMessage()) {
                onMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Updateʼni qayta işlaşda xato", e);
        }
    }

    // ------------------------------------------------------------------ xabarlar

    private void onMessage(Message m) {
        Long chatId = m.getChatId();
        boolean admin = isAdminView(chatId);

        if (m.hasContact() && m.isUserMessage()) {
            onContact(m);
            return;
        }
        if (admin && pending.get(chatId) instanceof AwaitBroadcast
                && !(m.hasText() && (ADMIN_BUTTONS.contains(m.getText().trim()) || m.getText().startsWith("/")))) {
            captureBroadcast(chatId, m.getMessageId());
            return;
        }
        if (m.hasPhoto()) {
            onPhoto(m, admin);
            return;
        }
        if (m.hasDocument() && m.isUserMessage()) {
            reply(chatId, admin ? "Iltimos, rasmni <b>rasm</b> sifatida yuboring (fayl emas) 🙏"
                    : I18n.t(langOf(m.getFrom()), "bot.send_as_photo"), null);
            return;
        }
        if (!m.hasText()) {
            return;
        }

        String text = m.getText().trim();
        String[] parts = text.split("\\s+");
        String command = parts[0].contains("@") ? parts[0].substring(0, parts[0].indexOf('@')) : parts[0];

        if (command.equals("/start")) {
            pending.remove(chatId);
            if (admin) {
                reply(chatId, "👩‍🍳 <b>Admin panel</b>\nPastdagi tugmalardan foydalaning.\n\n"
                        + "Mijoz sifatida menyuni köriş uçun çat pastidagi «🧁 Menyu» tugmasini bosing.", adminKeyboard());
            } else if (m.isUserMessage()) {
                customers.language(m.getFrom().getId()).ifPresentOrElse(
                        lang -> continueOnboarding(chatId, m.getFrom(), lang),
                        () -> sendLanguagePicker(chatId, null));
            }
            return;
        }
        if (command.equals("/orders") && m.isUserMessage()) {
            sendMyOrders(chatId, m.getFrom(), null);
            return;
        }
        if (command.equals("/lang") && m.isUserMessage()) {
            sendLanguagePicker(chatId, null);
            return;
        }
        if (command.equals("/mijoz") && isAdmin(chatId)) {
            customerMode.add(chatId);
            pending.remove(chatId);
            customers.resetProfile(m.getFrom().getId());
            reply(chatId, "🧪 <b>Mijoz rejimi</b>: ro'yxatdan o'tishni boshidan ko'rasiz. "
                    + "Admin panelga qaytish: /admin", org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
                    .builder().removeKeyboard(true).build());
            sendLanguagePicker(chatId, null);
            return;
        }
        if (command.equals("/admin") && isAdmin(chatId)) {
            customerMode.remove(chatId);
            reply(chatId, "👩‍🍳 <b>Admin panel</b>", adminKeyboard());
            return;
        }
        if (command.equals("/admin_id")) {
            reply(chatId, "Bu çat ID: <code>" + chatId + "</code>\nUni <code>ADMIN_CHAT_ID</code> sozlamasiga yozing.", null);
            return;
        }
        if (admin) {
            onAdminText(chatId, text, command, parts);
            return;
        }
        if (m.isUserMessage()) {
            String button = customerButton(text);
            boolean onboarded = customers.isOnboarded(m.getFrom().getId());
            if (button != null && onboarded) {
                onCustomerButton(chatId, m.getFrom(), button);
                return;
            }
            if (button != null) {
                customers.language(m.getFrom().getId()).ifPresentOrElse(
                        lang -> continueOnboarding(chatId, m.getFrom(), lang), () -> sendLanguagePicker(chatId, null));
                return;
            }
            Long ratedOrder = ratingComment.remove(chatId);
            if (ratedOrder != null && !text.startsWith("/")) {
                Lang lang = langOf(m.getFrom());
                try {
                    orders.rateComment(m.getFrom().getId(), ratedOrder, text);
                    reply(chatId, I18n.t(lang, "bot.rate_comment_thanks"), null);
                } catch (BusinessException e) {
                    reply(chatId, "⚠️ " + e.localized(lang), null);
                }
                return;
            }
            if (handleOnboardingText(chatId, m.getFrom(), text)) {
                return;
            }
            Lang lang = langOf(m.getFrom());
            reply(chatId, I18n.t(lang, "bot.help"), customerKeyboard(lang));
        }
    }

    private void onPhoto(Message m, boolean admin) {
        Long chatId = m.getChatId();
        String caption = m.getCaption() == null ? "" : m.getCaption().trim();
        String fileId = largest(m.getPhoto());

        if (admin && pending.get(chatId) instanceof AwaitPhoto p) {
            pending.remove(chatId);
            savePhoto(chatId, p.productId(), fileId);
            return;
        }
        if (admin && pending.get(chatId) instanceof AwaitNewProduct np && np.step() == 4) {
            pending.remove(chatId);
            saveNewProduct(chatId, np, fileId);
            return;
        }
        if (admin && pending.get(chatId) instanceof AwaitBanner) {
            pending.remove(chatId);
            banner.set(fileId);
            reply(chatId, "✅ <b>Bosh rasm</b> saqlandi. Mini App'ni qayta oçsangiz, bosh sahifada körinadi.",
                    InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                            inline("‹ Bosh rasm menyusi", "bnr:menu"), inline("✅ Tayyor", "done"))).build());
            return;
        }
        if (admin && caption.startsWith("/rasm")) { // eski usul ham ishlayveradi
            String[] parts = caption.split("\\s+");
            try {
                savePhoto(chatId, Long.parseLong(parts[1]), fileId);
            } catch (RuntimeException e) {
                reply(chatId, "Mahsulot rasmini qöyiş uçun «" + BTN_PHOTO + "» tugmasini bosing.", adminKeyboard());
            }
            return;
        }
        if (m.isUserMessage()) {
            onCustomerReceipt(chatId, m.getFrom(), fileId, admin);
        }
    }

    /** Mijoz chek yubordi: qaysi buyurtma uchunligini aniqlaymiz. */
    private void onCustomerReceipt(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, String fileId, boolean admin) {
        Lang lang = langOf(from);
        Long target = receiptTarget.remove(chatId);
        try {
            if (target != null) {
                orders.attachReceiptToOrder(from.getId(), target, fileId);
                return;
            }
            List<OrderDetails> awaiting = orders.awaitingReceipt(from.getId());
            if (awaiting.size() == 1) {
                orders.attachReceiptToOrder(from.getId(), awaiting.getFirst().id(), fileId);
            } else if (awaiting.size() > 1) {
                pendingReceiptPhoto.put(chatId, fileId);
                List<InlineKeyboardButton> buttons = awaiting.stream()
                        .map(o -> inline("#" + o.id() + " · " + Money.format(o.itemsTotal(), lang), "rcptfor:" + o.id()))
                        .toList();
                reply(chatId, I18n.t(lang, "bot.choose_order_for_receipt"), grid(buttons, 1));
            } else if (admin) {
                reply(chatId, "🤔 Çek kutilayotgan buyurtma yöq.\n\nMahsulotga rasm qöymoqçi bölsangiz, avval «"
                        + BTN_PHOTO + "» tugmasini bosing.", adminKeyboard());
            } else {
                reply(chatId, I18n.t(lang, "bot.receipt_no_order"), customerKeyboard(lang));
            }
        } catch (BusinessException e) {
            reply(chatId, "⚠️ " + e.localized(lang), customerKeyboard(lang));
        }
    }

    /** «📋 Buyurtmalarim»: filtr tugmalari (to'lov kutilayotgan / tasdiqlangan / bekor qilingan / barchasi). */
    private void sendMyOrders(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, Integer messageId) {
        Lang lang = langOf(from);
        List<OrderDetails> all = orders.myOrders(from.getId());
        var counts = all.stream().collect(java.util.stream.Collectors.groupingBy(OrderDetails::group, java.util.stream.Collectors.counting()));
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (!all.isEmpty()) {
            rows.addAll(myOrdersFilterRows(lang, counts, all.size(), null));
        }
        if (props.bot().hasWebApp()) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(I18n.t(lang, "bot.btn.open_menu")).webApp(new WebAppInfo(webAppUrl(lang))).build()));
        }
        show(chatId, messageId, OrderMessages.myOrdersHome(counts, all.size(), lang), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private static final int MY_ORDERS_PAGE = 8;

    /** Bitta filtr bo'yicha buyurtmalar ro'yxati (xabar tahrirlanadi). f: P / C / X / A */
    private void sendMyOrdersFiltered(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, Integer messageId, String f) {
        Lang lang = langOf(from);
        List<OrderDetails> all = orders.myOrders(from.getId());
        var counts = all.stream().collect(java.util.stream.Collectors.groupingBy(OrderDetails::group, java.util.stream.Collectors.counting()));
        // P — to'lov kutilayotganlar, qolgani (H; eski C/X/A tugmalari ham) — «Buyurtmalarim»
        boolean pendingOnly = f.equals("P");
        List<OrderDetails> list = all.stream().filter(o -> (o.group() == OrderDetails.Group.PENDING) == pendingOnly).toList();
        List<OrderDetails> page = list.stream().limit(MY_ORDERS_PAGE).toList();
        String titleKey = pendingOnly ? "bot.my_orders.f.PENDING" : "bot.my_orders.f.HISTORY";
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (OrderDetails o : page) {
            if (o.group() == OrderDetails.Group.PENDING) {
                rows.add(new InlineKeyboardRow(inline(I18n.t(lang, "bot.my_orders.send_receipt", o.id()), "rcpt:" + o.id())));
            }
        }
        rows.addAll(myOrdersFilterRows(lang, counts, all.size(), f));
        rows.add(new InlineKeyboardRow(inline(I18n.t(lang, "bot.my_orders.back"), "mo:H")));
        show(chatId, messageId, OrderMessages.myOrders(page, titleKey, list.size() - page.size(), props.zone(), lang),
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private List<InlineKeyboardRow> myOrdersFilterRows(Lang lang, java.util.Map<OrderDetails.Group, Long> counts, int all, String current) {
        java.util.function.BiFunction<String, Long, InlineKeyboardButton> b = (f, n) -> {
            String key = "bot.my_orders.btn." + f;
            String text = I18n.t(lang, key, n);
            return inline(f.equals(current) ? "• " + text + " •" : text, "mo:" + f);
        };
        long pending = counts.getOrDefault(OrderDetails.Group.PENDING, 0L);
        return List.of(
                new InlineKeyboardRow(b.apply("P", pending)),
                new InlineKeyboardRow(b.apply("H", all - pending)));
    }

    /** Mijozning tugmalari (admin tekshiruvisiz). true — callback ishlatildi. */
    private boolean onCustomerCallback(CallbackQuery cq, Long chatId, Integer messageId, String data) {
        var from = cq.getFrom();
        Lang lang = langOf(from);
        if (data.equals("terms:ok")) {
            if (customers.name(from.getId()).isEmpty() || !customers.hasVerifiedPhone(from.getId())) {
                answer(cq, null);
                continueOnboarding(chatId, from, lang);
                return true;
            }
            customers.acceptTerms(from.getId());
            answer(cq, I18n.t(lang, "bot.terms_accepted_short"));
            try {
                client.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId)
                        .replyMarkup(InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                                inline(I18n.t(lang, "bot.terms_accepted_short"), "noop"))).build()).build());
            } catch (TelegramApiException e) {
                log.debug("Shartlar tugmasini yangilab bölmadi: {}", e.getMessage());
            }
            sendWelcome(chatId, from, lang);
            return true;
        }
        if (data.equals("noop")) {
            answer(cq, null);
            return true;
        }
        if (data.equals("myorders")) {
            answer(cq, null);
            sendMyOrders(chatId, from, null);
            return true;
        }
        if (data.startsWith("mo:")) {
            answer(cq, null);
            String f = data.substring(3);
            if (f.equals("H")) {
                sendMyOrders(chatId, from, messageId);
            } else {
                sendMyOrdersFiltered(chatId, from, messageId, f);
            }
            return true;
        }
        if (data.startsWith("rcpt:")) {
            Long orderId = Long.parseLong(data.substring(5));
            receiptTarget.put(chatId, orderId);
            pendingReceiptPhoto.remove(chatId);
            answer(cq, null);
            reply(chatId, I18n.t(lang, "bot.send_new_receipt", orderId), null);
            return true;
        }
        if (data.startsWith("rt:")) {
            String[] parts = data.split(":");
            try {
                Long orderId = Long.parseLong(parts[1]);
                int stars = Integer.parseInt(parts[2]);
                orders.rateOrder(from.getId(), orderId, stars);
                answer(cq, I18n.t(lang, "bot.rate_thanks_short"));
                show(chatId, messageId, I18n.t(lang, "bot.rate_thanks", orderId, OrderMessages.stars(stars)), null);
                if (stars <= 3) {
                    ratingComment.put(chatId, orderId);
                    reply(chatId, I18n.t(lang, "bot.rate_why"), InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(inline(I18n.t(lang, "bot.btn.rate_skip"), "rtskip"))).build());
                }
            } catch (BusinessException e) {
                answerAlert(cq, e.localized(lang));
            }
            return true;
        }
        if (data.equals("rtskip")) {
            ratingComment.remove(chatId);
            answer(cq, null);
            show(chatId, messageId, I18n.t(lang, "bot.rate_skipped"), null);
            return true;
        }
        if (data.startsWith("dlv:")) {
            // «✅ Qabul qildim / Ha, oldim» yoki «❌ Hali kelmadi»
            String[] parts = data.split(":");
            try {
                if (parts[1].equals("yes")) {
                    orders.customerReceived(from.getId(), Long.parseLong(parts[2]));
                    answer(cq, I18n.t(lang, "bot.received_short"));
                } else {
                    orders.customerNotArrived(from.getId(), Long.parseLong(parts[2]));
                    answer(cq, null);
                }
                client.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(null).build());
            } catch (BusinessException e) {
                answerAlert(cq, e.localized(lang));
            } catch (TelegramApiException e) {
                log.debug("Tugmani olib taşlab bölmadi: {}", e.getMessage());
            }
            return true;
        }
        if (data.startsWith("ccl:")) {
            try {
                OrderDetails o = orders.cancelByCustomer(from.getId(), Long.parseLong(data.substring(4)));
                answer(cq, I18n.t(lang, "bot.order_cancelled_short", o.id()));
                client.execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(null).build());
            } catch (BusinessException e) {
                answerAlert(cq, e.localized(lang));
            } catch (TelegramApiException e) {
                log.warn("Bekor qilish tugmasini olib taşlab bölmadi: {}", e.getMessage());
            }
            return true;
        }
        if (data.startsWith("rcptfor:")) {
            String fileId = pendingReceiptPhoto.remove(chatId);
            answer(cq, null);
            if (fileId == null) {
                sendMyOrders(chatId, from, null);
                return true;
            }
            try {
                orders.attachReceiptToOrder(from.getId(), Long.parseLong(data.substring(8)), fileId);
                show(chatId, messageId, "✅ #" + data.substring(8), null);
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.localized(lang), null);
            }
            return true;
        }
        return false;
    }

    private void savePhoto(Long chatId, Long productId, String fileId) {
        try {
            String name = menu.setPhoto(productId, fileId);
            reply(chatId, "📷 «" + esc(name) + "» rasmi yangilandi. Menyuda darhol körinadi.\n\nYana rasm qöyasizmi?",
                    afterEditKeyboard("photo", categoryOf(productId)));
        } catch (BusinessException e) {
            reply(chatId, "⚠️ " + e.getMessage(), adminKeyboard());
        }
    }

    /** Rasm yoki narx saqlangandan keyin: shu kategoriyaga qaytish, boshqa kategoriya yoki tugatish. */
    private InlineKeyboardMarkup afterEditKeyboard(String mode, Long categoryId) {
        String again = switch (mode) {
            case "photo" -> "📷 Şu kategoriyadan yana";
            case "promo" -> "🏷 Şu kategoriyadan yana";
            case "set" -> "🎁 Boşqa set";
            default -> "💰 Şu kategoriyadan yana";
        };
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (categoryId != null) {
            rows.add(new InlineKeyboardRow(inline("‹ " + again, "cat:" + mode + ":" + categoryId)));
        }
        rows.add(new InlineKeyboardRow(inline("📂 Boşqa kategoriya", "back:" + mode), inline("✅ Tayyor", "done")));
        if (mode.equals("promo")) {
            rows.add(new InlineKeyboardRow(inline("📋 Aksiyadagi mahsulotlar", "promo:list")));
            rows.add(new InlineKeyboardRow(inline("📢 Mijozlarga e'lon qiliş", "promo:ann")));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private Long categoryOf(Long productId) {
        return menu.allProducts().stream().filter(p -> p.getId().equals(productId))
                .map(p -> p.getCategory().getId()).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ admin paneli

    private void onAdminText(Long chatId, String text, String command, String[] parts) {
        switch (text) {
            case BTN_ORDERS -> { pending.remove(chatId); sendActiveOrders(chatId); return; }
            case BTN_STATS -> { pending.remove(chatId); sendStats(chatId, null); return; }
            case BTN_STOPLIST, BTN_STOPLIST_OLD -> { pending.remove(chatId); sendStopList(chatId); return; }
            case BTN_SOLDOUT -> { pending.remove(chatId); sendSoldOut(chatId, null); return; }
            case BTN_PRODUCTS -> { pending.remove(chatId); sendProductsMenu(chatId, null); return; }
            case BTN_SETTINGS -> { pending.remove(chatId); sendSettings(chatId, null); return; }
            case BTN_BROADCAST -> { pending.remove(chatId); startBroadcast(chatId); return; }
            case BTN_PRICE -> { pending.remove(chatId); sendCategoryPicker(chatId, null, "price"); return; }
            case BTN_PHOTO -> { pending.remove(chatId); sendCategoryPicker(chatId, null, "photo"); return; }
            case BTN_CUSTOMERS -> { pending.remove(chatId); sendCustomers(chatId, null, 0); return; }
            case BTN_PROMO -> { pending.remove(chatId); sendPromoMenu(chatId, null); return; }
            case BTN_BANNER -> { pending.remove(chatId); sendBannerMenu(chatId, null); return; }
            case BTN_HELP -> { pending.remove(chatId); reply(chatId, adminHelp(), adminKeyboard()); return; }
            case BTN_SET -> { pending.remove(chatId); sendSetPicker(chatId, null); return; }
            default -> {
            }
        }

        // Yangi narx kutilayotgan bo'lsa
        if (pending.get(chatId) instanceof AwaitPrice p) {
            String digits = text.replaceAll("[\\s_.,]|so'm|söm|сум|sum", "");
            if (!digits.matches("\\d{3,9}")) {
                reply(chatId, "Narxni faqat raqam bilan yozing, masalan: <code>95000</code>", cancelKeyboard());
                return;
            }
            pending.remove(chatId);
            try {
                ProductVariant v = menu.changePrice(p.variantId(), Long.parseLong(digits));
                reply(chatId, "✅ " + esc(p.title()) + " — yangi narx: <b>" + Money.format(v.getPrice()) + "</b>\n\n"
                        + "Yana narx özgartirasizmi?", afterEditKeyboard("price", v.getProduct().getCategory().getId()));
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage(), adminKeyboard());
            }
            return;
        }
        if (pending.get(chatId) instanceof AwaitDiscount d) {
            String digits = text.replaceAll("[\\s%]", "");
            if (!digits.matches("\\d{1,2}")) {
                reply(chatId, "Çegirma foizini raqam bilan yozing (0–90), masalan: <code>15</code>\n0 — aksiyani olib taşlaş", cancelKeyboard());
                return;
            }
            pending.remove(chatId);
            try {
                int percent = Integer.parseInt(digits);
                String name = menu.setDiscount(d.productId(), percent);
                reply(chatId, percent == 0
                                ? "✅ «" + esc(name) + "» aksiyadan olib taşlandi"
                                : "🏷 «" + esc(name) + "» — <b>-" + percent + "%</b> aksiya qöyildi. Menyuda eski narx çizilib körinadi.",
                        afterEditKeyboard("promo", d.categoryId()));
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage(), adminKeyboard());
            }
            return;
        }
        if (pending.get(chatId) instanceof AwaitContents c) {
            pending.remove(chatId);
            String clean = text.lines().map(String::strip).filter(l -> !l.isEmpty())
                    .map(l -> l.replaceFirst("^(?:[-•*✓✔️]+|\\d+[.)])\\s*", "").strip()).filter(l -> !l.isEmpty())
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (text.strip().equals("-")) {
                clean = "-";
            }
            String name = menu.setContents(c.productId(), clean);
            reply(chatId, clean.equals("-") ? "✅ «" + esc(name) + "» tarkibi öçirildi"
                            : "✅ «" + esc(name) + "» tarkibi saqlandi:\n\n" + esc(clean).lines().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"))
                            + "\n\nMini App'da set oçilganda «Set içida» bölib körinadi.",
                    afterEditKeyboard("set", c.categoryId()));
            return;
        }
        if (onAdminPendingText(chatId, text)) {
            return;
        }
        if (pending.get(chatId) instanceof AwaitPhoto
                || pending.get(chatId) instanceof AwaitBanner) {
            reply(chatId, "Rasm kutilmoqda 📷 Mahsulot rasmini yuboring yoki «Bekor qiliş» ni bosing.", cancelKeyboard());
            return;
        }

        // Eski / buyruqlar ham ishlab turadi
        switch (command) {
            case "/buyurtmalar" -> sendActiveOrders(chatId);
            case "/statistika" -> sendStats(chatId, null);
            case "/stoplist" -> sendStopList(chatId);
            case "/mahsulotlar" -> reply(chatId, productsText(), adminKeyboard());
            case "/yordam", "/help" -> reply(chatId, adminHelp(), adminKeyboard());
            default -> reply(chatId, "Pastdagi tugmalardan birini tanlang 👇", adminKeyboard());
        }
    }

    private ReplyKeyboardMarkup adminKeyboard() {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(BTN_ORDERS, BTN_STATS))
                .keyboardRow(new KeyboardRow(BTN_PRODUCTS, BTN_CUSTOMERS))
                .keyboardRow(new KeyboardRow(BTN_PROMO, BTN_BROADCAST))
                .keyboardRow(new KeyboardRow(BTN_SETTINGS, BTN_HELP))
                .resizeKeyboard(true)
                .isPersistent(true)
                .inputFieldPlaceholder("Admin panel")
                .build();
    }

    private String adminHelp() {
        return "👩‍🍳 <b>Admin panel</b>\n\n"
                + BTN_ORDERS + " — faol buyurtmalar, 🧾 tekşirilmagan çeklar, 🔍 qidiriş (raqami yoki telefon bilan)\n"
                + BTN_STATS + " — bugungi va oylik savdo\n"
                + BTN_PRODUCTS + " — ➕ yangi mahsulot, ✏️ tahrirlaş/öçiriş, 💰 narx, 📷 rasm, ⛔ tugadi, 🎁 set tarkibi\n"
                + BTN_CUSTOMERS + " — mijozlar röyxati, bloklaş, Excel uçun fayl\n"
                + BTN_PROMO + " — aksiya beriş/olib taşlaş va mijozlarga e'lon qiliş\n"
                + BTN_BROADCAST + " — barça mijozlarga istalgan xabar (matn, rasm, video)\n"
                + BTN_SETTINGS + " — buyurtmalarni töxtatiş, ish vaqti, tölov kartasi, bosh rasm, adminlar\n\n"
                + "Yangi buyurtma va çeklar avtomatik keladi, ulardagi tugmalar bilan boşqarasiz.";
    }

    /** messageId berilsa, mavjud statistika xabari joyida yangilanadi («🔄 Yangilash»). */
    /** «📊 Statistika»: qisqa ko'rinish va davr tugmalari. */
    private void sendStats(Long chatId, Integer messageId) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(props.zone());
        StatsRange today = statsRange("D", 0);
        StatsRange month = statsRange("M", 0);
        OrderStats.Report d = orders.report(today.from(), today.to());
        OrderStats.Report m = orders.report(month.from(), month.to());
        String text = "📊 <b>Statistika</b> · " + OrderMessages.date(now.toLocalDate())
                + String.format(", %02d:%02d", now.getHour(), now.getMinute()) + "\n\n"
                + "📅 <b>Bugun</b>\n"
                + "   🧾 Buyurtmalar: <b>" + d.total() + " ta</b>\n"
                + "   💰 Bugungi jami tuşum: <b>" + Money.format(d.paid()) + "</b>\n"
                + pendingLine(d)
                + "\n🗓 <b>" + OrderMessages.month(now.getMonthValue()) + " oyi</b>\n"
                + "   🧾 Buyurtmalar: <b>" + m.total() + " ta</b>\n"
                + "   💰 Oylik jami tuşum: <b>" + Money.format(m.paid()) + "</b>\n"
                + pendingLine(m)
                + "\n⏳ Hozir faol buyurtmalar: <b>" + orders.activeOrders().size() + " ta</b>\n\n"
                + "<i>Tuşum — tölovi tasdiqlangan buyurtmalar summasi.</i>\nBatafsil köriş uçun davrni tanlang 👇";
        show(chatId, messageId, text, kb(
                row(inline("📅 Bugungi statistika", "stx:D:0")),
                row(inline("🗓 Oylik statistika", "stx:M:0")),
                row(inline("📈 Barça vaqt", "stx:A:0")),
                row(inline("⭐ Baholar va izohlar", "rtg:menu")),
                row(inline("🔄 Yangilaş", "stats"), inline("✅ Tayyor", "done"))));
    }

    /** Chek tasdiqlanmagan (lekin bekor qilinmagan) buyurtmalar summasi — bo'lsa. */
    private static String pendingLine(OrderStats.Report r) {
        long pending = r.revenue() - r.paid();
        return pending > 0 ? "   ⏳ Tasdiqlanişi kutilmoqda: " + Money.format(pending) + "\n" : "";
    }

    /** Statistika davri: D — bugun, M — oy (off = necha oy oldin), A — barcha vaqt. */
    private record StatsRange(String kind, int off, java.time.Instant from, java.time.Instant to, String title, String fileName) {
    }

    private StatsRange statsRange(String kind, int off) {
        java.time.ZoneId z = props.zone();
        java.time.LocalDate today = java.time.LocalDate.now(z);
        return switch (kind) {
            case "D" -> new StatsRange(kind, 0, today.atStartOfDay(z).toInstant(), today.plusDays(1).atStartOfDay(z).toInstant(),
                    "📅 <b>Bugungi statistika</b> · " + OrderMessages.date(today), "statistika-" + today);
            case "M" -> {
                java.time.LocalDate first = today.withDayOfMonth(1).minusMonths(off);
                yield new StatsRange(kind, off, first.atStartOfDay(z).toInstant(), first.plusMonths(1).atStartOfDay(z).toInstant(),
                        "🗓 <b>" + OrderMessages.month(first.getMonthValue()) + " " + first.getYear() + "</b> · oylik statistika",
                        "statistika-" + first.getYear() + "-" + String.format("%02d", first.getMonthValue()));
            }
            default -> new StatsRange("A", 0, java.time.Instant.EPOCH, today.plusDays(1).atStartOfDay(z).toInstant(),
                    "📈 <b>Barça vaqt statistikasi</b>", "statistika-hammasi-" + today);
        };
    }

    private void sendStatsPeriod(Long chatId, Integer messageId, String kind, int off) {
        StatsRange r = statsRange(kind, off);
        OrderStats.Report s = orders.report(r.from(), r.to());
        List<OrderDetails> list = orders.ordersBetween(r.from(), r.to());
        long delivered = list.stream().filter(o -> o.status() == uz.habibi.bakery.domain.OrderStatus.DELIVERED).count();
        long inProgress = s.done() - delivered;
        StringBuilder sb = new StringBuilder(r.title()).append("\n\n");
        sb.append("🧾 Jami buyurtmalar: <b>").append(s.total()).append(" ta</b>\n")
                .append("   🎉 Topşirilgan: ").append(delivered).append(" ta\n")
                .append("   🔄 Hali jarayonda: ").append(inProgress).append(" ta\n")
                .append("   ❌ Bekor qilingan: ").append(s.cancelled()).append(" ta");
        String totalLabel = switch (kind) {
            case "D" -> "Bugungi jami tuşum";
            case "M" -> "Oylik jami tuşum";
            default -> "Jami tuşum";
        };
        sb.append("\n\n💰 ").append(totalLabel).append(": <b>").append(Money.format(s.paid())).append("</b>")
                .append(" (").append(s.paidCount()).append(" ta buyurtma)\n");
        long pending = s.revenue() - s.paid();
        if (pending > 0) {
            sb.append("⏳ Tasdiqlanişi kutilmoqda: ").append(Money.format(pending)).append('\n');
        }
        double[] rating = orders.ratingBetween(r.from(), r.to());
        if (rating[1] > 0) {
            sb.append("⭐ Mijozlar bahosi: <b>").append(rating[0]).append("</b> / 5 (").append((long) rating[1]).append(" ta baho)\n");
        }
        // eng qimmat va eng arzon buyurtma (bekor qilinmaganlar orasidan)
        List<OrderDetails> valid = list.stream().filter(o -> o.status() != uz.habibi.bakery.domain.OrderStatus.CANCELLED).toList();
        OrderDetails maxO = valid.stream().max(java.util.Comparator.comparingLong(OrderDetails::itemsTotal)).orElse(null);
        OrderDetails minO = valid.stream().min(java.util.Comparator.comparingLong(OrderDetails::itemsTotal)).orElse(null);
        if (maxO != null) {
            sb.append("\n🔝 Eng qimmat buyurtma: <b>").append(Money.format(maxO.itemsTotal())).append("</b> · #").append(maxO.id())
                    .append(" (").append(esc(maxO.customerName())).append(")\n");
            if (minO != null && !minO.id().equals(maxO.id())) {
                sb.append("🔻 Eng arzon buyurtma: <b>").append(Money.format(minO.itemsTotal())).append("</b> · #").append(minO.id())
                        .append(" (").append(esc(minO.customerName())).append(")\n");
            }
        }
        if (!s.top().isEmpty()) {
            sb.append("\n🔥 <b>Eng köp sotilgan</b>\n");
            int i = 1;
            for (OrderStats.TopProduct t : s.top()) {
                sb.append(i++).append(". ").append(esc(t.name())).append(" — ").append(t.quantity()).append(" ta\n");
            }
        }
        if (kind.equals("D")) {
            sb.append("\n⏳ Hozir faol buyurtmalar: <b>").append(orders.activeOrders().size()).append(" ta</b>");
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (kind.equals("M")) {
            java.time.LocalDate first = java.time.LocalDate.now(props.zone()).withDayOfMonth(1).minusMonths(off);
            List<InlineKeyboardButton> nav = new ArrayList<>();
            nav.add(inline("◀️ " + OrderMessages.month(first.minusMonths(1).getMonthValue()), "stx:M:" + (off + 1)));
            if (off > 0) {
                nav.add(inline(OrderMessages.month(first.plusMonths(1).getMonthValue()) + " ▶️", "stx:M:" + (off - 1)));
            }
            rows.add(new InlineKeyboardRow(nav));
        }
        if (maxO != null) {
            List<InlineKeyboardButton> open = new ArrayList<>();
            open.add(inline("🔝 #" + maxO.id(), "show:" + maxO.id()));
            if (minO != null && !minO.id().equals(maxO.id())) {
                open.add(inline("🔻 #" + minO.id(), "show:" + minO.id()));
            }
            rows.add(new InlineKeyboardRow(open));
        }
        rows.add(row(inline("📥 Excel fayl (" + s.total() + " ta buyurtma)", "stxf:" + kind + ":" + off)));
        rows.add(row(inline("‹ Statistika", "stats"), inline("🔄 Yangilaş", "stx:" + kind + ":" + off)));
        show(chatId, messageId, sb.toString().trim(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** «⭐ Baholar va izohlar»: o'rtacha baho, taqsimot va oxirgi past baholar izohlari bilan. */
    private void sendRatingsView(Long chatId, Integer messageId) {
        List<OrderDetails> rated = orders.ratedOrders();
        StatsRange month = statsRange("M", 0);
        double[] m = orders.ratingBetween(month.from(), month.to());
        double[] all = orders.ratingBetween(java.time.Instant.EPOCH, java.time.Instant.now().plus(java.time.Duration.ofDays(1)));
        StringBuilder sb = new StringBuilder("⭐ <b>Baholar va izohlar</b>\n\n");
        if (rated.isEmpty()) {
            sb.append("<i>Hali baho yöq.</i>");
        } else {
            sb.append("📈 Umumiy: <b>").append(all[0]).append("</b> / 5 (").append((long) all[1]).append(" ta baho)\n");
            if (m[1] > 0) {
                sb.append("🗓 Şu oy: <b>").append(m[0]).append("</b> / 5 (").append((long) m[1]).append(" ta)\n");
            }
            sb.append('\n');
            for (int st = 5; st >= 1; st--) {
                final int star = st;
                long c = rated.stream().filter(o -> o.rating() == star).count();
                sb.append(OrderMessages.stars(st)).append(" — ").append(c).append(" ta\n");
            }
            List<OrderDetails> low = rated.stream().filter(o -> o.rating() <= 3).limit(8).toList();
            if (!low.isEmpty()) {
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM");
                sb.append("\n⚠️ <b>Oxirgi past baholar</b>\n");
                for (OrderDetails o : low) {
                    sb.append("#").append(o.id()).append(" · ").append(f.format(o.createdAt().atZone(props.zone())))
                            .append(" · ").append(o.rating()).append("⭐ · ").append(esc(o.customerName())).append('\n');
                    if (o.ratingComment() != null && !o.ratingComment().isBlank()) {
                        sb.append("   💬 <i>").append(esc(o.ratingComment())).append("</i>\n");
                    }
                }
            }
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        List<InlineKeyboardButton> nums = rated.stream().filter(o -> o.rating() <= 3).limit(8)
                .map(o -> inline("#" + o.id(), "show:" + o.id())).toList();
        if (!nums.isEmpty()) {
            for (int i = 0; i < nums.size(); i += 4) {
                rows.add(new InlineKeyboardRow(nums.subList(i, Math.min(i + 4, nums.size()))));
            }
        }
        if (!rated.isEmpty()) {
            rows.add(row(inline("📥 Excel — barça baholar (" + rated.size() + ")", "rtg:excel")));
        }
        rows.add(row(inline("‹ Statistika", "stats"), inline("🔄 Yangilaş", "rtg:menu")));
        show(chatId, messageId, sb.toString().trim(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void sendCsvFile(Long chatId, StringBuilder csv, String fileName, String caption) {
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.send.SendDocument.builder()
                    .chatId(chatId)
                    .document(new org.telegram.telegrambots.meta.api.objects.InputFile(
                            new java.io.ByteArrayInputStream(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), fileName))
                    .caption(caption)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Faylni yuborib bölmadi ({}): {}", fileName, e.getMessage());
            reply(chatId, "⚠️ Faylni yuborib bölmadi", null);
        }
    }

    /** Hamma baholar: buyurtma, sana, mijoz, baho, izoh, mahsulotlar. */
    private void sendRatingsCsv(Long chatId) {
        List<OrderDetails> rated = orders.ratedOrders();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Buyurtma;Sana;Mijoz;Telefon;Username;Baho;Izoh;Mahsulotlar;Summa\n");
        for (OrderDetails o : rated) {
            String items = o.items().stream().map(l -> l.productName() + (l.variantName() == null ? "" : " (" + l.variantName() + ")")
                    + " × " + l.quantity()).collect(java.util.stream.Collectors.joining(", "));
            csv.append('#').append(o.id()).append(';')
                    .append(f.format(o.createdAt().atZone(props.zone()))).append(';')
                    .append(csvCell(o.customerName())).append(';')
                    .append(csvCell(o.phone())).append(';')
                    .append(csvCell(o.customerUsername() == null ? null : "@" + o.customerUsername())).append(';')
                    .append(o.rating()).append(';')
                    .append(csvCell(o.ratingComment())).append(';')
                    .append(csvCell(items)).append(';')
                    .append(o.itemsTotal()).append('\n');
        }
        double avg = rated.stream().mapToInt(OrderDetails::rating).average().orElse(0);
        csv.append("\nÖrtaça baho;;;;;").append(String.format("%.1f", avg).replace('.', ',')).append(";;;\n");
        sendCsvFile(chatId, csv, "baholar-" + java.time.LocalDate.now(props.zone()) + ".csv",
                "⭐ Barça baholar — " + rated.size() + " ta, örtaça " + String.format("%.1f", avg) + " / 5.\nExcel yoki Google Sheets'da oçiladi.");
    }

    /** Bugungi cheklar — bank ko'chirmasi bilan solishtirish uchun. */
    private void sendReceiptsCsv(Long chatId) {
        List<OrderDetails> today = orders.receiptsToday(props.zone());
        java.time.format.DateTimeFormatter t = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Buyurtma;Buyurtma vaqti;Mijoz;Telefon;Username;Kutilgan summa;Çek holati;Buyurtma holati\n");
        long paid = 0;
        long pending = 0;
        long rejected = 0;
        for (OrderDetails o : today) {
            switch (o.paymentStatus()) {
                case PAID -> paid += o.itemsTotal();
                case RECEIPT_SENT -> pending += o.itemsTotal();
                case AWAITING_RECEIPT -> rejected++;
                default -> { }
            }
            csv.append('#').append(o.id()).append(';')
                    .append(t.format(o.createdAt().atZone(props.zone()))).append(';')
                    .append(csvCell(o.customerName())).append(';')
                    .append(csvCell(o.phone())).append(';')
                    .append(csvCell(o.customerUsername() == null ? null : "@" + o.customerUsername())).append(';')
                    .append(o.itemsTotal()).append(';')
                    .append(csvCell(OrderMessages.receiptState(o))).append(';')
                    .append(csvCell(o.status().label())).append('\n');
        }
        csv.append('\n')
                .append("Tasdiqlangan jami;;;;;").append(paid).append(";;\n")
                .append("Tekşirilmagan jami;;;;;").append(pending).append(";;\n")
                .append("Rad etilgan çeklar;;;;;").append(rejected).append(" ta;;\n");
        java.time.LocalDate day = java.time.LocalDate.now(props.zone());
        sendCsvFile(chatId, csv, "ceklar-" + day + ".csv",
                "🧾 Bugungi çeklar — " + today.size() + " ta · ✅ tasdiqlangan: " + Money.format(paid)
                        + "\nBank köçirmasi bilan solişting. Excel yoki Google Sheets'da oçiladi.");
    }

    /** Davr buyurtmalari CSV fayl bo'lib (Excel'da ochiladi). */
    private void sendStatsCsv(Long chatId, String kind, int off) {
        StatsRange r = statsRange(kind, off);
        List<OrderDetails> list = orders.ordersBetween(r.from(), r.to());
        OrderStats.Report s = orders.report(r.from(), r.to());
        java.time.format.DateTimeFormatter d = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        java.time.format.DateTimeFormatter t = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Buyurtma;Sana;Vaqt;Mijoz;Telefon;Username;Mahsulotlar;Soni (dona);Summa;Tölov usuli;Tölov holati;Holat;Manzil;Tortga yozuv\n");
        for (OrderDetails o : list) {
            var at = o.createdAt().atZone(props.zone());
            String items = o.items().stream().map(l -> l.productName() + (l.variantName() == null ? "" : " (" + l.variantName() + ")")
                    + " × " + l.quantity()).collect(java.util.stream.Collectors.joining(", "));
            int qty = o.items().stream().mapToInt(OrderDetails.Line::quantity).sum();
            csv.append('#').append(o.id()).append(';')
                    .append(d.format(at)).append(';')
                    .append(t.format(at)).append(';')
                    .append(csvCell(o.customerName())).append(';')
                    .append(csvCell(o.phone())).append(';')
                    .append(csvCell(o.customerUsername() == null ? null : "@" + o.customerUsername())).append(';')
                    .append(csvCell(items)).append(';')
                    .append(qty).append(';')
                    .append(o.itemsTotal()).append(';')
                    .append(csvCell(o.paymentMethod().label())).append(';')
                    .append(csvCell(OrderMessages.receiptState(o))).append(';')
                    .append(csvCell(o.status().label())).append(';')
                    .append(csvCell(o.address())).append(';')
                    .append(csvCell(o.inscription())).append('\n');
        }
        csv.append('\n')
                .append("JAMI (bekor qilinmagan);;;;;;;;").append(s.revenue()).append(";;;").append(s.total()).append(" ta buyurtma;;\n")
                .append("Tölovi tasdiqlangan;;;;;;;;").append(s.paid()).append(";;;").append(s.paidCount()).append(" ta;;\n")
                .append("Bekor qilingan;;;;;;;;;;;").append(s.cancelled()).append(" ta;;\n");
        String caption = r.title().replaceAll("<[^>]+>", "") + "\n🧾 " + s.total() + " ta buyurtma · 💰 " + Money.format(s.revenue())
                + "\nExcel yoki Google Sheets'da oçiladi.";
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.send.SendDocument.builder()
                    .chatId(chatId)
                    .document(new org.telegram.telegrambots.meta.api.objects.InputFile(
                            new java.io.ByteArrayInputStream(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), r.fileName() + ".csv"))
                    .caption(caption)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Statistika faylini yuborib bölmadi: {}", e.getMessage());
            reply(chatId, "⚠️ Faylni yuborib bölmadi", null);
        }
    }

    /** «📋 Buyurtmalar»: bo'limlar soni bilan. */
    private void sendActiveOrders(Long chatId) {
        sendOrdersMenu(chatId, null);
    }

    private void sendOrdersMenu(Long chatId, Integer messageId) {
        int active = orders.activeOrders().size();
        int cancelled = orders.cancelledToday(props.zone()).size();
        int unconfirmed = orders.unconfirmed().size();
        show(chatId, messageId, "📋 <b>Buyurtmalar</b>\n\n"
                        + "🟢 Faol: <b>" + active + "</b>\n"
                        + "⏳ Tasdiqlanmagan: <b>" + unconfirmed + "</b>\n"
                        + "❌ Bugun bekor qilingan: <b>" + cancelled + "</b>",
                kb(row(inline("🟢 Faol buyurtmalar (" + active + ")", "ord:active")),
                        row(inline("⏳ Tasdiqlanmagan buyurtmalar (" + unconfirmed + ")", "ord:unconf")),
                        row(inline("❌ Bugun bekor qilinganlar (" + cancelled + ")", "ord:cancel")),
                        row(inline("🧾 Çeklar", "rc:menu")),
                        row(inline("🔍 Qidiriş", "ord:search"), inline("✅ Tayyor", "done"))));
    }

    /** «⛔ Mahsulot tugadi»: avval kategoriya, keyin shu kategoriya mahsulotlari. */
    private void sendStopList(Long chatId) {
        sendCategoryPicker(chatId, null, "stop");
    }

    private void sendStopCategory(Long chatId, Integer messageId, Long categoryId) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Product p : menu.allProducts()) {
            if (p.getCategory().getId().equals(categoryId)) {
                rows.add(new InlineKeyboardRow(inline((p.isAvailable() ? "✅ " : "⛔ ") + p.getName(), "ps:" + categoryId + ":" + p.getId())));
            }
        }
        rows.add(row(inline("‹ Kategoriyalar", "back:stop"), inline("✅ Tayyor", "done")));
        show(chatId, messageId, "⛔ <b>Mahsulot tugadi</b> · " + esc(categoryName(categoryId))
                        + "\nQolmagan mahsulotni bosing — menyuda «Tugagan» bölib körinadi. Qayta bosilsa, yana sotuvga çiqadi.\n\n"
                        + "✅ — bor · ⛔ — qolmagan",
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** «🚫 Tugagan mahsulotlar»: hozir yo'q mahsulotlar va «sotuvga qaytarish» tugmalari. */
    private void sendSoldOut(Long chatId, Integer messageId) {
        List<Product> soldOut = menu.allProducts().stream().filter(p -> !p.isAvailable()).toList();
        if (soldOut.isEmpty()) {
            show(chatId, messageId, "✅ Hozir hamma mahsulot bor, tugaganlari yöq.",
                    kb(row(inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✅ Tayyor", "done"))));
            return;
        }
        StringBuilder sb = new StringBuilder("🚫 <b>Tugagan mahsulotlar</b> — ").append(soldOut.size()).append(" ta\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        String category = null;
        for (Product p : soldOut) {
            if (!p.getCategory().getName().equals(category)) {
                category = p.getCategory().getName();
                sb.append("<b>").append(esc(category)).append("</b>\n");
            }
            sb.append("   ⛔ ").append(esc(p.getName())).append('\n');
            rows.add(new InlineKeyboardRow(inline("✅ " + p.getName() + " — sotuvga qaytariş", "pr:" + p.getId())));
        }
        rows.add(row(inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✅ Tayyor", "done")));
        sb.append("\nMahsulot yana tayyor bölsa, tugmasini bosing — menyuda darhol çiqadi.");
        show(chatId, messageId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** «📦 Mahsulotlar» → kategoriya: mahsulotlar, o'lcham va narxlar, holati. */
    private void sendCategoryProducts(Long chatId, Integer messageId, Long categoryId) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Product p : menu.allProducts()) {
            if (!p.getCategory().getId().equals(categoryId)) {
                continue;
            }
            if (count == 0) {
                sb.append(p.getCategory().getEmoji() == null ? "" : p.getCategory().getEmoji() + " ")
                        .append("<b>").append(esc(p.getCategory().getName())).append("</b>\n\n");
            }
            count++;
            sb.append(p.isAvailable() ? "✅ " : "⛔ ").append("<b>").append(esc(p.getName())).append("</b>")
                    .append(p.getPhotoFileId() == null ? "" : " 📷")
                    .append(p.getDiscountPercent() > 0 ? " 🏷 -" + p.getDiscountPercent() + "%" : "").append('\n');
            for (ProductVariant v : p.getVariants()) {
                sb.append("     ").append(v.getName() == null ? "" : esc(v.getName()) + " — ")
                        .append(Money.format(v.getPrice())).append('\n');
            }
        }
        sb.append("\n✅ — bor · ⛔ — qolmagan · 📷 — rasmi bor");
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(inline("‹ Kategoriyalar", "back:view"), inline("✅ Tayyor", "done")))
                .build();
        show(chatId, messageId, sb.toString(), kb);
    }

    private InlineKeyboardMarkup stopListKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Product p : menu.allProducts()) {
            rows.add(new InlineKeyboardRow(inline((p.isAvailable() ? "✅ " : "⛔ ") + p.getName(), "p:" + p.getId())));
        }
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private String productsText() {
        StringBuilder sb = new StringBuilder("📦 <b>Mahsulotlar</b>\n");
        String category = null;
        for (Product p : menu.allProducts()) {
            if (!p.getCategory().getName().equals(category)) {
                category = p.getCategory().getName();
                sb.append("\n<b>").append(esc(category)).append("</b>\n");
            }
            sb.append(p.isAvailable() ? "• " : "⛔ ").append(esc(p.getName()))
                    .append(p.getPhotoFileId() == null ? "" : " 📷").append('\n');
            for (ProductVariant v : p.getVariants()) {
                sb.append("     ")
                        .append(v.getName() == null ? "" : esc(v.getName()) + " — ")
                        .append(Money.format(v.getPrice())).append('\n');
            }
        }
        return sb.append("\n📷 — rasmi bor · ⛔ — tugagan").toString();
    }

    // ---- Mijozlar ----

    private static final int CUSTOMERS_PER_PAGE = 10;

    private void sendCustomers(Long chatId, Integer messageId, int page) {
        List<CustomerSummary> all = customers.summaries();
        if (all.isEmpty()) {
            show(chatId, messageId, "👥 Hozirça mijozlar yöq", null);
            return;
        }
        // Faqat oxirgi 10 ta mijoz; qolganlari — qidiruv yoki CSV fayl orqali
        page = 0;
        long verified = all.stream().filter(CustomerSummary::phoneVerified).count();
        long buyers = all.stream().filter(c -> c.orders() > 0).count();

        StringBuilder sb = new StringBuilder("👥 <b>Mijozlar</b> — jami ").append(all.size()).append(" ta\n")
                .append("🛍 Buyurtma bergan: ").append(buyers).append(" · 📱 Raqami tasdiqlangan: ").append(verified)
                .append("\n\n");
        int from = page * CUSTOMERS_PER_PAGE;
        List<CustomerSummary> slice = all.subList(from, Math.min(from + CUSTOMERS_PER_PAGE, all.size()));
        for (int i = 0; i < slice.size(); i++) {
            CustomerSummary c = slice.get(i);
            sb.append(from + i + 1).append(". ").append(c.blocked() ? "🚫 " : "")
                    .append("<a href=\"tg://user?id=").append(c.telegramId()).append("\">")
                    .append(esc(c.displayName())).append("</a>");
            if (c.username() != null) {
                sb.append(" · @").append(esc(c.username()));
            }
            sb.append('\n');
            sb.append("   📱 ").append(c.phone() == null ? "—" : esc(c.phone()))
                    .append(c.phone() == null ? "" : c.phoneVerified() ? " ✅" : " <i>(tasdiqlanmagan)</i>").append('\n');
            sb.append("   🛍 ").append(c.orders()).append(" ta");
            if (c.orders() > 0) {
                sb.append(" · ").append(Money.format(c.spent()));
                sb.append("\n   💳 Tölangan: ").append(Money.format(c.paid()));
                if (c.unpaid() > 0) {
                    sb.append(" · tölanmagan: <b>").append(Money.format(c.unpaid())).append("</b>");
                }
            }
            if (c.lastOrderAt() != null) {
                sb.append(" · oxirgi: ").append(OrderMessages.date(c.lastOrderAt().atZone(props.zone()).toLocalDate()));
            }
            sb.append('\n');
            sb.append("   🌐 ").append(c.language() == null ? "til tanlanmagan" : c.language().title()).append("\n\n");
        }
        int rest = all.size() - slice.size();
        if (rest > 0) {
            sb.append("➕ Yana <b>").append(rest).append(" ta</b> mijoz bor — ularni «🔍 Mijozni qidiriş» orqali toping.\n\n");
        }
        sb.append("<i>Raqamni bossangiz — mijozning buyurtmalari va boşqaruv tugmalari çiqadi</i>");
        List<InlineKeyboardButton> numbers = new ArrayList<>();
        for (int i = 0; i < slice.size(); i++) {
            numbers.add(inline(String.valueOf(from + i + 1), "custd:" + slice.get(i).telegramId() + ":" + page));
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < numbers.size(); i += 5) {
            rows.add(new InlineKeyboardRow(numbers.subList(i, Math.min(i + 5, numbers.size()))));
        }
        rows.add(new InlineKeyboardRow(inline("🔍 Mijozni qidiriş", "ord:csearch")));
        rows.add(new InlineKeyboardRow(inline("📥 Excel uçun fayl (CSV)", "custcsv")));
        show(chatId, messageId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** Mijoz kartochkasi: profil, har bir buyurtma (sana, vaqt, summa, to'lov holati) va bloklash tugmasi. */
    private static final int CUSTOMER_ORDERS_PAGE = 5;

    /** Mijoz profili: faqat ma'lumotlari va «Buyurtmalari» tugmasi. */
    private void sendCustomerDetail(Long chatId, Integer messageId, long telegramId, int page) {
        CustomerSummary c = customers.summary(telegramId).orElse(null);
        if (c == null) {
            show(chatId, messageId, "Mijoz topilmadi", null);
            return;
        }
        java.time.format.DateTimeFormatter dt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
        List<OrderDetails> list = orders.myOrders(telegramId);
        StringBuilder sb = new StringBuilder();
        sb.append(c.blocked() ? "🚫 <b>BLOKLANGAN</b>\n" : "")
                .append("👤 <a href=\"tg://user?id=").append(c.telegramId()).append("\"><b>").append(esc(c.displayName())).append("</b></a>\n")
                .append("✈️ Username: ").append(c.username() == null ? "<i>yöq</i>" : "@" + esc(c.username())).append('\n')
                .append("📱 Botdagi raqami: ").append(c.phone() == null ? "—" : esc(c.phone()))
                .append(c.phone() != null && c.phoneVerified() ? " ✅" : "").append('\n');
        // buyurtmalarda yozilgan oluvchi ismi va raqami (botdagidan farq qilishi mumkin)
        java.util.Set<String> recipients = new java.util.LinkedHashSet<>();
        for (OrderDetails o : list) {
            recipients.add(esc(o.customerName()) + ", " + esc(o.phone()));
        }
        if (!recipients.isEmpty()) {
            sb.append("📦 Buyurtma uçun bergan: ").append(String.join("; ", recipients.stream().limit(3).toList())).append('\n');
        }
        if (c.registeredAt() != null) {
            sb.append("🗓 Botga kirgan: ").append(dt.format(c.registeredAt().atZone(props.zone()))).append('\n');
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(inline("🛍 Buyurtmalari (" + list.size() + ")", "cuo:" + telegramId + ":0")));
        if (!isAdmin(telegramId)) {
            rows.add(new InlineKeyboardRow(c.blocked()
                    ? inline("🔓 Blokdan çiqariş", "custunb:" + telegramId + ":" + page)
                    : inline("🚫 Bloklaş", "custblk:" + telegramId + ":" + page)));
            rows.add(new InlineKeyboardRow(inline("🗑 Öçiriş", "custdel:" + telegramId + ":" + page)));
        }
        rows.add(new InlineKeyboardRow(inline("‹ Mijozlar röyxati", "cust:" + page)));
        show(chatId, messageId, sb.toString().trim(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** Mijoz buyurtmalari: raqami, sanasi, mahsulotlari narxi bilan, jami, to'lov usuli va holati (5 tadan). */
    private void sendCustomerOrders(Long chatId, Integer messageId, long telegramId, int page) {
        List<OrderDetails> list = orders.myOrders(telegramId);
        String name = customers.summary(telegramId).map(CustomerSummary::displayName).orElse(String.valueOf(telegramId));
        if (list.isEmpty()) {
            show(chatId, messageId, "🛍 <b>" + esc(name) + "</b> hali buyurtma bermagan.",
                    kb(row(inline("‹ Mijoz", "custd:" + telegramId + ":0"))));
            return;
        }
        int pages = (list.size() + CUSTOMER_ORDERS_PAGE - 1) / CUSTOMER_ORDERS_PAGE;
        page = Math.max(0, Math.min(page, pages - 1));
        List<OrderDetails> slice = list.subList(page * CUSTOMER_ORDERS_PAGE, Math.min((page + 1) * CUSTOMER_ORDERS_PAGE, list.size()));
        java.time.format.DateTimeFormatter dt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
        StringBuilder sb = new StringBuilder("🛍 <b>").append(esc(name)).append("</b> — buyurtmalari: ").append(list.size()).append(" ta\n\n");
        List<InlineKeyboardButton> nums = new ArrayList<>();
        for (OrderDetails o : slice) {
            sb.append("🧾 <b>#").append(o.id()).append("</b> · ").append(dt.format(o.createdAt().atZone(props.zone()))).append('\n');
            for (OrderDetails.Line l : o.items()) {
                sb.append("   • ").append(esc(l.productName()))
                        .append(l.variantName() == null ? "" : " (" + esc(l.variantName()) + ")")
                        .append(" × ").append(l.quantity()).append(" — ").append(Money.format(l.total())).append('\n');
            }
            sb.append("   💰 Jami: <b>").append(Money.format(o.itemsTotal())).append("</b>\n")
                    .append("   ").append(o.paymentMethod().label()).append('\n')
                    .append("   ").append(OrderMessages.receiptState(o)).append('\n')
                    .append("   📌 ").append(o.status().label()).append("\n\n");
            nums.add(inline("#" + o.id(), "show:" + o.id()));
        }
        sb.append("👇 Raqamni bosing — buyurtma batafsil, lokatsiya va çek bilan çiqadi");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(nums));
        if (pages > 1) {
            List<InlineKeyboardButton> nav = new ArrayList<>();
            if (page > 0) {
                nav.add(inline("‹ Yangiroq", "cuo:" + telegramId + ":" + (page - 1)));
            }
            nav.add(inline((page + 1) + " / " + pages, "cuo:" + telegramId + ":" + page));
            if (page < pages - 1) {
                nav.add(inline("Eskiroq ›", "cuo:" + telegramId + ":" + (page + 1)));
            }
            rows.add(new InlineKeyboardRow(nav));
        }
        rows.add(new InlineKeyboardRow(inline("‹ Mijoz", "custd:" + telegramId + ":0")));
        show(chatId, messageId, sb.toString().trim(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** Butun ro'yxat CSV fayl bo'lib (Excel'da ochiladi: UTF-8 BOM, ";" ajratgich). */
    private void sendCustomersCsv(Long chatId) {
        List<CustomerSummary> all = customers.summaries();
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("Telegram ID;Ism;Telegram ismi;Username;Telefon;Raqam tasdiqlangan;Til;Buyurtmalar;Jami summa;Tölangan;Tölanmagan;Bloklangan;Oxirgi buyurtma;Röyxatdan ötgan\n");
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (CustomerSummary c : all) {
            csv.append(c.telegramId()).append(';')
                    .append(csvCell(c.name())).append(';')
                    .append(csvCell(c.telegramName())).append(';')
                    .append(csvCell(c.username() == null ? null : "@" + c.username())).append(';')
                    .append(csvCell(c.phone())).append(';')
                    .append(c.phoneVerified() ? "ha" : "yöq").append(';')
                    .append(csvCell(c.language() == null ? null : c.language().code())).append(';')
                    .append(c.orders()).append(';')
                    .append(c.spent()).append(';')
                    .append(c.paid()).append(';')
                    .append(c.unpaid()).append(';')
                    .append(c.blocked() ? "ha" : "yöq").append(';')
                    .append(c.lastOrderAt() == null ? "" : f.format(c.lastOrderAt().atZone(props.zone()))).append(';')
                    .append(c.registeredAt() == null ? "" : f.format(c.registeredAt().atZone(props.zone()))).append('\n');
        }
        String fileName = "mijozlar-" + java.time.LocalDate.now(props.zone()) + ".csv";
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.send.SendDocument.builder()
                    .chatId(chatId)
                    .document(new org.telegram.telegrambots.meta.api.objects.InputFile(
                            new java.io.ByteArrayInputStream(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), fileName))
                    .caption("👥 Mijozlar röyxati — " + all.size() + " ta. Excel yoki Google Sheets'da oçiladi.")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Mijozlar faylini yuborib bölmadi: {}", e.getMessage());
            reply(chatId, "⚠️ Faylni yuborib bölmadi", null);
        }
    }

    /** CSV katakchasi: ";" , qo'shtirnoq va qator ko'chishi bo'lsa qo'shtirnoqqa olinadi. */
    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        // Excel formulaga aylantirmasin (=, +, -, @ bilan boshlansa)
        String safe = v.matches("^[=+\\-@].*") && !v.startsWith("@") && !v.startsWith("+998") ? "'" + v : v;
        return safe.contains(";") || safe.contains("\"") || safe.contains("\n")
                ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    /** Barcha mahsulotlar CSV fayl bo'lib: har bir o'lcham — alohida qator, narx, chegirma, like, baho, sotilgani. */
    private void sendProductsCsv(Long chatId) {
        List<Product> all = menu.allProducts();
        MenuService.ProductNumbers n = menu.productNumbers();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("ID;Kategoriya;Nomi;Tavsif;Ölçam;Narx;Çegirma %;Çegirmali narx;Holat;Rasm;Like;Örtaça baho;Baholar soni;Sotilgan (dona);Set tarkibi;Qöşilgan\n");
        long variants = 0;
        for (Product p : all) {
            var r = n.ratings().get(p.getId());
            for (ProductVariant v : p.getVariants()) {
                variants++;
                csv.append(p.getId()).append(';')
                        .append(csvCell(p.getCategory().getName())).append(';')
                        .append(csvCell(p.getName())).append(';')
                        .append(csvCell(p.getDescription())).append(';')
                        .append(csvCell(v.getName() == null ? "—" : v.getName())).append(';')
                        .append(v.getPrice()).append(';')
                        .append(p.getDiscountPercent() > 0 ? p.getDiscountPercent() : 0).append(';')
                        .append(p.getDiscountPercent() > 0 ? String.valueOf(p.priceOf(v)) : "").append(';')
                        .append(p.isAvailable() ? "Sotuvda" : "Tugagan").append(';')
                        .append(p.getPhotoFileId() == null ? "yöq" : "bor").append(';')
                        .append(n.likes().getOrDefault(p.getId(), 0L)).append(';')
                        .append(r == null ? "" : String.valueOf(r.avg()).replace('.', ',')).append(';')
                        .append(r == null ? 0 : r.count()).append(';')
                        .append(n.sold().getOrDefault(p.getId(), 0L)).append(';')
                        .append(csvCell(p.getContents() == null ? null : p.getContents().replace("\n", ", "))).append(';')
                        .append(p.getCreatedAt() == null ? "" : f.format(p.getCreatedAt().atZone(props.zone()))).append('\n');
            }
        }
        long cats = all.stream().map(p -> p.getCategory().getId()).distinct().count();
        String fileName = "mahsulotlar-" + java.time.LocalDate.now(props.zone()) + ".csv";
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.send.SendDocument.builder()
                    .chatId(chatId)
                    .document(new org.telegram.telegrambots.meta.api.objects.InputFile(
                            new java.io.ByteArrayInputStream(csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), fileName))
                    .caption("📦 Barça mahsulotlar — " + all.size() + " ta mahsulot (" + variants + " ta narx qatori), " + cats
                            + " ta kategoriya.\nExcel yoki Google Sheets'da oçiladi.")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Mahsulotlar faylini yuborib bölmadi: {}", e.getMessage());
            reply(chatId, "⚠️ Faylni yuborib bölmadi", null);
        }
    }

    // ---- Aksiya ----

    private List<Product> promoProducts() {
        return menu.allProducts().stream().filter(p -> p.getDiscountPercent() > 0).toList();
    }

    private void sendPromoMenu(Long chatId, Integer messageId) {
        int count = promoProducts().size();
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(inline("➕ Aksiya e'lon qiliş", "promo:add")))
                .keyboardRow(new InlineKeyboardRow(inline("➖ Aksiyani olib taşlaş", "promo:rm")))
                .keyboardRow(new InlineKeyboardRow(inline("📋 Aksiyadagi mahsulotlar (" + count + ")", "promo:list")))
                .keyboardRow(new InlineKeyboardRow(inline("📢 Mijozlarga e'lon qiliş", "promo:ann")))
                .keyboardRow(new InlineKeyboardRow(inline("✅ Tayyor", "done")))
                .build();
        show(chatId, messageId, "🏷 <b>Aksiya</b>\n\nHozir aksiyada: <b>" + count + " ta</b> mahsulot.\n"
                + "Aksiyadagi mahsulotlar Mini App'da «🏷 Aksiyalar» böliminda, eski narxi çizilgan holda körinadi.", kb);
    }

    private void sendPromoList(Long chatId, Integer messageId) {
        List<Product> list = promoProducts();
        StringBuilder sb = new StringBuilder("📋 <b>Aksiyadagi mahsulotlar</b> — ").append(list.size()).append(" ta\n\n");
        if (list.isEmpty()) {
            sb.append("<i>Hozirça aksiya yöq</i>");
        }
        for (Product p : list) {
            sb.append("🏷 <b>").append(esc(p.getName())).append("</b> — <b>-").append(p.getDiscountPercent()).append("%</b>")
                    .append(p.isAvailable() ? "" : " ⛔").append('\n');
            for (ProductVariant v : p.getVariants()) {
                sb.append("     ").append(v.getName() == null ? "" : esc(v.getName()) + ": ")
                        .append("<s>").append(Money.format(v.getPrice())).append("</s> → <b>")
                        .append(Money.format(p.priceOf(v))).append("</b>\n");
            }
            sb.append('\n');
        }
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(inline("➕ Aksiya e'lon qiliş", "promo:add"), inline("➖ Olib taşlaş", "promo:rm")))
                .keyboardRow(new InlineKeyboardRow(inline("‹ Aksiya menyusi", "promo:menu")))
                .build();
        show(chatId, messageId, sb.toString().trim(), kb);
    }

    private void sendPromoRemove(Long chatId, Integer messageId) {
        List<Product> list = promoProducts();
        if (list.isEmpty()) {
            show(chatId, messageId, "✅ Aksiyadagi mahsulot yöq — olib taşlaydigan narsa qolmadi.",
                    InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(inline("‹ Aksiya menyusi", "promo:menu"))).build());
            return;
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Product p : list) {
            rows.add(new InlineKeyboardRow(inline("✖️ " + p.getName() + " (-" + p.getDiscountPercent() + "%)", "promo:rm:" + p.getId())));
        }
        if (list.size() > 1) {
            rows.add(new InlineKeyboardRow(inline("🗑 Hammasini olib taşlaş", "promo:rmall")));
        }
        rows.add(new InlineKeyboardRow(inline("‹ Aksiya menyusi", "promo:menu")));
        show(chatId, messageId, "➖ <b>Aksiyani olib taşlaş</b>\n\nQaysi mahsulotdan aksiya olinsin? Bosilgan mahsulot asl narxiga qaytadi.",
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void onPromoCallback(CallbackQuery cq, Long chatId, Integer messageId, String[] p) {
        pending.remove(chatId);
        switch (p[1]) {
            case "menu" -> { answer(cq, null); sendPromoMenu(chatId, messageId); }
            case "add" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "promo"); }
            case "list" -> { answer(cq, null); sendPromoList(chatId, messageId); }
            case "rm" -> {
                if (p.length > 2) {
                    String name = menu.setDiscount(Long.parseLong(p[2]), 0);
                    answer(cq, "✅ «" + name + "» aksiyadan olindi");
                }  else {
                    answer(cq, null);
                }
                sendPromoRemove(chatId, messageId);
            }
            case "rmall" -> {
                answer(cq, null);
                show(chatId, messageId, "🗑 <b>Barça aksiyalarni</b> (" + promoProducts().size() + " ta mahsulot) olib taşlaysizmi?\n"
                                + "Hamma mahsulot asl narxiga qaytadi.",
                        InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                                inline("✅ Ha, hammasini", "promo:rmallok"), inline("✖️ Yöq", "promo:rm"))).build());
            }
            case "ann" -> {
                answer(cq, null);
                int count = promoProducts().size();
                if (count == 0) {
                    show(chatId, messageId, "🏷 Hozir aksiyada mahsulot yöq — e'lon qiladigan narsa yöq.",
                            InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(inline("‹ Aksiya menyusi", "promo:menu"))).build());
                    return;
                }
                int people = customers.broadcastTargets().size();
                show(chatId, messageId, "📢 <b>Aksiya e'loni</b>\n\nBarça mijozlarga (<b>" + people + " kişi</b>) şunday xabar boradi:\n\n"
                                + "<i>" + I18n.t(Lang.UZ, "bot.promo_announce", count) + "</i>\n\n"
                                + "va «🧁 Menyuni oçiş» tugmasi.\nYuboraymi?",
                        InlineKeyboardMarkup.builder()
                                .keyboardRow(new InlineKeyboardRow(inline("✅ Ha, yuborish", "promo:annok"), inline("✖️ Yöq", "promo:menu")))
                                .build());
            }
            case "annok" -> {
                answer(cq, "📢 Yuborilmoqda…");
                int count = promoProducts().size();
                show(chatId, messageId, "📢 E'lon yuborilmoqda… Tugagaç xabar beraman.", null);
                broadcastPromo(chatId, count);
            }
            case "rmallok" -> {
                List<Product> list = promoProducts();
                list.forEach(x -> menu.setDiscount(x.getId(), 0));
                answer(cq, "✅ " + list.size() + " ta mahsulotdan aksiya olindi");
                sendPromoMenu(chatId, messageId);
            }
            default -> answer(cq, null);
        }
    }

    /** Aksiya e'loni barcha mijozlarga (Telegram limiti: sekundiga ~30 xabar — biz 20 tadan oshirmaymiz). */
    private void broadcastPromo(Long adminChatId, int count) {
        var targets = customers.broadcastTargets();
        Thread.ofVirtual().start(() -> {
            int ok = 0;
            int fail = 0;
            for (var e : targets.entrySet()) {
                Lang lang = e.getValue();
                InlineKeyboardMarkup kb = props.bot().hasWebApp()
                        ? InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                                .text(I18n.t(lang, "bot.btn.open_menu")).webApp(new WebAppInfo(webAppUrl(lang))).build())).build()
                        : null;
                try {
                    client.execute(SendMessage.builder().chatId(e.getKey()).text(I18n.t(lang, "bot.promo_announce", count))
                            .parseMode("HTML").replyMarkup(kb).build());
                    ok++;
                } catch (TelegramApiException ex) {
                    fail++;
                    log.debug("Aksiya e'loni yetmadi (çat {}): {}", e.getKey(), ex.getMessage());
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            reply(adminChatId, "✅ Aksiya e'loni yuborildi: <b>" + ok + " kişi</b>"
                    + (fail > 0 ? "\n⚠️ Yetmadi: " + fail + " (botni bloklagan bölişi mumkin)" : ""), adminKeyboard());
        });
    }

    // ---- Bosh rasm ----

    private void sendBannerMenu(Long chatId, Integer messageId) {
        boolean has = banner.fileId().isPresent();
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(inline(has ? "🔄 Rasmni almaştiriş" : "⬆️ Rasm yuklaş", "bnr:set")));
        if (has) {
            rows.add(new InlineKeyboardRow(inline("👁 Köriş", "bnr:show"), inline("🗑 Öçiriş", "bnr:del")));
        }
        rows.add(new InlineKeyboardRow(inline("‹ Sozlamalar", "st:menu"), inline("✅ Tayyor", "done")));
        show(chatId, messageId, "🖼 <b>Bosh rasm</b> — " + (has ? "✅ yuklangan" : "yuklanmagan")
                        + "\n\nMini App oçilganda kutib olish sahifasida körinadigan katta rasm. "
                        + "Yotiq (eni böyidan katta) rasm eng çiroyli çiqadi.\n"
                        + "<i>Rasm yuklanmaguncha mahsulot rasmlaridan kollaj körsatiladi.</i>",
                InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void onBannerCallback(CallbackQuery cq, Long chatId, Integer messageId, String action) {
        switch (action) {
            case "menu" -> { answer(cq, null); pending.remove(chatId); sendBannerMenu(chatId, messageId); }
            case "set" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitBanner());
                show(chatId, messageId, "⬆️ <b>Bosh rasm</b>\n\nEndi rasmni yuboring.",
                        InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                                inline("‹ Orqaga", "bnr:menu"), inline("✖️ Bekor qiliş", "cancel"))).build());
            }
            case "show" -> {
                answer(cq, null);
                banner.fileId().ifPresent(fileId -> {
                    try {
                        client.execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder()
                                .chatId(chatId).photo(new org.telegram.telegrambots.meta.api.objects.InputFile(fileId))
                                .caption("🖼 Bosh rasm").build());
                    } catch (TelegramApiException e) {
                        log.warn("Bosh rasmni körsatib bölmadi: {}", e.getMessage());
                    }
                });
            }
            case "del" -> { banner.remove(); answer(cq, "🗑 Bosh rasm öçirildi"); sendBannerMenu(chatId, messageId); }
            default -> answer(cq, null);
        }
    }

    // ---- Narx va rasm uchun tanlash: kategoriya → mahsulot → (o'lcham) ----

    /** messageId null bo'lsa yangi xabar, aks holda mavjud xabarni tahrirlaydi. */
    private void sendCategoryPicker(Long chatId, Integer messageId, String mode) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (Category c : menu.activeCategories()) {
            buttons.add(inline((c.isActive() ? "" : "🙈 ") + (c.getEmoji() == null ? "" : c.getEmoji() + " ") + c.getName(),
                    "cat:" + mode + ":" + c.getId()));
        }
        InlineKeyboardMarkup kb = mode.equals("promo")
                ? grid(buttons, 2, new InlineKeyboardRow(inline("‹ Aksiya menyusi", "promo:menu"), inline("✖️ Bekor qiliş", "cancel")))
                : grid(buttons, 2, new InlineKeyboardRow(inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✖️ Bekor qiliş", "cancel")));
        String title = switch (mode) {
            case "price" -> "💰 <b>Narxni özgartiriş</b>";
            case "view" -> "📦 <b>Mahsulotlar</b>";
            case "set" -> "🎁 <b>Set tarkibi</b>";
            case "new" -> "➕ <b>Yangi mahsulot</b>\nQaysi kategoriyaga qöşamiz?";
            case "edit" -> "✏️ <b>Tahrirlaş / öçiriş</b>";
            case "stop" -> "⛔ <b>Mahsulot tugadi</b>";
            case "newset" -> "🎁 <b>Yangi set</b>\nQaysi kategoriyaga qöşamiz?";
            case "promo" -> "🏷 <b>Aksiya</b>\nÇegirma qöyiladigan mahsulot kategoriyasini tanlang";
            default -> "📷 <b>Rasm qöyiş</b>";
        };
        show(chatId, messageId, title + "\n\nKategoriyani tanlang:", kb);
    }

    /** Set tarkibi: sovg'a (🎁) kategoriyasi bitta bo'lsa — darhol setlar ro'yxati. */
    private void sendSetPicker(Long chatId, Integer messageId) {
        List<Category> gift = menu.activeCategories().stream().filter(c -> "🎁".equals(c.getEmoji())).toList();
        if (gift.size() == 1) {
            sendProductPicker(chatId, messageId, "set", gift.getFirst().getId());
        } else {
            sendCategoryPicker(chatId, messageId, "set");
        }
    }

    private void sendProductPicker(Long chatId, Integer messageId, String mode, Long categoryId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        String categoryName = "";
        for (Product p : menu.allProducts()) {
            if (!p.getCategory().getId().equals(categoryId)) {
                continue;
            }
            categoryName = p.getCategory().getName();
            String mark = mode.equals("set") && p.getContents() != null ? " ✅"
                    : mode.equals("photo") && p.getPhotoFileId() != null ? " 📷"
                    : mode.equals("promo") && p.getDiscountPercent() > 0 ? " 🏷 -" + p.getDiscountPercent() + "%" : "";
            buttons.add(inline(p.getName() + mark, "prod:" + mode + ":" + p.getId()));
        }
        InlineKeyboardMarkup kb = grid(buttons, mode.equals("photo") ? 2 : 1,
                new InlineKeyboardRow(inline("‹ Orqaga", "back:" + mode), inline("✖️ Bekor qiliş", "cancel")));
        String hint = mode.equals("photo") ? "\n<i>📷 — rasmi bor, yangisi bilan almaştiriladi</i>"
                : mode.equals("set") ? "\n<i>✅ — tarkibi yozilgan</i>" : "";
        show(chatId, messageId, "<b>" + esc(categoryName) + "</b>\nMahsulotni tanlang:" + hint, kb);
    }

    private void onProductPicked(Long chatId, Integer messageId, String mode, Long productId) {
        Product p = menu.allProducts().stream().filter(x -> x.getId().equals(productId)).findFirst()
                .orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        if (mode.equals("edit")) {
            sendProductCard(chatId, messageId, p.getId());
            return;
        }
        if (mode.equals("promo")) {
            pending.put(chatId, new AwaitDiscount(p.getId(), p.getName(), p.getCategory().getId()));
            String now = p.getDiscountPercent() > 0 ? "Hozir: <b>-" + p.getDiscountPercent() + "%</b>" : "Hozir aksiya yöq";
            show(chatId, messageId, "🏷 <b>" + esc(p.getName()) + "</b>\n" + now
                    + "\n\nÇegirma foizini yozing (masalan: <code>15</code>).\n<code>0</code> — aksiyani olib taşlaş.",
                    kb(row(inline("‹ Orqaga", "cat:promo:" + p.getCategory().getId()), inline("✖️ Bekor qiliş", "cancel"))));
            return;
        }
        if (mode.equals("set")) {
            pending.put(chatId, new AwaitContents(p.getId(), p.getName(), p.getCategory().getId()));
            String now = p.getContents() == null ? "Hozir tarkib yozilmagan"
                    : "Hozirgi tarkib:\n" + esc(p.getContents()).lines().map(l -> "• " + l).collect(java.util.stream.Collectors.joining("\n"));
            show(chatId, messageId, "🎁 <b>" + esc(p.getName()) + "</b>\n" + now
                    + "\n\nSet içiga nimalar kirişini yozing — <b>har qatorda bittadan</b>, masalan:\n"
                    + "<code>Damskiy palçik — 2 dona\nTrayfel (kiçik)\nŞokoladli qulupnay — 6 dona</code>\n\n"
                    + "<code>-</code> — tarkibni öçiriş.",
                    kb(row(inline("‹ Orqaga", "cat:set:" + p.getCategory().getId()), inline("✖️ Bekor qiliş", "cancel"))));
            return;
        }
        if (mode.equals("photo")) {
            pending.put(chatId, new AwaitPhoto(p.getId(), p.getName()));
            show(chatId, messageId, "📷 <b>" + esc(p.getName()) + "</b>\n\nEndi şu mahsulot rasmini yuboring.\n"
                    + "<i>Kvadrat rasm menyuda eng çiroyli çiqadi.</i>",
                    kb(row(inline("‹ Mahsulotga qaytiş", "pe:card:" + p.getId()), inline("✖️ Bekor qiliş", "cancel"))));
            return;
        }
        if (p.getVariants().size() == 1) {
            askPrice(chatId, messageId, p.getVariants().getFirst(), p);
            return;
        }
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (ProductVariant v : p.getVariants()) {
            buttons.add(inline(v.getName() + " — " + Money.format(v.getPrice()), "var:" + v.getId()));
        }
        InlineKeyboardMarkup kb = grid(buttons, 1,
                new InlineKeyboardRow(inline("‹ Orqaga", "cat:price:" + p.getCategory().getId()), inline("✖️ Bekor qiliş", "cancel")),
                new InlineKeyboardRow(inline("✏️ Mahsulot kartoçkasi", "pe:card:" + p.getId())));
        show(chatId, messageId, "💰 <b>" + esc(p.getName()) + "</b>\nQaysi ölçam narxini özgartiramiz?", kb);
    }

    private void askPrice(Long chatId, Integer messageId, ProductVariant v, Product p) {
        String title = p.getName() + (v.getName() == null ? "" : " (" + v.getName() + ")");
        pending.put(chatId, new AwaitPrice(v.getId(), title));
        show(chatId, messageId, "💰 <b>" + esc(title) + "</b>\nHozirgi narx: " + Money.format(v.getPrice())
                + "\n\nYangi narxni yozing, masalan: <code>95000</code>",
                kb(row(inline("‹ Orqaga", p.getVariants().size() > 1 ? "prod:price:" + p.getId() : "cat:price:" + p.getCategory().getId()),
                        inline("✖️ Bekor qiliş", "cancel")), row(inline("✏️ Mahsulot kartoçkasi", "pe:card:" + p.getId()))));
    }

    /** Yangi mahsulot/set qadamlari: mahsulotlar menyusiga qaytish yoki bekor qilish. */
    private InlineKeyboardMarkup wizardKeyboard() {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✖️ Bekor qiliş", "cancel"))).build();
    }

    private InlineKeyboardMarkup cancelKeyboard() {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(inline("✖️ Bekor qiliş", "cancel"))).build();
    }

    // ------------------------------------------------------------------ admin: yangi bo'limlar

    /** Pastdagi admin tugmalari (xabar yuborish kutilayotganda ular bosilsa — bekor qilinadi). */
    private static final java.util.Set<String> ADMIN_BUTTONS = java.util.Set.of(BTN_ORDERS, BTN_STATS, BTN_STOPLIST,
            BTN_SOLDOUT, BTN_STOPLIST_OLD, BTN_PRODUCTS, BTN_PRICE, BTN_PHOTO, BTN_CUSTOMERS, BTN_PROMO, BTN_BANNER,
            BTN_HELP, BTN_SET, BTN_SETTINGS, BTN_BROADCAST);

    private String categoryName(Long categoryId) {
        return menu.activeCategories().stream().filter(c -> c.getId().equals(categoryId))
                .map(Category::getName).findFirst().orElse("");
    }

    private static InlineKeyboardMarkup kb(InlineKeyboardRow... rows) {
        return InlineKeyboardMarkup.builder().keyboard(List.of(rows)).build();
    }

    private static InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        return new InlineKeyboardRow(buttons);
    }

    // ---- 📦 Mahsulotlar menyusi ----

    private void sendProductsMenu(Long chatId, Integer messageId) {
        List<Product> all = menu.allProducts();
        int total = all.size();
        long soldOut = all.stream().filter(p -> !p.isAvailable()).count();
        // menyuda ko'rinadigan kategoriyalar — kamida bitta mahsuloti borlari
        long categories = all.stream().map(p -> p.getCategory().getId()).distinct().count();
        show(chatId, messageId, "📦 <b>Mahsulotlar</b>\n\nMenyuda <b>" + total + " ta mahsulot</b>, <b>" + categories + " ta kategoriya</b>"
                        + (soldOut > 0 ? "\n⛔ Tugagan: <b>" + soldOut + " ta</b>" : "") + "\n\nNima qilamiz?",
                kb(row(inline("➕ Yangi mahsulot", "pm:new"), inline("🎁 Yangi set", "pm:newset")),
                        row(inline("✏️ Tahrirlaş / öçiriş (rasm ham)", "pm:edit")),
                        row(inline("💰 Narx:", "pm:price")),
                        row(inline("⛔ Mahsulot tugadi", "pm:stop"), inline("🚫 Tugagan mahsulotlar", "pm:soldout")),
                        row(inline("🎁 Set tarkibi", "pm:set"), inline("👁 Röyxatni köriş", "pm:view")),
                        row(inline("📂 Kategoriyalar", "pm:cats")),
                        row(inline("📥 Barça mahsulotlar (Excel)", "pm:excel")),
                        row(inline("✅ Tayyor", "done"))));
    }

    /** Mahsulot kartochkasi: nomi, tavsifi, narxlari va tahrirlash tugmalari. */
    private void sendProductCard(Long chatId, Integer messageId, Long productId) {
        Product p = menu.allProducts().stream().filter(x -> x.getId().equals(productId)).findFirst()
                .orElseThrow(() -> new BusinessException("Mahsulot topilmadi (öçirilgan bölişi mumkin)"));
        StringBuilder sb = new StringBuilder("✏️ <b>").append(esc(p.getName())).append("</b>\n")
                .append("📂 ").append(esc(p.getCategory().getName())).append('\n')
                .append("📝 ").append(p.getDescription() == null ? "<i>tavsif yöq</i>" : esc(p.getDescription())).append("\n\n");
        for (ProductVariant v : p.getVariants()) {
            sb.append("💰 ").append(v.getName() == null ? "" : esc(v.getName()) + " — ").append(Money.format(v.getPrice())).append('\n');
        }
        int[] pos = menu.positionInCategory(p.getId());
        sb.append("\n↕️ Kategoriyadagi örni: <b>").append(pos[0]).append(" / ").append(pos[1]).append("</b>");
        sb.append("\n🔢 Donada sotiladi: ").append(p.isPerPiece() ? "<b>ha</b> (narx yonida «/ dona»)" : "yöq");
        sb.append("\n📷 Rasm: ").append(p.getPhotoFileId() == null ? "yöq" : "bor")
                .append("\n").append(p.isAvailable() ? "✅ Sotuvda" : "⛔ Tugagan")
                .append(p.getDiscountPercent() > 0 ? " · 🏷 -" + p.getDiscountPercent() + "%" : "");
        show(chatId, messageId, sb.toString(),
                kb(row(inline("✏️ Nomi", "pe:name:" + p.getId()), inline("📝 Tavsifi", "pe:desc:" + p.getId())),
                        row(inline("💰 Narx:", "pe:price:" + p.getId()), inline("📷 Rasm", "pe:photo:" + p.getId())),
                        MenuService.isCakeCategory(p.getCategory()) || p.getVariants().size() > 1
                                ? row(inline("📏 Ölçamlar", "pe:sizes:" + p.getId()), inline("📂 Kategoriyasi", "pe:move:" + p.getId()))
                                : row(inline("📂 Kategoriyasi", "pe:move:" + p.getId())),
                        row(inline(p.isPerPiece() ? "🔢 Donada: ha → yöq qiliş" : "🔢 Donada sotiladi", "pe:piece:" + p.getId())),
                        row(inline("⬆️ Yuqoriga", "pe:up:" + p.getId()), inline("⬇️ Pastga", "pe:down:" + p.getId())),
                        row(inline("🗑 Öçiriş", "pe:del:" + p.getId())),
                        row(inline("‹ Orqaga", "cat:edit:" + p.getCategory().getId()), inline("✅ Tayyor", "done"))));
    }

    private boolean isCake(Long categoryId) {
        return menu.activeCategories().stream().filter(c -> c.getId().equals(categoryId)).findFirst()
                .map(MenuService::isCakeCategory).orElse(false);
    }

    /** «150 000», «150000 so'm» → 150000; noto'g'ri bo'lsa null. */
    private static Long parsePrice(String text) {
        String digits = text == null ? "" : text.replaceAll("[\\s_.,]|so'm|söm|сум|sum", "");
        return digits.matches("\\d{3,9}") ? Long.parseLong(digits) : null;
    }

    private void startNewSet(Long chatId, Integer messageId, Long categoryId) {
        pending.put(chatId, new AwaitNewProduct(1, categoryId, null, null, null, true, null));
        show(chatId, messageId, "🎁 <b>Yangi set</b> · " + esc(categoryName(categoryId))
                + "\n\n<b>1/4.</b> Set nomini yozing, masalan: <code>Şirin quti</code>", wizardKeyboard());
    }

    /** Mahsulot o'lchamlari: ro'yxat, o'chirish va yangi qo'shish. */
    private void sendSizes(Long chatId, Integer messageId, Long productId) {
        Product p = menu.allProducts().stream().filter(x -> x.getId().equals(productId)).findFirst()
                .orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        StringBuilder sb = new StringBuilder("📏 <b>").append(esc(p.getName())).append("</b> — ölçamlar\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (ProductVariant v : p.getVariants()) {
            String name = v.getName() == null ? "Standart" : v.getName();
            sb.append("• ").append(esc(name)).append(" — ").append(Money.format(v.getPrice())).append('\n');
            if (p.getVariants().size() > 1) {
                rows.add(row(inline("✖️ " + name + " — öçiriş", "pe:vdel:" + v.getId())));
            }
        }
        if (p.getVariants().size() == 1) {
            sb.append("\n<i>Oxirgi ölçamni öçirib bölmaydi.</i>");
        }
        if (MenuService.isCakeCategory(p.getCategory())) {
            List<InlineKeyboardButton> add = new ArrayList<>();
            for (int i = 0; i < MenuService.CAKE_SIZES.size(); i++) {
                String size = MenuService.CAKE_SIZES.get(i);
                boolean has = p.getVariants().stream().anyMatch(v -> v.getName() != null
                        && uz.habibi.bakery.i18n.NewAlphabet.convert(v.getName()).equalsIgnoreCase(size));
                if (!has) {
                    add.add(inline("➕ " + size, "pe:vadd:" + p.getId() + ":" + i));
                }
            }
            if (!add.isEmpty()) {
                rows.add(new InlineKeyboardRow(add));
            }
        } else {
            sb.append("\n\n<i>Ölçamlar (Butun / Yarim / Çorak) faqat 🎂 Desertlar kategoriyasida böladi.</i>");
        }
        rows.add(row(inline("‹ Mahsulotga qaytiş", "pe:card:" + p.getId()), inline("✅ Tayyor", "done")));
        show(chatId, messageId, sb.toString().trim(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    /** «📂 Kategoriyalar»: har biri tugma — bosilsa boshqaruv kartochkasi. */
    private void sendCategoriesList(Long chatId, Integer messageId) {
        StringBuilder sb = new StringBuilder("📂 <b>Kategoriyalar</b>\n<i>Menyuda eng köp buyurtma qilinadigani birinçi turadi</i>\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Category c : menu.activeCategories()) {
            long n = menu.allProducts().stream().filter(x -> x.getCategory().getId().equals(c.getId())).count();
            String icon = c.getEmoji() == null ? "•" : c.getEmoji();
            sb.append(icon).append(' ').append(esc(c.getName())).append(" — ").append(n).append(" ta")
                    .append(!c.isActive() ? " · 🙈 <i>yaşirilgan</i>" : n == 0 ? " · <i>böş, menyuda körinmaydi</i>" : "").append('\n');
            rows.add(row(inline((c.isActive() ? "" : "🙈 ") + icon + " " + c.getName(), "pc:card:" + c.getId())));
        }
        sb.append("\nBoşqariş uçun kategoriyani bosing 👇");
        rows.add(row(inline("➕ Yangi kategoriya", "pm:newcat")));
        rows.add(row(inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✅ Tayyor", "done")));
        show(chatId, messageId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private void sendCategoryCard(Long chatId, Integer messageId, Long categoryId) {
        List<Category> all = menu.activeCategories();
        Category c = all.stream().filter(x -> x.getId().equals(categoryId)).findFirst()
                .orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        int pos = all.indexOf(c) + 1;
        long n = menu.allProducts().stream().filter(x -> x.getCategory().getId().equals(categoryId)).count();
        String text = (c.getEmoji() == null ? "📂" : c.getEmoji()) + " <b>" + esc(c.getName()) + "</b>\n\n"
                + "📦 Mahsulotlar: <b>" + n + " ta</b>\n"
                + (c.isActive() ? "👁 Mijozlarga körinadi" : "🙈 <b>Yaşirilgan</b> — mijozlar körmaydi") + "\n"
                + "↕️ Zaxira tartibi: <b>" + pos + " / " + all.size() + "</b>\n\n"
                + "<i>Menyuda kategoriyalar avtomatik turadi: yangisi (5 kun) birinçi, keyin oxirgi 30 kunda eng köp "
                + "buyurtma qilingani. ⬆️/⬇️ — buyurtmalar soni teng bölganda işlatiladi.</i>";
        show(chatId, messageId, text, kb(
                row(inline("✏️ Nomini özgartiriş", "pc:ren:" + categoryId)),
                row(c.isActive() ? inline("🙈 Yaşiriş", "pc:vis:" + categoryId) : inline("👁 Körsatiş", "pc:vis:" + categoryId)),
                row(inline("⬆️ Yuqoriga", "pc:up:" + categoryId), inline("⬇️ Pastga", "pc:down:" + categoryId)),
                row(inline("🗑 Öçiriş", "pc:del:" + categoryId)),
                row(inline("‹ Kategoriyalar", "pm:cats"), inline("✅ Tayyor", "done"))));
    }

    private void saveNewProduct(Long chatId, AwaitNewProduct np, String photoFileId) {
        try {
            Long id = menu.createProduct(np.categoryId(), np.name(), np.description(), np.sizes(), photoFileId);
            if (np.contents() != null) {
                menu.setContents(id, np.contents());
            }
            StringBuilder sb = new StringBuilder(np.set() ? "✅ <b>Yangi set qöşildi!</b>\n\n🎁 <b>" : "✅ <b>Yangi mahsulot qöşildi!</b>\n\n🧁 <b>")
                    .append(esc(np.name())).append("</b>\n");
            if (np.description() != null) {
                sb.append("📝 ").append(esc(np.description())).append('\n');
            }
            if (np.contents() != null) {
                sb.append(esc(np.contents()).lines().map(l -> "   • " + l).collect(java.util.stream.Collectors.joining("\n"))).append('\n');
            }
            for (MenuService.NewVariant v : np.sizes()) {
                sb.append("💰 ").append(v.name() == null ? "" : esc(v.name()) + " — ").append(Money.format(v.price())).append('\n');
            }
            sb.append(photoFileId == null ? "📷 Rasmsiz (keyin «📷 Rasm» orqali qöşasiz)\n" : "📷 Rasm bilan\n");
            sb.append("\nMenyuda darhol körinadi.");
            reply(chatId, sb.toString(), kb(
                    row(inline(np.set() ? "🎁 Yana set" : "➕ Yana mahsulot", np.set() ? "pm:newset" : "pm:new"),
                            inline("✏️ Uni tahrirlaş", "pe:card:" + id)),
                    row(inline("‹ Mahsulotlar menyusi", "pm:menu"), inline("✅ Tayyor", "done"))));
        } catch (BusinessException e) {
            reply(chatId, "⚠️ " + e.getMessage(), adminKeyboard());
        }
    }

    // ---- ⚙️ Sozlamalar ----

    private void sendSettings(Long chatId, Integer messageId) {
        var shop = settings.shop();
        boolean paused = settings.isPaused();
        boolean openNow = shop.isOpen(java.time.LocalTime.now(props.zone()));
        String card = shop.cardNumber() == null || shop.cardNumber().isBlank() ? "<i>kiritilmagan</i>"
                : "<code>" + esc(shop.cardNumber()) + "</code>" + (shop.cardHolder() == null || shop.cardHolder().isBlank() ? "" : " · " + esc(shop.cardHolder()));
        String text = "⚙️ <b>Sozlamalar</b>\n\n"
                + (paused ? "⏸ <b>Buyurtmalar töxtatilgan</b> — mijozlar buyurtma bera olmaydi\n"
                          : "🟢 Buyurtmalar qabul qilinmoqda\n")
                + "🕐 Ish vaqti: <b>" + shop.opens() + "–" + shop.closes() + "</b>" + (openNow ? " (hozir oçiq)" : " (hozir yopiq)") + "\n"
                + "💳 Karta: " + card + "\n"
                + "👩‍🍳 Adminlar: <b>" + settings.adminChats().size() + " ta</b>";
        show(chatId, messageId, text, kb(
                row(paused ? inline("▶️ Buyurtmalarni qayta yoqiş", "st:resume") : inline("⏸ Buyurtmalarni töxtatiş", "st:pause")),
                row(inline("🕐 Ish vaqti", "st:hours"), inline("💳 Tölov kartasi", "st:card")),
                row(inline("🖼 Bosh rasm", "st:banner"), inline("👩‍🍳 Adminlar", "st:admins")),
                row(inline("✅ Tayyor", "done"))));
    }

    private void sendAdmins(Long chatId, Integer messageId) {
        StringBuilder sb = new StringBuilder("👩‍🍳 <b>Adminlar</b>\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (props.bot().hasAdmin()) {
            sb.append("👑 ").append(adminLabel(props.bot().adminChatId())).append(" — asosiy\n");
        }
        boolean main = settings.isMainAdmin(chatId);
        for (Long id : settings.extraAdmins()) {
            boolean sup = settings.isSuperAdmin(id);
            sb.append(sup ? "⭐️ " : "👤 ").append(adminLabel(id)).append(sup ? " — super admin" : "").append('\n');
            String name = customers.summary(id).map(CustomerSummary::displayName).orElse(String.valueOf(id));
            if (main) {
                // super adminni faqat asosiy admin tayinlaydi / oladi
                rows.add(row(sup ? inline("⬇️ " + name + " — super adminlikdan oliş", "adm:sup0:" + id)
                        : inline("⭐️ " + name + " — super admin qiliş", "adm:sup1:" + id)));
            }
            // super admin oddiy adminlarni olib taşlay oladi, super adminlarni — faqat asosiy admin
            if (main || (settings.isSuperAdmin(chatId) && !sup)) {
                rows.add(row(inline("✖️ " + name + " — olib taşlaş", "adm:rm:" + id)));
            }
        }
        sb.append("\nYangi buyurtma va çeklar barça adminlarga keladi. Kim tugmani bossa, hammada yangilanadi.");
        sb.append("\n⭐️ Super admin admin qöşa va oddiy adminlarni olib taşlay oladi. Asosiy adminni hech kim olib taşlay olmaydi.");
        if (settings.canManageAdmins(chatId)) {
            rows.add(row(inline("➕ Admin qöşiş", "adm:add")));
        } else {
            sb.append("\n\n<i>Admin qöşiş/olib taşlaşni faqat asosiy admin va super adminlar qila oladi.</i>");
        }
        rows.add(row(inline("‹ Sozlamalar", "st:menu"), inline("✅ Tayyor", "done")));
        show(chatId, messageId, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
    }

    private String adminLabel(Long id) {
        return customers.summary(id).map(c -> esc(c.displayName()) + (c.phone() == null ? "" : ", " + esc(c.phone())))
                .orElse("ID " + id);
    }

    // ---- 📢 Xabar yuborish ----

    private void startBroadcast(Long chatId) {
        pending.put(chatId, new AwaitBroadcast());
        reply(chatId, "📢 <b>Barça mijozlarga xabar</b>\n\n"
                + "Xabarni yozing yoki <b>rasm / video</b> yuboring (izohi bilan). Mijozlarga aynan şu körinişda boradi, "
                + "ostiga «🧁 Menyuni oçiş» tugmasi qöşiladi.\n\n"
                + "Hozir xabar oladiganlar: <b>" + customers.broadcastTargets().size() + " kişi</b>", cancelKeyboard());
    }

    private void captureBroadcast(Long chatId, Integer messageId) {
        pending.put(chatId, new AwaitBroadcastConfirm(chatId, messageId));
        int people = customers.broadcastTargets().size();
        try {
            client.execute(SendMessage.builder().chatId(chatId)
                    .text("👆 Şu xabar <b>" + people + " kişi</b>ga yuboriladi. Yuboraymi?")
                    .parseMode("HTML").replyToMessageId(messageId)
                    .replyMarkup(kb(row(inline("✅ Ha, yuboriş", "bc:ok"), inline("✖️ Bekor qiliş", "cancel"))))
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Tasdiqlaş xabari yuborilmadi: {}", e.getMessage());
        }
    }

    /** Admin yozgan xabarni (matn/rasm/video) har bir mijozga nusxa qilib yuboradi — sekundiga ~20 ta. */
    private void sendBroadcast(Long adminChatId, Long fromChat, Integer messageId) {
        var targets = customers.broadcastTargets();
        Thread.ofVirtual().start(() -> {
            int ok = 0;
            int fail = 0;
            for (var e : targets.entrySet()) {
                Lang lang = e.getValue();
                try {
                    var copy = org.telegram.telegrambots.meta.api.methods.CopyMessage.builder()
                            .chatId(e.getKey()).fromChatId(fromChat).messageId(messageId);
                    if (props.bot().hasWebApp()) {
                        copy.replyMarkup(kb(row(InlineKeyboardButton.builder().text(I18n.t(lang, "bot.btn.open_menu"))
                                .webApp(new WebAppInfo(webAppUrl(lang))).build())));
                    }
                    client.execute(copy.build());
                    ok++;
                } catch (TelegramApiException ex) {
                    fail++;
                    log.debug("Xabar yetmadi (çat {}): {}", e.getKey(), ex.getMessage());
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            reply(adminChatId, "✅ Xabar yuborildi: <b>" + ok + " kişi</b>"
                    + (fail > 0 ? "\n⚠️ Yetmadi: " + fail + " (botni bloklagan bölişi mumkin)" : ""), adminKeyboard());
        });
    }

    // ---- 🔍 Qidiruv va tekshirilmagan cheklar ----

    private void sendOrderList(Long chatId, Integer messageId, String title, List<OrderDetails> list, InlineKeyboardRow extra) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
        // Telegram xabari 4096 belgidan oshmasin
        int max = 20;
        for (OrderDetails o : list.stream().limit(max).toList()) {
            sb.append("<b>#").append(o.id()).append("</b> · ").append(f.format(o.createdAt().atZone(props.zone()))).append('\n')
                    .append("   ").append(esc(o.customerName())).append(", ").append(esc(o.phone())).append('\n')
                    .append("   ").append(Money.format(o.itemsTotal())).append(" · ").append(o.status().label()).append('\n')
                    .append("   ").append(OrderMessages.receiptState(o)).append("\n\n");
            buttons.add(inline("#" + o.id(), "show:" + o.id()));
        }
        if (list.size() > max) {
            sb.append("<i>… va yana ").append(list.size() - max).append(" ta (eskiroqlari). Kerakli buyurtmani 🔍 Qidiriş orqali toping.</i>\n\n");
        }
        sb.append("👇 Buyurtmani oçiş uçun raqamini bosing");
        show(chatId, messageId, sb.toString(), extra == null ? grid(buttons, 4) : grid(buttons, 4, extra));
    }

    // ---- 🧾 Cheklar ----

    /** T — bugungi cheklar, U — tekshirilmaganlar. */
    private List<OrderDetails> receipts(String filter) {
        return filter.equals("U") ? orders.uncheckedReceiptPhotos() : orders.receiptsToday(props.zone());
    }

    private void sendReceiptsMenu(Long chatId, Integer messageId) {
        List<OrderDetails> today = orders.receiptsToday(props.zone());
        List<OrderDetails> paid = today.stream().filter(o -> o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.PAID).toList();
        long rejected = today.stream().filter(o -> o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.AWAITING_RECEIPT).count();
        int unchecked = orders.uncheckedReceiptPhotos().size();
        long sum = paid.stream().mapToLong(OrderDetails::itemsTotal).sum();
        show(chatId, messageId, "🧾 <b>Çeklar</b> · bugun\n\n"
                        + "📥 Kelgan: <b>" + today.size() + " ta</b>\n"
                        + "✅ Tasdiqlangan: <b>" + paid.size() + " ta · " + Money.format(sum) + "</b>\n"
                        + "⏳ Tekşirilmagan: <b>" + unchecked + "</b>\n"
                        + "❌ Rad etilgan: <b>" + rejected + "</b>\n\n"
                        + "<i>Kun oxirida «Tasdiqlangan» summani bank ilovasidagi tuşum bilan solişting.</i>",
                kb(row(inline("🧾 Bugungi çeklar (" + today.size() + ")", "rc:T:0")),
                        row(inline("⏳ Tekşirilmaganlar (" + unchecked + ")", "rc:U:0")),
                        row(inline("📥 Excel — bugungi çeklar", "rc:excel")),
                        row(inline("‹ Buyurtmalar", "ord:menu"), inline("✅ Tayyor", "done"))));
    }

    /** Bitta chek: rasmi, izohi va ◀️ ▶️ tugmalari. messageId null — yangi xabar, aks holda o'sha rasm almashtiriladi. */
    private void showReceipt(Long chatId, Integer messageId, String filter, int index) throws TelegramApiException {
        List<OrderDetails> list = receipts(filter);
        if (list.isEmpty()) {
            if (messageId != null) {
                deleteMessage(chatId, messageId);
            }
            reply(chatId, filter.equals("U") ? "✅ Tekşirilmagan çek qolmadi — hammasi körib çiqildi." : "Bugun hali çek kelmagan.",
                    kb(row(inline("‹ Çeklar", "rc:menu"))));
            return;
        }
        int i = Math.floorMod(index, list.size());
        OrderDetails o = list.get(i);
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
        String caption = "🧾 <b>#" + o.id() + "</b> · " + f.format(o.createdAt().atZone(props.zone())) + "\n"
                + "👤 " + esc(o.customerName()) + ", " + esc(o.phone()) + "\n"
                + "💰 Kutilgan summa: <b>" + Money.format(o.itemsTotal()) + "</b>\n\n"
                + "<b>" + OrderMessages.receiptState(o) + "</b>";
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.RECEIPT_SENT) {
            rows.add(row(inline("✅ Tasdiqlaş", "rc:ok:" + filter + ":" + i + ":" + o.id()),
                    inline("❌ Rad etiş", "rc:no:" + filter + ":" + i + ":" + o.id())));
        }
        rows.add(row(inline("◀️", "rc:" + filter + ":" + (i - 1)), inline((i + 1) + " / " + list.size(), "rc:" + filter + ":" + i),
                inline("▶️", "rc:" + filter + ":" + (i + 1))));
        rows.add(row(inline("📋 Buyurtmani oçiş", "show:" + o.id())));
        rows.add(row(inline("‹ Çeklar", "rc:menu"), inline("✖️ Yopiş", "rc:close")));
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(rows).build();
        if (messageId != null) {
            try {
                client.execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia.builder()
                        .chatId(chatId).messageId(messageId)
                        .media(org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto.builder()
                                .media(o.receiptFileId()).caption(caption).parseMode("HTML").build())
                        .replyMarkup(markup).build());
                return;
            } catch (TelegramApiException e) {
                if (String.valueOf(e.getMessage()).contains("not modified")) {
                    return;
                }
                log.debug("Çekni almaştirib bölmadi, yangisini yuboraman: {}", e.getMessage());
            }
        }
        client.execute(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.builder().chatId(chatId).photo(new org.telegram.telegrambots.meta.api.objects.InputFile(o.receiptFileId()))
                .caption(caption).parseMode("HTML").replyMarkup(markup).build());
    }

    private void deleteMessage(Long chatId, Integer messageId) {
        try {
            client.execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage.builder()
                    .chatId(chatId).messageId(messageId).build());
        } catch (TelegramApiException e) {
            log.debug("Xabarni öçirib bölmadi: {}", e.getMessage());
        }
    }

    private void onReceiptCallback(CallbackQuery cq, Long chatId, Integer messageId, String[] p) throws TelegramApiException {
        boolean onPhoto = cq.getMessage() instanceof Message msg && msg.hasPhoto();
        switch (p[1]) {
            case "menu" -> {
                answer(cq, null);
                if (onPhoto) {
                    sendReceiptsMenu(chatId, null);
                } else {
                    sendReceiptsMenu(chatId, messageId);
                }
            }
            case "close" -> {
                answer(cq, null);
                deleteMessage(chatId, messageId);
            }
            case "excel" -> {
                answer(cq, "📥 Fayl tayyorlanmoqda...");
                sendReceiptsCsv(chatId);
            }
            case "ok", "no" -> {
                String filter = p[2];
                int index = Integer.parseInt(p[3]);
                OrderDetails o = orders.apply(Long.parseLong(p[4]), p[1].equals("ok") ? AdminAction.PAY_OK : AdminAction.PAY_NO);
                answer(cq, p[1].equals("ok") ? "✅ Tölov tasdiqlandi" : "❌ Çek rad etildi — mijozga xabar bordi");
                // tekshirilmaganlar ro'yxatidan chiqib ketadi — o'sha joyda keyingisi turadi
                showReceipt(chatId, messageId, filter, index);
            }
            default -> {
                // rc:T:3 / rc:U:0 — ko'rish
                answer(cq, null);
                showReceipt(chatId, onPhoto ? messageId : null, p[1], Integer.parseInt(p[2]));
            }
        }
    }

    // ---- matn kutilayotgan holatlar ----

    /** Yangi bo'limlarda admin yozgan matn. true — ishlatildi. */
    private boolean onAdminPendingText(Long chatId, String text) {
        Pending state = pending.get(chatId);
        if (state instanceof AwaitSearch) {
            pending.remove(chatId);
            List<OrderDetails> found = orders.search(text);
            InlineKeyboardRow again = row(inline("🔍 Yana qidiriş", "ord:search"));
            if (found.isEmpty()) {
                reply(chatId, "🔍 «" + esc(text) + "» böyiça buyurtma topilmadi.\n"
                        + "<i>Buyurtma raqamini (masalan <code>45</code>) yoki telefon raqamini yozing.</i>", kb(again));
            } else if (found.size() == 1) {
                notifier.showFull(found.getFirst(), chatId);
            } else {
                sendOrderList(chatId, null, "🔍 <b>Topildi: " + found.size() + " ta</b>", found, again);
            }
            return true;
        }
        if (state instanceof AwaitCategoryRename r) {
            try {
                String name = menu.renameCategory(r.categoryId(), text);
                pending.remove(chatId);
                reply(chatId, "✅ Yangi nom: <b>" + esc(name) + "</b>", null);
                sendCategoryCard(chatId, null, r.categoryId());
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage() + "\nQaytadan yozing:", kb(row(inline("‹ Orqaga", "pc:card:" + r.categoryId()),
                        inline("✖️ Bekor qiliş", "cancel"))));
            }
            return true;
        }
        if (state instanceof AwaitCategory c && c.name() == null) {
            String name = text.strip();
            if (name.length() < 2 || name.length() > 40 || name.startsWith("/")) {
                reply(chatId, "Kategoriya nomi 2–40 belgi bölişi kerak. Qaytadan yozing:", cancelKeyboard());
                return true;
            }
            boolean exists = menu.activeCategories().stream().anyMatch(x -> x.getName().equalsIgnoreCase(name));
            if (exists) {
                reply(chatId, "«" + esc(name) + "» kategoriyasi allaqaçon bor. Boşqa nom yozing:", cancelKeyboard());
                return true;
            }
            pending.put(chatId, new AwaitCategory(name));
            List<InlineKeyboardButton> emojis = new ArrayList<>();
            for (int i = 0; i < CATEGORY_EMOJI.size(); i++) {
                emojis.add(inline(CATEGORY_EMOJI.get(i), "pm:catemoji:" + i));
            }
            reply(chatId, "📂 <b>" + esc(name) + "</b>\n\nKategoriya uçun belgi tanlang:",
                    grid(emojis, 6, row(inline("Belgisiz", "pm:catemoji:-1")), row(inline("✖️ Bekor qiliş", "cancel"))));
            return true;
        }
        if (state instanceof AwaitAddSize a) {
            Long price = parsePrice(text);
            if (price == null) {
                reply(chatId, "Narxni faqat raqam bilan yozing, masalan: <code>160000</code>", cancelKeyboard());
                return true;
            }
            try {
                menu.addVariant(a.productId(), a.size(), price);
                pending.remove(chatId);
                reply(chatId, "✅ " + a.size() + " — " + Money.format(price) + " qöşildi", null);
                sendSizes(chatId, null, a.productId());
            } catch (BusinessException e) {
                pending.remove(chatId);
                reply(chatId, "⚠️ " + e.getMessage(), kb(row(inline("‹ Ölçamlar", "pe:sizes:" + a.productId()))));
            }
            return true;
        }
        if (state instanceof AwaitTracking t) {
            String url = OrderService.extractTrackingUrl(text);
            if (url == null) {
                reply(chatId, "⚠️ Bu Yandex havolasiga öxşamayapti. Yandex Go'dagi «Ulaşiş» havolasini yuboring "
                                + "(<code>https://...yandex...</code>) yoki havolasiz davom eting.",
                        kb(row(inline("⏭ Havolasiz davom etiş", "trk:skip:" + t.orderId())), row(inline("✖️ Bekor qiliş", "cancel"))));
                return true;
            }
            pending.remove(chatId);
            try {
                OrderDetails o = orders.dispatch(t.orderId(), url);
                reply(chatId, "✅ #" + o.id() + " — yölga çiqdi. Mijozga kuzatiş havolasi bilan xabar bordi 📍", null);
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage(), null);
            }
            return true;
        }
        if (state instanceof AwaitCustomerSearch) {
            pending.remove(chatId);
            List<CustomerSummary> found = customers.search(text);
            InlineKeyboardRow again = row(inline("🔍 Yana qidiriş", "ord:csearch"), inline("‹ Mijozlar röyxati", "cust:0"));
            if (found.isEmpty()) {
                reply(chatId, "🔍 «" + esc(text) + "» böyiça mijoz topilmadi.", kb(again));
            } else if (found.size() == 1) {
                sendCustomerDetail(chatId, null, found.getFirst().telegramId(), 0);
            } else {
                StringBuilder sb = new StringBuilder("🔍 <b>Topildi: " + found.size() + " ta</b>"
                        + (found.size() == 20 ? " (birinçi 20 tasi — aniqroq yozing)" : "") + "\n\n");
                List<InlineKeyboardButton> numbers = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    CustomerSummary c = found.get(i);
                    sb.append(i + 1).append(". ").append(c.blocked() ? "🚫 " : "").append(esc(c.displayName()))
                            .append(c.username() == null ? "" : " · @" + esc(c.username())).append('\n')
                            .append("   📱 ").append(c.phone() == null ? "—" : esc(c.phone()))
                            .append(" · 🛍 ").append(c.orders()).append(" ta\n");
                    numbers.add(inline(String.valueOf(i + 1), "custd:" + c.telegramId() + ":0"));
                }
                sb.append("\n👇 Mijozni oçiş uçun raqamini bosing");
                reply(chatId, sb.toString(), grid(numbers, 5, again));
            }
            return true;
        }
        if (state instanceof AwaitNewProduct np) {
            switch (np.step()) {
                case 1 -> {
                    String name = text.strip();
                    if (name.length() < 2 || name.length() > 100 || name.startsWith("/")) {
                        reply(chatId, "Nom 2–100 belgi bölişi kerak. Qaytadan yozing:", wizardKeyboard());
                        return true;
                    }
                    pending.put(chatId, new AwaitNewProduct(2, np.categoryId(), name, null, null, np.set(), null));
                    reply(chatId, (np.set() ? "🎁 <b>" : "🧁 <b>") + esc(name) + "</b>\n\n<b>2/4.</b> Qisqa tavsif yozing, masalan: "
                            + "<code>Pistali va şokoladli, 8–10 kişilik</code>\n\nKerak bölmasa — <code>-</code> yozing.", wizardKeyboard());
                }
                case 2 -> {
                    String d = text.strip();
                    if (d.length() > 300) {
                        reply(chatId, "Tavsif juda uzun (300 belgigaça). Qisqaroq yozing:", wizardKeyboard());
                        return true;
                    }
                    if (np.set()) {
                        // setlarda tarkib so'ralmaydi — mijoz rasmdan ko'radi
                        pending.put(chatId, new AwaitNewProduct(3, np.categoryId(), np.name(), d.equals("-") ? null : d, null, true, null));
                        reply(chatId, "<b>3/4.</b> Set narxini yozing, masalan: <code>250000</code>", wizardKeyboard());
                        return true;
                    }
                    pending.put(chatId, new AwaitNewProduct(3, np.categoryId(), np.name(), d.equals("-") ? null : d, null, false, null));
                    reply(chatId, isCake(np.categoryId())
                            ? "<b>3/4.</b> Narxlari. Avval <b>Butun</b> narxini yozing, masalan: <code>300000</code>"
                            : "<b>3/4.</b> Narxini yozing, masalan: <code>150000</code>", wizardKeyboard());
                }
                case 10 -> {
                    String contents = text.lines().map(String::strip).filter(l -> !l.isEmpty())
                            .map(l -> l.replaceFirst("^(?:[-•*✓✔️]+|\\d+[.)])\\s*", "").strip()).filter(l -> !l.isEmpty())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    if (contents.isEmpty()) {
                        reply(chatId, "Set tarkibini yozing — har qatorda bittadan:", wizardKeyboard());
                        return true;
                    }
                    pending.put(chatId, new AwaitNewProduct(3, np.categoryId(), np.name(), np.description(), null, true, contents));
                    reply(chatId, "<b>4/5.</b> Set narxini yozing, masalan: <code>250000</code>", wizardKeyboard());
                }
                case 3, 31, 32 -> {
                    // 3 — narx (tortda: Butun), 31 — Yarim, 32 — Chorak (tortlarda; «-» — bu o'lcham yo'q)
                    boolean skip = np.step() != 3 && text.strip().equals("-");
                    Long price = skip ? null : parsePrice(text);
                    if (!skip && price == null) {
                        reply(chatId, "Narxni faqat raqam bilan yozing, masalan: <code>150000</code>"
                                + (np.step() != 3 ? "\nBu ölçam kerak bölmasa — <code>-</code>" : ""), wizardKeyboard());
                        return true;
                    }
                    boolean cake = !np.set() && isCake(np.categoryId());
                    List<MenuService.NewVariant> sizes = new ArrayList<>(np.sizes() == null ? List.of() : np.sizes());
                    if (price != null) {
                        String sizeName = !cake ? null : MenuService.CAKE_SIZES.get(np.step() == 3 ? 0 : np.step() == 31 ? 1 : 2);
                        sizes.add(new MenuService.NewVariant(sizeName, price));
                    }
                    AwaitNewProduct withSizes = new AwaitNewProduct(np.step(), np.categoryId(), np.name(), np.description(), sizes, np.set(), np.contents());
                    if (cake && np.step() == 3) {
                        pending.put(chatId, withSizes.next(31));
                        reply(chatId, "✅ Butun — " + Money.format(price) + "\n\n<b>Yarim</b> narxini yozing (bölmasa — <code>-</code>):", wizardKeyboard());
                    } else if (cake && np.step() == 31) {
                        pending.put(chatId, withSizes.next(32));
                        reply(chatId, (price == null ? "Yarim — yöq" : "✅ Yarim — " + Money.format(price))
                                + "\n\n<b>Çorak</b> narxini yozing (bölmasa — <code>-</code>):", wizardKeyboard());
                    } else {
                        pending.put(chatId, withSizes.next(4));
                        reply(chatId, (np.set() ? "<b>4/4.</b> Endi set" : "<b>4/4.</b> Endi mahsulot") + " <b>rasmini</b> yuboring 📷\n<i>Kvadrat rasm menyuda eng çiroyli çiqadi.</i>",
                                kb(row(inline("💾 Rasmsiz saqlaş", "np:save")), row(inline("✖️ Bekor qiliş", "cancel"))));
                    }
                }
                default -> reply(chatId, "Rasm kutilmoqda 📷 Rasmni yuboring yoki «💾 Rasmsiz saqlaş» ni bosing.",
                        kb(row(inline("💾 Rasmsiz saqlaş", "np:save")), row(inline("✖️ Bekor qiliş", "cancel"))));
            }
            return true;
        }
        if (state instanceof AwaitRename r) {
            pending.remove(chatId);
            try {
                String name = r.description() ? menu.setDescription(r.productId(), text) : menu.rename(r.productId(), text);
                reply(chatId, r.description() ? "✅ «" + esc(name) + "» tavsifi saqlandi" : "✅ Yangi nom: <b>" + esc(name) + "</b>",
                        kb(row(inline("‹ Mahsulotga qaytiş", "pe:card:" + r.productId()), inline("✅ Tayyor", "done"))));
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage(), kb(row(inline("‹ Mahsulotga qaytiş", "pe:card:" + r.productId()))));
            }
            return true;
        }
        if (state instanceof AwaitHours) {
            java.time.LocalTime[] h = uz.habibi.bakery.service.SettingsService.parseHours(text);
            if (h == null) {
                reply(chatId, "Tuşunmadim. Masalan şunday yozing: <code>11:00-23:00</code>", cancelKeyboard());
                return true;
            }
            try {
                settings.setHours(h[0], h[1]);
                pending.remove(chatId);
                reply(chatId, "✅ Yangi ish vaqti: <b>" + h[0] + "–" + h[1] + "</b>\nMijozlar faqat şu vaqtda buyurtma bera oladi.",
                        kb(row(inline("‹ Sozlamalar", "st:menu"), inline("✅ Tayyor", "done"))));
            } catch (BusinessException e) {
                reply(chatId, "⚠️ " + e.getMessage(), cancelKeyboard());
            }
            return true;
        }
        if (state instanceof AwaitCard c) {
            if (c.number() == null) {
                String number = uz.habibi.bakery.service.SettingsService.normalizeCard(text);
                if (number == null) {
                    reply(chatId, "Karta raqami 16 ta raqamdan iborat bölişi kerak, masalan: <code>8600 1234 5678 9012</code>", cancelKeyboard());
                    return true;
                }
                pending.put(chatId, new AwaitCard(number));
                reply(chatId, "💳 <code>" + number + "</code>\n\nEndi karta egasining ismini yozing (mijozlar köradi), masalan: "
                        + "<code>Xamidov Otabek</code>", cancelKeyboard());
                return true;
            }
            String holder = text.strip();
            if (holder.length() < 2 || holder.length() > 60) {
                reply(chatId, "Ism 2–60 belgi bölişi kerak. Qaytadan yozing:", cancelKeyboard());
                return true;
            }
            pending.remove(chatId);
            settings.setCard(c.number(), holder);
            reply(chatId, "✅ Tölov kartasi saqlandi:\n<code>" + c.number() + "</code>\n" + esc(holder)
                            + "\n\nEndi yangi buyurtmalarda mijozlarga şu karta körsatiladi.",
                    kb(row(inline("‹ Sozlamalar", "st:menu"), inline("✅ Tayyor", "done"))));
            return true;
        }
        if (state instanceof AwaitAdminPhone) {
            Optional<Long> id = customers.findByPhone(text);
            if (id.isEmpty()) {
                reply(chatId, "Bu raqam böyiça mijoz topilmadi.\n\nYordamçingiz avval botga kirib, <b>/start</b> bosib "
                        + "röyxatdan ötişi (raqamini yuboriş) kerak. Keyin raqamini qayta yozing:", cancelKeyboard());
                return true;
            }
            pending.remove(chatId);
            if (settings.isAdmin(id.get())) {
                reply(chatId, "Bu odam allaqaçon admin ✅", kb(row(inline("‹ Adminlar", "st:admins"))));
                return true;
            }
            settings.addAdmin(id.get());
            pending.remove(id.get());
            reply(id.get(), "👩‍🍳 Siz <b>Habibi Bakery</b> botiga <b>admin</b> qilib qöşildingiz.\n"
                    + "Yangi buyurtma va çeklar endi sizga ham keladi. Pastdagi tugmalardan foydalaning.", adminKeyboard());
            reply(chatId, "✅ " + adminLabel(id.get()) + " admin qilindi.", kb(row(inline("‹ Adminlar", "st:admins"), inline("✅ Tayyor", "done"))));
            return true;
        }
        if (state instanceof AwaitBroadcastConfirm) {
            reply(chatId, "Yuqoridagi xabarni yuboraymi? «✅ Ha, yuboriş» yoki «✖️ Bekor qiliş» ni bosing.", null);
            return true;
        }
        return false;
    }

    // ---- yangi bo'limlar tugmalari ----

    private void onAdminToolsCallback(CallbackQuery cq, Long chatId, Integer messageId, String[] p) throws TelegramApiException {
        String key = p[0] + ":" + p[1];
        Long id = p.length > 2 ? Long.parseLong(p[2]) : null;
        switch (key) {
            // 📋 Buyurtmalar
            case "ord:menu" -> { answer(cq, null); pending.remove(chatId); sendOrdersMenu(chatId, messageId); }
            case "ord:active", "ord:unconf", "ord:cancel", "ord:unchecked" -> {
                answer(cq, null);
                InlineKeyboardRow back = row(inline("‹ Buyurtmalar", "ord:menu"), inline("🔄 Yangilaş", key));
                List<OrderDetails> list;
                String title;
                String empty;
                switch (key) {
                    case "ord:active" -> {
                        list = orders.activeOrders();
                        title = "🟢 <b>Faol buyurtmalar (" + list.size() + ")</b>";
                        empty = "Hozirça faol buyurtma yöq ☕";
                    }
                    case "ord:cancel" -> {
                        list = orders.cancelledToday(props.zone());
                        title = "❌ <b>Bugun bekor qilinganlar (" + list.size() + ")</b>";
                        empty = "Bugun bekor qilingan buyurtma yöq ✅";
                    }
                    default -> {
                        list = orders.unconfirmed();
                        title = "⏳ <b>Tasdiqlanmagan buyurtmalar (" + list.size() + ")</b>\nQabul qilinmagan yoki çeki tekşirilmagan:";
                        empty = "Hamma buyurtma tasdiqlangan ✅";
                    }
                }
                if (list.isEmpty()) {
                    show(chatId, messageId, empty, kb(back));
                } else {
                    sendOrderList(chatId, messageId, title, list, back);
                }
            }
            case "ord:csearch" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitCustomerSearch());
                reply(chatId, "🔍 Mijozning <b>ismi</b>, <b>@username</b> yoki <b>töliq telefon raqamini</b> yozing "
                        + "(masalan <code>90 123 45 67</code>):", kb(row(inline("‹ Mijozlar röyxati", "cust:0"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "ord:search" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitSearch());
                reply(chatId, "🔍 Buyurtma <b>raqamini</b> (masalan <code>45</code>) yoki mijozning <b>telefon raqamini</b> yozing:",
                        kb(row(inline("‹ Buyurtmalar", "ord:menu"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            // 📦 Mahsulotlar
            case "pm:menu" -> { answer(cq, null); pending.remove(chatId); sendProductsMenu(chatId, messageId); }
            case "pm:new" -> { answer(cq, null); pending.remove(chatId); sendCategoryPicker(chatId, messageId, "new"); }
            case "pm:edit" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "edit"); }
            case "pm:price" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "price"); }
            case "pm:photo" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "photo"); }
            case "pm:view" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "view"); }
            case "pm:set" -> { answer(cq, null); sendSetPicker(chatId, messageId); }
            case "pm:soldout" -> { answer(cq, null); sendSoldOut(chatId, messageId); }
            case "pm:stop" -> { answer(cq, null); sendCategoryPicker(chatId, messageId, "stop"); }
            case "pm:excel" -> {
                answer(cq, "📥 Fayl tayyorlanmoqda...");
                sendProductsCsv(chatId);
            }
            case "pm:cats" -> { answer(cq, null); pending.remove(chatId); sendCategoriesList(chatId, messageId); }
            case "pc:card" -> { answer(cq, null); pending.remove(chatId); sendCategoryCard(chatId, messageId, id); }
            case "pc:up", "pc:down" -> {
                menu.moveCategory(id, key.equals("pc:up"));
                answer(cq, key.equals("pc:up") ? "⬆️" : "⬇️");
                sendCategoryCard(chatId, messageId, id);
            }
            case "pc:vis" -> {
                boolean visible = menu.toggleCategoryVisible(id);
                answer(cq, visible ? "👁 Mijozlarga körinadi" : "🙈 Yaşirildi");
                sendCategoryCard(chatId, messageId, id);
            }
            case "pc:ren" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitCategoryRename(id));
                show(chatId, messageId, "✏️ <b>" + esc(categoryName(id)) + "</b> — yangi nomini yozing:",
                        kb(row(inline("‹ Orqaga", "pc:card:" + id), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "pc:del" -> {
                answer(cq, null);
                long n = menu.allProducts().stream().filter(x -> x.getCategory().getId().equals(id)).count();
                if (n > 0) {
                    show(chatId, messageId, "🗑 <b>" + esc(categoryName(id)) + "</b> içida <b>" + n + " ta mahsulot</b> bor.\n\n"
                                    + "Avval ularni boşqa kategoriyaga köçiring (✏️ Tahrirlaş → mahsulot → 📂 Kategoriyasi) yoki öçiring.\n"
                                    + "<i>Vaqtinça kerak bölmasa — öçirmang, «🙈 Yaşiriş» ni bosing.</i>",
                            kb(row(inline("‹ Orqaga", "pc:card:" + id))));
                } else {
                    show(chatId, messageId, "🗑 <b>" + esc(categoryName(id)) + "</b> kategoriyasini öçirasizmi?",
                            kb(row(inline("🗑 Ha, öçiriş", "pc:delok:" + id), inline("✖️ Yöq", "pc:card:" + id))));
                }
            }
            case "pc:delok" -> {
                String name = menu.deleteCategory(id);
                answer(cq, "🗑 Öçirildi");
                show(chatId, messageId, "🗑 «" + esc(name) + "» kategoriyasi öçirildi.", kb(row(inline("‹ Kategoriyalar", "pm:cats"))));
            }
            case "pm:newcat" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitCategory(null));
                show(chatId, messageId, "➕ <b>Yangi kategoriya</b>\n\nNomini yozing, masalan: <code>Bayram tortlari</code>",
                        kb(row(inline("‹ Kategoriyalar", "pm:cats"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "pm:catemoji" -> {
                if (!(pending.get(chatId) instanceof AwaitCategory c) || c.name() == null) {
                    answer(cq, "Bu amal eskirgan");
                    return;
                }
                int idx = id.intValue();
                String emoji = idx >= 0 && idx < CATEGORY_EMOJI.size() ? CATEGORY_EMOJI.get(idx) : null;
                pending.remove(chatId);
                Long catId = menu.createCategory(c.name(), emoji);
                answer(cq, "✅ Kategoriya yaratildi");
                show(chatId, messageId, "✅ <b>" + (emoji == null ? "" : emoji + " ") + esc(c.name()) + "</b> kategoriyasi yaratildi.\n\n"
                                + "Endi unga mahsulot qöşing — şundan keyin menyuda körinadi.",
                        kb(row(inline("➕ Unga mahsulot qöşiş", "cat:new:" + catId), inline("🎁 Unga set qöşiş", "cat:newset:" + catId)),
                                row(inline("‹ Kategoriyalar", "pm:cats"), inline("✅ Tayyor", "done"))));
            }
            case "pm:newset" -> {
                answer(cq, null);
                pending.remove(chatId);
                List<Category> gift = menu.activeCategories().stream().filter(c -> "🎁".equals(c.getEmoji())).toList();
                if (gift.size() == 1) {
                    startNewSet(chatId, messageId, gift.getFirst().getId());
                } else {
                    sendCategoryPicker(chatId, messageId, "newset");
                }
            }
            // ✏️ Mahsulot kartochkasi
            case "pe:card" -> { answer(cq, null); pending.remove(chatId); sendProductCard(chatId, messageId, id); }
            case "pe:name", "pe:desc" -> {
                answer(cq, null);
                boolean desc = key.equals("pe:desc");
                pending.put(chatId, new AwaitRename(id, desc));
                show(chatId, messageId, desc ? "📝 Yangi tavsifni yozing (<code>-</code> — tavsifni öçiriş):" : "✏️ Yangi nomni yozing:",
                        kb(row(inline("‹ Orqaga", "pe:card:" + id), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "pe:price" -> { answer(cq, null); onProductPicked(chatId, messageId, "price", id); }
            case "pe:piece" -> {
                boolean on = menu.togglePerPiece(id);
                answer(cq, on ? "🔢 Endi narx yonida «/ dona» körinadi" : "🔢 «/ dona» olib taşlandi");
                sendProductCard(chatId, messageId, id);
            }
            case "pe:up", "pe:down" -> {
                menu.moveProductInCategory(id, key.equals("pe:up"));
                answer(cq, key.equals("pe:up") ? "⬆️ Yuqoriga" : "⬇️ Pastga");
                sendProductCard(chatId, messageId, id);
            }
            case "pe:sizes" -> { answer(cq, null); pending.remove(chatId); sendSizes(chatId, messageId, id); }
            case "pe:vadd" -> {
                answer(cq, null);
                String size = MenuService.CAKE_SIZES.get(Math.max(0, Math.min(2, p.length > 3 ? Integer.parseInt(p[3]) : 0)));
                pending.put(chatId, new AwaitAddSize(id, size));
                show(chatId, messageId, "➕ <b>" + size + "</b> narxini yozing, masalan: <code>160000</code>",
                        kb(row(inline("‹ Orqaga", "pe:sizes:" + id), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "pe:vdel" -> {
                Long productId = menu.allProducts().stream()
                        .filter(x -> x.getVariants().stream().anyMatch(v -> v.getId().equals(id)))
                        .map(Product::getId).findFirst().orElse(null);
                String name = menu.removeVariant(id);
                answer(cq, "✖️ «" + name + "» öçirildi");
                if (productId != null) {
                    sendSizes(chatId, messageId, productId);
                }
            }
            case "pe:move" -> {
                answer(cq, null);
                Long current = categoryOf(id);
                List<InlineKeyboardButton> cats = new ArrayList<>();
                for (Category c : menu.activeCategories()) {
                    if (!c.getId().equals(current)) {
                        cats.add(inline((c.getEmoji() == null ? "" : c.getEmoji() + " ") + c.getName(), "pe:mvto:" + id + ":" + c.getId()));
                    }
                }
                show(chatId, messageId, "📂 Hozirgi kategoriya: <b>" + esc(categoryName(current)) + "</b>\n\nQaysi kategoriyaga köçiramiz?",
                        grid(cats, 2, row(inline("‹ Mahsulotga qaytiş", "pe:card:" + id))));
            }
            case "pe:mvto" -> {
                String cat = menu.moveProduct(id, Long.parseLong(p[3]));
                answer(cq, "📂 «" + cat + "» ga köçirildi");
                sendProductCard(chatId, messageId, id);
            }
            case "pe:photo" -> { answer(cq, null); onProductPicked(chatId, messageId, "photo", id); }
            case "pe:del" -> {
                answer(cq, null);
                String name = menu.allProducts().stream().filter(x -> x.getId().equals(id)).map(Product::getName).findFirst().orElse("?");
                show(chatId, messageId, "🗑 <b>" + esc(name) + "</b> ni öçirasizmi?\n\n"
                                + "Menyudan butunlay yöqoladi. Eski buyurtmalardagi ma'lumot saqlanib qoladi.\n"
                                + "<i>Vaqtinça yöq bölsa — öçirmang, «⛔ Mahsulot tugadi» deb belgilang.</i>",
                        kb(row(inline("🗑 Ha, öçiriş", "pe:delok:" + id), inline("✖️ Yöq", "pe:card:" + id))));
            }
            case "pe:delok" -> {
                Long cat = categoryOf(id);
                String name = menu.archive(id);
                answer(cq, "🗑 Öçirildi");
                show(chatId, messageId, "🗑 «" + esc(name) + "» menyudan öçirildi.",
                        kb(row(inline("‹ " + esc(categoryName(cat)), "cat:edit:" + cat), inline("‹ Mahsulotlar menyusi", "pm:menu"))));
            }
            // ➕ Yangi mahsulot: rasmsiz saqlash
            case "np:save" -> {
                if (pending.get(chatId) instanceof AwaitNewProduct np && np.step() == 4) {
                    pending.remove(chatId);
                    answer(cq, null);
                    saveNewProduct(chatId, np, null);
                } else {
                    answer(cq, "Bu amal eskirgan");
                }
            }
            // ⚙️ Sozlamalar
            case "st:menu" -> { answer(cq, null); pending.remove(chatId); sendSettings(chatId, messageId); }
            case "st:pause" -> {
                settings.setPaused(true);
                answer(cq, "⏸ Buyurtmalar töxtatildi");
                sendSettings(chatId, messageId);
            }
            case "st:resume" -> {
                settings.setPaused(false);
                answer(cq, "▶️ Buyurtmalar qayta yoqildi");
                sendSettings(chatId, messageId);
            }
            case "st:hours" -> {
                answer(cq, null);
                var shop = settings.shop();
                pending.put(chatId, new AwaitHours());
                show(chatId, messageId, "🕐 Hozirgi ish vaqti: <b>" + shop.opens() + "–" + shop.closes() + "</b>\n\n"
                        + "Yangi vaqtni yozing, masalan: <code>10:00-22:00</code>", kb(row(inline("‹ Sozlamalar", "st:menu"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "st:card" -> {
                answer(cq, null);
                pending.put(chatId, new AwaitCard(null));
                show(chatId, messageId, "💳 Yangi karta raqamini yozing (16 ta raqam):", kb(row(inline("‹ Sozlamalar", "st:menu"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "st:banner" -> { answer(cq, null); sendBannerMenu(chatId, messageId); }
            case "st:admins" -> { answer(cq, null); pending.remove(chatId); sendAdmins(chatId, messageId); }
            // 👩‍🍳 Adminlar
            case "adm:add" -> {
                if (!settings.canManageAdmins(chatId)) {
                    answer(cq, "Faqat asosiy admin yoki super admin qöşa oladi");
                    return;
                }
                answer(cq, null);
                pending.put(chatId, new AwaitAdminPhone());
                show(chatId, messageId, "➕ <b>Admin qöşiş</b>\n\n1. Yordamçingiz botga kirib <b>/start</b> bosib, röyxatdan ötsin (raqamini yuborsin).\n"
                        + "2. Keyin uning telefon raqamini şu yerga yozing, masalan: <code>90 123 45 67</code>",
                        kb(row(inline("‹ Adminlar", "st:admins"), inline("✖️ Bekor qiliş", "cancel"))));
            }
            case "adm:rm" -> {
                boolean allowed = settings.isMainAdmin(chatId)
                        || (settings.isSuperAdmin(chatId) && !settings.isSuperAdmin(id) && !settings.isMainAdmin(id));
                if (!allowed) {
                    answer(cq, "Bu adminni faqat asosiy admin olib taşlay oladi");
                    return;
                }
                settings.removeAdmin(id);
                pending.remove(id);
                customerMode.remove(id);
                Lang lang = customers.language(id).orElse(Lang.UZ);
                reply(id, "ℹ️ Sizning adminlik huquqingiz olib taşlandi.", customerReplyKeyboard(lang));
                answer(cq, "✖️ Adminlikdan olindi");
                sendAdmins(chatId, messageId);
            }
            case "adm:sup1", "adm:sup0" -> {
                if (!settings.isMainAdmin(chatId)) {
                    answer(cq, "Faqat asosiy admin super admin tayinlay oladi");
                    return;
                }
                boolean on = key.equals("adm:sup1");
                settings.setSuperAdmin(id, on);
                reply(id, on ? "⭐️ Siz <b>super admin</b> qilindingiz: endi admin qöşa va oddiy adminlarni olib taşlay olasiz "
                        + "(⚙️ Sozlamalar → 👩‍🍳 Adminlar)." : "ℹ️ Sizning super adminlik huquqingiz olindi (oddiy admin bölib qoldingiz).", null);
                answer(cq, on ? "⭐️ Super admin qilindi" : "⬇️ Super adminlikdan olindi");
                sendAdmins(chatId, messageId);
            }
            // 📢 Xabar yuborish
            case "bc:ok" -> {
                if (pending.remove(chatId) instanceof AwaitBroadcastConfirm b) {
                    answer(cq, "📢 Yuborilmoqda…");
                    show(chatId, messageId, "📢 Xabar yuborilmoqda… Tugagaç hisobot yuboraman.", null);
                    sendBroadcast(chatId, b.fromChat(), b.messageId());
                } else {
                    answer(cq, "Bu amal eskirgan");
                }
            }
            default -> answer(cq, null);
        }
    }


    // ------------------------------------------------------------------ tugmalar (inline)

    private void onCallback(CallbackQuery cq) throws TelegramApiException {
        String data = cq.getData() == null ? "" : cq.getData();
        Long chatId = cq.getMessage().getChatId();
        Integer messageId = cq.getMessage().getMessageId();

        if (data.startsWith("lang:")) {
            onLanguageCallback(cq, chatId, messageId, data.substring(5));
            return;
        }
        if (onCustomerCallback(cq, chatId, messageId, data)) {
            return;
        }
        if (!isAdmin(chatId)) {
            answer(cq, null);
            return;
        }
        try {
            String[] p = data.split(":");
            switch (p[0]) {
                case "o" -> {
                    AdminAction action = AdminAction.valueOf(p[2]);
                    if (action == AdminAction.ON_WAY) {
                        // avval Yandex kuryerini kuzatish havolasi so'raladi
                        Long orderId = Long.parseLong(p[1]);
                        if (orders.details(orderId).status() != uz.habibi.bakery.domain.OrderStatus.PREPARING) {
                            answer(cq, "Bu buyurtma allaqaçon yölga çiqqan yoki holati boşqa");
                            return;
                        }
                        answer(cq, null);
                        pending.put(chatId, new AwaitTracking(orderId));
                        reply(chatId, "🚕 <b>#" + orderId + " yölga çiqdi</b>\n\n📍 Kuryerni kuzatiş havolasini yuboring:\n"
                                        + "<i>Yandex Go → şu buyurtma → «Ulaşiş» → havolani nusxalab, şu yerga yuboring.</i>",
                                kb(row(inline("⏭ Havolasiz davom etiş", "trk:skip:" + orderId)), row(inline("✖️ Bekor qiliş", "cancel"))));
                        return;
                    }
                    OrderDetails o = orders.apply(Long.parseLong(p[1]), action);
                    answer(cq, "Bajarildi: " + o.status().label());
                    if ((action == AdminAction.PAY_OK || action == AdminAction.PAY_NO)
                            && cq.getMessage() instanceof Message msg && msg.hasPhoto()) {
                        closeReceipt(chatId, messageId, msg.getCaption(), action == AdminAction.PAY_OK);
                    }
                }
                case "bnr" -> onBannerCallback(cq, chatId, messageId, p[1]);
                case "promo" -> onPromoCallback(cq, chatId, messageId, p);
                case "ord", "pm", "pe", "np", "st", "adm", "bc", "pc" -> onAdminToolsCallback(cq, chatId, messageId, p);
                case "rc" -> onReceiptCallback(cq, chatId, messageId, p);
                case "trk" -> {
                    Long orderId = Long.parseLong(p[2]);
                    if (pending.get(chatId) instanceof AwaitTracking t && t.orderId().equals(orderId)) {
                        pending.remove(chatId);
                    }
                    OrderDetails o = orders.dispatch(orderId, null);
                    answer(cq, "🚕 Yölga çiqdi");
                    show(chatId, messageId, "🚕 #" + o.id() + " — yölga çiqdi (havolasiz). Mijozga xabar bordi.", null);
                }
                case "remind" -> {
                    Long orderId = Long.parseLong(p[1]);
                    Long last = lastReminder.get(orderId);
                    if (last != null && System.currentTimeMillis() - last < 60_000) {
                        answer(cq, "⏳ Eslatma hozirgina yuborildi, birozdan keyin qayta urinib köring");
                        return;
                    }
                    OrderDetails o = orders.details(orderId);
                    if (o.paymentStatus() != uz.habibi.bakery.domain.PaymentStatus.AWAITING_RECEIPT) {
                        answer(cq, "Bu buyurtma uçun çek kutilmayapti");
                        return;
                    }
                    boolean sent = notifier.remindReceipt(o);
                    if (sent) {
                        lastReminder.put(orderId, System.currentTimeMillis());
                    }
                    answer(cq, sent ? "🔔 Mijozga eslatma yuborildi" : "⚠️ Yuborib bölmadi (mijoz botni bloklagan bölişi mumkin)");
                }
                case "cust" -> {
                    answer(cq, null);
                    sendCustomers(chatId, messageId, Integer.parseInt(p[1]));
                }
                case "cuo" -> {
                    answer(cq, null);
                    sendCustomerOrders(chatId, messageId, Long.parseLong(p[1]), Integer.parseInt(p[2]));
                }
                case "custd" -> {
                    answer(cq, null);
                    sendCustomerDetail(chatId, messageId, Long.parseLong(p[1]), Integer.parseInt(p[2]));
                }
                case "custblk" -> {
                    // tasdiqlash so'raladi
                    answer(cq, null);
                    long id = Long.parseLong(p[1]);
                    String name = customers.summary(id).map(CustomerSummary::displayName).orElse(String.valueOf(id));
                    show(chatId, messageId, "🚫 <b>" + esc(name) + "</b> ni bloklaysizmi?\n\n"
                                    + "Mijoz botdan va menyudan foydalana olmaydi, buyurtma bera olmaydi. "
                                    + "Unga quyidagi xabar boradi:\n\n<i>" + blockedText(Lang.UZ) + "</i>\n\n"
                                    + "Ma'lumotlari va buyurtmalari saqlanib qoladi, istalgan payt blokdan çiqarsa böladi.",
                            InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                                    inline("✅ Ha, bloklaş", "custblkok:" + id + ":" + p[2]),
                                    inline("✖️ Yöq", "custd:" + id + ":" + p[2]))).build());
                }
                case "custblkok" -> {
                    long id = Long.parseLong(p[1]);
                    if (isAdmin(id)) {
                        answer(cq, "Adminni bloklab bölmaydi");
                        return;
                    }
                    customers.setBlocked(id, true);
                    receiptTarget.remove(id);
                    pendingReceiptPhoto.remove(id);
                    lastBlockedNotice.put(id, System.currentTimeMillis());
                    Lang lang = customers.language(id).orElse(Lang.UZ);
                    reply(id, blockedText(lang),
                            org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder().removeKeyboard(true).build());
                    answer(cq, "🚫 Mijoz bloklandi");
                    sendCustomerDetail(chatId, messageId, id, Integer.parseInt(p[2]));
                }
                case "custdel" -> {
                    // tasdiqlash so'raladi
                    answer(cq, null);
                    long id = Long.parseLong(p[1]);
                    String name = customers.summary(id).map(CustomerSummary::displayName).orElse(String.valueOf(id));
                    long active = orders.myOrders(id).stream().filter(o -> !o.status().isFinal()).count();
                    show(chatId, messageId, "🗑 <b>" + esc(name) + "</b> ni öçirasizmi?\n\n"
                                    + "Öçadi: til, ism, raqam, şartlarga rozilik, manzillar, yoqtirganlari"
                                    + " (blok bölsa — u ham olinadi).\n"
                                    + "Saqlanadi: buyurtmalari, çeklari va statistika.\n\n"
                                    + "Mijoz botga qayta kirganda ro'yxatdan boşidan ötadi."
                                    + (active > 0 ? "\n\n⚠️ Unda <b>" + active + " ta faol buyurtma</b> bor — ular davom etadi." : ""),
                            InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(
                                    inline("✅ Ha, öçiriş", "custdelok:" + id + ":" + p[2]),
                                    inline("✖️ Yöq", "custd:" + id + ":" + p[2]))).build());
                }
                case "custdelok" -> {
                    long id = Long.parseLong(p[1]);
                    if (isAdmin(id)) {
                        answer(cq, "Adminni öçirib bölmaydi");
                        return;
                    }
                    Lang lang = customers.language(id).orElse(Lang.UZ);
                    customers.deleteProfile(id);
                    pending.remove(id);
                    receiptTarget.remove(id);
                    pendingReceiptPhoto.remove(id);
                    ratingComment.remove(id);
                    lastBlockedNotice.remove(id);
                    reply(id, I18n.t(lang, "bot.profile_deleted"),
                            org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder().removeKeyboard(true).build());
                    answer(cq, "🗑 Mijoz öçirildi");
                    sendCustomerDetail(chatId, messageId, id, Integer.parseInt(p[2]));
                }
                case "custunb" -> {
                    long id = Long.parseLong(p[1]);
                    customers.setBlocked(id, false);
                    lastBlockedNotice.remove(id);
                    reply(id, I18n.t(customers.language(id).orElse(Lang.UZ), "bot.unblocked"), null);
                    answer(cq, "🔓 Blokdan çiqarildi");
                    sendCustomerDetail(chatId, messageId, id, Integer.parseInt(p[2]));
                }
                case "custcsv" -> {
                    answer(cq, "📥 Fayl tayyorlanmoqda...");
                    sendCustomersCsv(chatId);
                }
                case "stx" -> {
                    answer(cq, null);
                    sendStatsPeriod(chatId, messageId, p[1], Integer.parseInt(p[2]));
                }
                case "rtg" -> {
                    if (p[1].equals("excel")) {
                        answer(cq, "📥 Fayl tayyorlanmoqda...");
                        sendRatingsCsv(chatId);
                    } else {
                        answer(cq, null);
                        sendRatingsView(chatId, messageId);
                    }
                }
                case "stxf" -> {
                    answer(cq, "📥 Fayl tayyorlanmoqda...");
                    sendStatsCsv(chatId, p[1], Integer.parseInt(p[2]));
                }
                case "stats" -> {
                    answer(cq, "Yangilandi");
                    sendStats(chatId, messageId);
                }
                case "done" -> {
                    pending.remove(chatId);
                    answer(cq, null);
                    show(chatId, messageId, "✅ Tayyor", null);
                }
                case "show" -> {
                    answer(cq, null);
                    notifier.showFull(orders.details(Long.parseLong(p[1])), chatId);
                }
                case "ps" -> {
                    boolean available = menu.toggleAvailability(Long.parseLong(p[2]));
                    answer(cq, available ? "✅ Sotuvga qaytdi" : "⛔ Tugagan deb belgilandi");
                    sendStopCategory(chatId, messageId, Long.parseLong(p[1]));
                }
                case "p" -> {
                    boolean available = menu.toggleAvailability(Long.parseLong(p[1]));
                    answer(cq, available ? "✅ Sotuvga qaytdi" : "⛔ Tugagan deb belgilandi");
                    client.execute(EditMessageReplyMarkup.builder()
                            .chatId(chatId).messageId(messageId).replyMarkup(stopListKeyboard()).build());
                }
                case "cat" -> {
                    answer(cq, null);
                    pending.remove(chatId);
                    if (p[1].equals("view")) {
                        sendCategoryProducts(chatId, messageId, Long.parseLong(p[2]));
                    } else if (p[1].equals("stop")) {
                        sendStopCategory(chatId, messageId, Long.parseLong(p[2]));
                    } else if (p[1].equals("newset")) {
                        startNewSet(chatId, messageId, Long.parseLong(p[2]));
                    } else if (p[1].equals("new")) {
                        pending.put(chatId, new AwaitNewProduct(1, Long.parseLong(p[2]), null, null, null, false, null));
                        show(chatId, messageId, "➕ <b>Yangi mahsulot</b> · " + esc(categoryName(Long.parseLong(p[2])))
                                + "\n\n<b>1/4.</b> Mahsulot nomini yozing, masalan: <code>Medovik</code>", wizardKeyboard());
                    } else {
                        sendProductPicker(chatId, messageId, p[1], Long.parseLong(p[2]));
                    }
                }
                case "pr" -> {
                    // «Tugagan mahsulotlar» dan sotuvga qaytarish
                    Long productId = Long.parseLong(p[1]);
                    boolean available = menu.allProducts().stream()
                            .filter(x -> x.getId().equals(productId)).findFirst().map(Product::isAvailable).orElse(true);
                    if (!available) {
                        menu.toggleAvailability(productId);
                    }
                    answer(cq, "✅ Sotuvga qaytdi");
                    sendSoldOut(chatId, messageId);
                }
                case "back" -> {
                    answer(cq, null);
                    pending.remove(chatId);
                    sendCategoryPicker(chatId, messageId, p[1]);
                }
                case "prod" -> {
                    answer(cq, null);
                    onProductPicked(chatId, messageId, p[1], Long.parseLong(p[2]));
                }
                case "var" -> {
                    answer(cq, null);
                    Long variantId = Long.parseLong(p[1]);
                    for (Product prod : menu.allProducts()) {
                        for (ProductVariant v : prod.getVariants()) {
                            if (v.getId().equals(variantId)) {
                                askPrice(chatId, messageId, v, prod);
                                return;
                            }
                        }
                    }
                    throw new BusinessException("Ölçam topilmadi");
                }
                case "cancel" -> {
                    pending.remove(chatId);
                    answer(cq, "Bekor qilindi");
                    show(chatId, messageId, "✖️ Bekor qilindi", null);
                }
                default -> answer(cq, null);
            }
        } catch (BusinessException e) {
            answer(cq, e.getMessage());
        }
    }

    private void closeReceipt(Long chatId, Integer messageId, String caption, boolean ok) {
        try {
            client.execute(EditMessageCaption.builder()
                    .chatId(chatId).messageId(messageId)
                    .caption(esc(caption) + "\n\n" + (ok ? "✅ <b>Tölov tasdiqlandi</b>" : "🚫 <b>Çek rad etildi</b>"))
                    .parseMode("HTML")
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(List.of()).build())
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Çek xabarini yangilab bölmadi: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ mijoz

    private String welcome(String firstName, Lang lang) {
        String name = firstName == null || firstName.isBlank() ? "" : ", " + esc(firstName);
        return I18n.t(lang, "bot.welcome", name);
    }

    /** Til tanlash (uchala tilda yozilgan). */
    private void sendLanguagePicker(Long chatId, Integer messageId) {
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(inline(Lang.UZ.title(), "lang:" + Lang.UZ.code())))
                .keyboardRow(new InlineKeyboardRow(inline(Lang.CYR.title(), "lang:" + Lang.CYR.code())))
                .keyboardRow(new InlineKeyboardRow(inline(Lang.RU.title(), "lang:" + Lang.RU.code())))
                .build();
        show(chatId, messageId, I18n.t(Lang.UZ, "lang.choose"), kb);
    }

    private void onLanguageCallback(CallbackQuery cq, Long chatId, Integer messageId, String code) {
        if (code.equals("menu")) {
            answer(cq, null);
            sendLanguagePicker(chatId, null);
            return;
        }
        Lang lang = Lang.fromCode(code);
        var from = cq.getFrom();
        customers.setLanguage(new TelegramUser(from.getId(), from.getFirstName(), from.getLastName(), from.getUserName()), lang);
        answer(cq, I18n.t(lang, "lang.saved"));
        if (customers.isOnboarded(from.getId())) {
            setChatMenuButton(chatId, lang);
        }
        show(chatId, messageId, I18n.t(lang, "lang.saved"), null);
        if (!isAdminView(chatId)) {
            continueOnboarding(chatId, from, lang); // ro'yxatdan o'tgan bo'lsa — yangi tildagi tugmalar bilan salomlashuv
        }
    }

    /** Til tanlangan: raqam bo'lmasa — so'raymiz, bo'lsa — salomlashuv va menyu. */
    /** Ro'yxatdan o'tish: til (tanlangan) → ism → Telegram orqali raqam → salomlashuv va menyu. */
    private void continueOnboarding(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, Lang lang) {
        if (customers.name(from.getId()).isEmpty()) {
            askName(chatId, from, lang);
        } else if (!customers.hasVerifiedPhone(from.getId())) {
            askPhone(chatId, lang);
        } else if (!customers.hasAcceptedTerms(from.getId())) {
            sendTerms(chatId, lang);
        } else {
            sendWelcome(chatId, from, lang);
        }
    }

    /** Buyurtma berish shartlari — «✅ Roziman» bosilmaguncha menyu ochilmaydi. */
    private void sendTerms(Long chatId, Lang lang) {
        reply(chatId, I18n.t(lang, "bot.terms_intro"),
                org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder().removeKeyboard(true).build());
        reply(chatId, I18n.t(lang, "bot.terms") + "\n\n" + I18n.t(lang, "bot.terms_footer"), InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(inline(I18n.t(lang, "bot.btn.terms_accept"), "terms:ok")))
                .build());
    }

    /** Salomlashuv (pastki doimiy tugmalar bilan) va alohida «Menyuni ochish» tugmasi. */
    private void sendWelcome(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, Lang lang) {
        setChatMenuButton(chatId, lang);
        reply(chatId, welcome(customers.name(from.getId()).orElse(from.getFirstName()), lang), customerReplyKeyboard(lang));
        if (props.bot().hasWebApp()) {
            reply(chatId, I18n.t(lang, "bot.open_menu_hint"), customerKeyboard(lang));
        }
    }

    /** Mijozning pastki doimiy tugmalari (admin panelga o'xshash). */
    private ReplyKeyboardMarkup customerReplyKeyboard(Lang lang) {
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(I18n.t(lang, "bot.kb.order")))
                .keyboardRow(new KeyboardRow(I18n.t(lang, "bot.kb.orders"), I18n.t(lang, "bot.kb.receipt")))
                .keyboardRow(new KeyboardRow(I18n.t(lang, "bot.kb.terms"), I18n.t(lang, "bot.kb.language"), I18n.t(lang, "bot.kb.contact")))
                .resizeKeyboard(true)
                .isPersistent(true)
                .inputFieldPlaceholder(I18n.t(lang, "bot.kb.placeholder"))
                .build();
    }

    /** Bosilgan pastki tugma (uchala tildagi yozuvi bilan solishtiriladi); tugma bo'lmasa null. */
    private static String customerButton(String text) {
        for (String key : List.of("bot.kb.order", "bot.kb.orders", "bot.kb.receipt", "bot.kb.receipt_old", "bot.kb.language", "bot.kb.contact", "bot.kb.terms")) {
            for (Lang l : Lang.values()) {
                if (text.equals(I18n.t(l, key))) {
                    // eski klaviaturadagi «📎 Çek yuboriş» ham ishlayveradi
                    return key.equals("bot.kb.receipt_old") ? "bot.kb.receipt" : key;
                }
            }
        }
        return null;
    }

    private void onCustomerButton(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, String key) {
        Lang lang = langOf(from);
        switch (key) {
            // Klaviatura tugmasidan ochilgan Mini App'ga Telegram foydalanuvchi ma'lumotini bermaydi,
            // shuning uchun inline «Menyuni ochish» tugmasi yuboriladi
            case "bot.kb.order" -> {
                if (props.bot().hasWebApp()) {
                    reply(chatId, I18n.t(lang, "bot.open_menu_hint"), InlineKeyboardMarkup.builder()
                            .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                                    .text(I18n.t(lang, "bot.btn.open_menu")).webApp(new WebAppInfo(webAppUrl(lang))).build()))
                            .build());
                } else {
                    reply(chatId, I18n.t(lang, "bot.open_menu_hint"), null);
                }
            }
            case "bot.kb.orders" -> sendMyOrders(chatId, from, null);
            case "bot.kb.language" -> sendLanguagePicker(chatId, null);
            case "bot.kb.terms" -> reply(chatId, I18n.t(lang, "bot.terms"), null);
            case "bot.kb.contact" -> {
                String phone = props.shop().phone() == null || props.shop().phone().isBlank() ? ""
                        : I18n.t(lang, "bot.contact_phone", esc(props.shop().phone()));
                reply(chatId, I18n.t(lang, "bot.contact_info", phone), null);
            }
            case "bot.kb.receipt" -> {
                List<OrderDetails> awaiting = orders.awaitingReceipt(from.getId());
                if (awaiting.isEmpty()) {
                    reply(chatId, I18n.t(lang, "bot.receipt_no_order"), customerKeyboard(lang));
                } else if (awaiting.size() == 1) {
                    receiptTarget.put(chatId, awaiting.getFirst().id());
                    reply(chatId, I18n.t(lang, "bot.send_new_receipt", awaiting.getFirst().id()), null);
                } else {
                    List<InlineKeyboardButton> buttons = awaiting.stream()
                            .map(o -> inline("#" + o.id() + " · " + Money.format(o.itemsTotal(), lang), "rcpt:" + o.id()))
                            .toList();
                    reply(chatId, I18n.t(lang, "bot.choose_order_for_receipt"), grid(buttons, 1));
                }
            }
            default -> {
            }
        }
    }

    /** Ism so'raladi; Telegram'dagi ism tez tanlash uchun tugma qilib beriladi. */
    private void askName(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, Lang lang) {
        ReplyKeyboard kb = null;
        String tgName = from.getFirstName() == null ? "" : from.getFirstName().trim();
        if (validName(tgName)) {
            kb = ReplyKeyboardMarkup.builder()
                    .keyboardRow(new KeyboardRow(tgName))
                    .resizeKeyboard(true).oneTimeKeyboard(true)
                    .build();
        }
        reply(chatId, I18n.t(lang, "bot.ask_name"), kb);
    }

    private static boolean validName(String name) {
        return name != null && name.length() >= 2 && name.length() <= 50 && !name.startsWith("/")
                && name.codePoints().anyMatch(Character::isLetter);
    }

    /** Ro'yxatdan o'tmagan mijoz matn yozdi: ism kutilayotgan bo'lsa saqlaymiz. true — xabar ishlatildi. */
    private boolean handleOnboardingText(Long chatId, org.telegram.telegrambots.meta.api.objects.User from, String text) {
        Optional<Lang> saved = customers.language(from.getId());
        if (saved.isEmpty()) {
            sendLanguagePicker(chatId, null);
            return true;
        }
        Lang lang = saved.get();
        if (customers.name(from.getId()).isEmpty()) {
            if (!validName(text)) {
                reply(chatId, I18n.t(lang, "bot.name_invalid"), null);
                return true;
            }
            customers.setName(new TelegramUser(from.getId(), from.getFirstName(), from.getLastName(), from.getUserName()), text);
            reply(chatId, I18n.t(lang, "bot.name_saved", esc(text.trim())), null);
            continueOnboarding(chatId, from, lang);
            return true;
        }
        if (!customers.hasVerifiedPhone(from.getId())) {
            askPhone(chatId, lang);
            return true;
        }
        if (!customers.hasAcceptedTerms(from.getId())) {
            reply(chatId, I18n.t(lang, "bot.terms_pending"), null);
            return true;
        }
        return false;
    }

    /** «📱 Raqamni yuborish» tugmasi — Telegram foydalanuvchining o'z raqamini yuboradi. */
    private void askPhone(Long chatId, Lang lang) {
        ReplyKeyboardMarkup kb = ReplyKeyboardMarkup.builder()
                .keyboardRow(new KeyboardRow(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
                        .builder().text(I18n.t(lang, "bot.btn.share_phone")).requestContact(true).build()))
                .resizeKeyboard(true)
                .oneTimeKeyboard(true)
                .build();
        reply(chatId, I18n.t(lang, "bot.ask_phone"), kb);
    }

    private void onContact(Message m) {
        Long chatId = m.getChatId();
        var from = m.getFrom();
        Lang lang = langOf(from);
        var contact = m.getContact();
        // Faqat o'z raqami qabul qilinadi (birovning kontaktini yuborish mumkin emas)
        if (contact.getUserId() == null || !contact.getUserId().equals(from.getId())) {
            askPhoneAgain(chatId, lang);
            return;
        }
        TelegramUser user = new TelegramUser(from.getId(), from.getFirstName(), from.getLastName(), from.getUserName());
        customers.setPhone(user, contact.getPhoneNumber());
        String phone = customers.phone(from.getId()).orElse(contact.getPhoneNumber());
        reply(chatId, I18n.t(lang, "bot.phone_saved", esc(phone)),
                org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove.builder().removeKeyboard(true).build());
        if (isAdminView(chatId)) {
            reply(chatId, "👩‍🍳 Admin panel", adminKeyboard());
        } else {
            continueOnboarding(chatId, from, lang);
        }
    }

    private void askPhoneAgain(Long chatId, Lang lang) {
        reply(chatId, I18n.t(lang, "bot.phone_not_own"), null);
        askPhone(chatId, lang);
    }

    /**
     * Har bir mijoz chatidagi «Menyu» tugmasini joriy manzilga yangilaydi.
     * Tunnel manzili har ishga tushirishda o'zgaradi — aks holda eski (o'lik) manzil ochilib qoladi.
     */
    private void refreshChatMenuButtons() {
        Thread.ofVirtual().start(() -> {
            int ok = 0;
            for (var e : customers.languages().entrySet()) {
                if (!customers.isOnboarded(e.getKey())) {
                    clearChatMenuButton(e.getKey());
                } else if (setChatMenuButton(e.getKey(), e.getValue())) {
                    ok++;
                }
            }
            log.info("Menyu tugmalari yangilandi: {} ta chat", ok);
        });
    }

    /** Ro'yxatdan to'liq o'tmagan chatda «Menyu» tugmasi bo'lmasin (shartlar tasdiqlanmagan). */
    private void clearChatMenuButton(Long chatId) {
        try {
            client.execute(SetChatMenuButton.builder().chatId(chatId)
                    .menuButton(org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonDefault.builder().build()).build());
        } catch (TelegramApiException e) {
            log.debug("Menyu tugmasini olib tashlab bölmadi (çat {}): {}", chatId, e.getMessage());
        }
    }

    /** Shu chat uchun pastdagi «Menyu» tugmasi: tanlangan tilda va Mini App ham shu tilda ochiladi. */
    private boolean setChatMenuButton(Long chatId, Lang lang) {
        if (!props.bot().hasWebApp()) {
            return false;
        }
        try {
            client.execute(SetChatMenuButton.builder()
                    .chatId(chatId)
                    .menuButton(MenuButtonWebApp.builder().text(I18n.t(lang, "bot.menu_button"))
                            .webAppInfo(new WebAppInfo(webAppUrl(lang))).build())
                    .build());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Menyu tugmasini örnatib bölmadi (çat {}): {}", chatId, e.getMessage());
            return false;
        }
    }

    private String webAppUrl(Lang lang) {
        String base = props.bot().webAppUrl();
        return base + (base.contains("?") ? "&" : "?") + "lang=" + lang.code();
    }

    /** Mijoz tili: saqlangan bo'lsa o'sha, bo'lmasa Telegram tilidan taxmin. */
    private Lang langOf(org.telegram.telegrambots.meta.api.objects.User user) {
        if (user == null) {
            return Lang.UZ;
        }
        return customers.language(user.getId()).orElseGet(() -> Lang.fromTelegram(user.getLanguageCode()));
    }

    private InlineKeyboardMarkup customerKeyboard(Lang lang) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        if (props.bot().hasWebApp()) {
            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text(I18n.t(lang, "bot.btn.open_menu"))
                    .webApp(new WebAppInfo(webAppUrl(lang)))
                    .build()));
        }
        rows.add(new InlineKeyboardRow(inline(I18n.t(lang, "bot.btn.my_orders"), "myorders"),
                inline(I18n.t(lang, "bot.btn.language"), "lang:menu")));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    // ------------------------------------------------------------------ yordamchi

    /** Admin paneli ko'rsatiladimi (admin «/mijoz» sinov rejimida bo'lmasa). */
    private boolean isAdminView(Long chatId) {
        return isAdmin(chatId) && !customerMode.contains(chatId);
    }

    private boolean isAdmin(Long chatId) {
        return chatId != null && settings.isAdmin(chatId);
    }

    private static InlineKeyboardButton inline(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private static InlineKeyboardMarkup grid(List<InlineKeyboardButton> buttons, int perRow, InlineKeyboardRow... extra) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += perRow) {
            rows.add(new InlineKeyboardRow(buttons.subList(i, Math.min(i + perRow, buttons.size()))));
        }
        rows.addAll(List.of(extra));
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private static String largest(List<PhotoSize> photos) {
        return photos.stream().max(Comparator.comparing(PhotoSize::getFileSize, Comparator.nullsFirst(Integer::compare)))
                .orElse(photos.getLast()).getFileId();
    }

    /** Inline menyuni joyida yangilaydi (messageId bo'lsa) yoki yangi xabar yuboradi. */
    private void show(Long chatId, Integer messageId, String html, InlineKeyboardMarkup keyboard) {
        if (messageId == null) {
            reply(chatId, html, keyboard);
            return;
        }
        try {
            client.execute(EditMessageText.builder().chatId(chatId).messageId(messageId)
                    .text(html).parseMode("HTML").replyMarkup(keyboard).build());
        } catch (TelegramApiException e) {
            if (!String.valueOf(e.getMessage()).contains("message is not modified")) {
                reply(chatId, html, keyboard);
            }
        }
    }

    private void reply(Long chatId, String html, ReplyKeyboard keyboard) {
        try {
            client.execute(SendMessage.builder().chatId(chatId).text(html).parseMode("HTML").replyMarkup(keyboard).build());
        } catch (TelegramApiException e) {
            log.warn("Javob yuborilmadi (çat {}): {}", chatId, e.getMessage());
        }
    }

    /** Oyna ko'rinishidagi javob (foydalanuvchi «OK» bosguncha turadi). */
    private void answerAlert(CallbackQuery cq, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).text(text).showAlert(true).build());
        } catch (TelegramApiException e) {
            log.debug("Callback javobi yuborilmadi: {}", e.getMessage());
        }
    }

    private void answer(CallbackQuery cq, String text) {
        try {
            client.execute(AnswerCallbackQuery.builder().callbackQueryId(cq.getId()).text(text).build());
        } catch (TelegramApiException e) {
            log.debug("Callback javobi yuborilmadi: {}", e.getMessage());
        }
    }
}
