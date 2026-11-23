package uz.habibi.bakery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.habibi.bakery.domain.*;

@SpringBootTest(properties = {"app.bot.token=", "app.dev-mode=false"})
@ActiveProfiles("test")
class OrderServiceTest {

    @Autowired
    OrderService orders;
    @Autowired
    MenuService menu;
    @Autowired
    CustomerService customers;
    @Autowired
    LikeService likes;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    SettingsService settings;
    @Autowired
    RatingService ratings;
    @Autowired
    AddressService addresses;

    /** 5 daqiqalik bekor qilish vaqti o'tib ketganini taqlid qiladi. */
    private void sixMinutesPassed(Long orderId) {
        jdbc.update("update orders set created_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(360)), orderId);
    }

    private boolean visibleToAdmin(Long orderId) {
        return orders.activeOrders().stream().anyMatch(o -> o.id().equals(orderId));
    }

    private static final TelegramUser USER = new TelegramUser(555L, "Ali", null, "ali");
    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Tashkent")).plusDays(1);

    private CreateOrderRequest request(DeliveryType type, PaymentMethod pay, CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(List.of(items), "Ali", "+998 90 123 45 67", type,
                type == DeliveryType.DELIVERY ? "Chilonzor 9" : null,
                type == DeliveryType.DELIVERY ? 41.3111 : null, type == DeliveryType.DELIVERY ? 69.2797 : null, TOMORROW, "13:00–15:00", pay, null, null, null);
    }

    private long variantId(String product, String variant) {
        return menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(p -> p.name().equals(product)).findFirst().orElseThrow()
                .variants().stream().filter(v -> variant == null || variant.equals(v.name())).findFirst().orElseThrow().id();
    }

    /**
     * V28 dan keyin bazada mahsulot yo'q — testlar uchun namunaviy mahsulotlar (bir marta) yaratiladi.
     * «Yangi» belgisi ularni chalg'itmasligi uchun created_at bo'sh qoldiriladi.
     */
    @org.junit.jupiter.api.BeforeEach
    void sampleProducts() {
        boolean exists = menu.allProducts().stream().anyMatch(p -> p.getName().equals("Chocoboom"));
        if (exists) {
            return;
        }
        java.util.Map<String, Long> cat = new java.util.HashMap<>();
        menu.activeCategories().forEach(c -> cat.put(c.getName(), c.getId()));
        // yangi mahsulot kategoriya boshiga tushadi — shuning uchun teskari tartibda yaratiladi
        sample(cat.get("Desertlar"), "Snikers torti", null, "Butun - 450000\nYarim - 230000\nÇorak - 120000");
        Long dubai = sample(cat.get("Desertlar"), "Dubai torti", "Торт «Дубай»", "Butun - 450000\nYarim - 230000\nÇorak - 120000");
        jdbc.update("update product_variant set name_ru = case name when 'Butun' then 'Целый' when 'Yarim' then 'Половина' else 'Четверть' end where product_id = ?", dubai);
        sample(cat.get("Setlar"), "Bananli trayfel", null, "80000");
        sample(cat.get("Setlar"), "Qulupnayli trayfel", null, "85000");
        sample(cat.get("Sovğa setlari"), "Damskiy palçik", null, "Katta - 240000\nÖrtaça - 160000\nKiçik - 40000");
        sample(cat.get("Sovğa setlari"), "Olçali desert", null, "65000");
        sample(cat.get("Sovğa setlari"), "Malinali desert", null, "60000");
        sample(cat.get("Sovğa setlari"), "Chocoboom", null, "10000");
        jdbc.update("update product set created_at = null");
    }

    private Long sample(Long categoryId, String name, String nameRu, String prices) {
        Long id = menu.createProduct(categoryId, name, null, MenuService.parseSizes(prices), null);
        if (nameRu != null) {
            jdbc.update("update product set name_ru = ? where id = ?", nameRu, id);
        }
        return id;
    }

    @Test
    void cleanStartKeepsOnlyRealCategories() {
        assertThat(menu.activeCategories()).extracting(Category::getName)
                .startsWith("Desertlar", "Setlar", "Sovğa setlari")
                .doesNotContain("Trayfellar", "Damskiy palçik", "Şokoladli şirinliklar")
                .doesNotContain("Maxsus tortlar");
        var dubai = menu.menu().categories().stream().flatMap(c -> c.products().stream()).filter(p -> p.name().equals("Dubai torti")).findFirst().orElseThrow();
        assertThat(dubai.variants()).extracting(MenuView.VariantView::price).containsExactly(450000L, 230000L, 120000L);
    }

    @Test
    void totalIsCalculatedOnServerAndDuplicatesMerged() {
        long half = variantId("Dubai torti", "Yarim");
        long triffle = variantId("Qulupnayli trayfel", null);
        OrderDetails o = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER,
                new CreateOrderRequest.Item(half, 1), new CreateOrderRequest.Item(triffle, 1), new CreateOrderRequest.Item(triffle, 1)));

        assertThat(o.itemsTotal()).isEqualTo(230000 + 2 * 85000);
        assertThat(o.items()).hasSize(2);
        assertThat(o.paymentStatus()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
        assertThat(o.status()).isEqualTo(OrderStatus.NEW);
    }

    @Test
    void deliveryRequiresAddressAndValidSlot() {
        long v = variantId("Chocoboom", null);
        var noAddress = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.DELIVERY, " ", null, null, TOMORROW, "13:00–15:00", PaymentMethod.CASH, null, null, null);
        assertThatThrownBy(() -> orders.create(USER, noAddress)).hasMessageContaining("xaritadan");

        // Faqat ko'cha nomi / izoh bilan (xaritasiz) qabul qilinmaydi
        var onlyText = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.DELIVERY, "Chilonzor 9", null, null, null, null, PaymentMethod.CARD_TRANSFER, "Eşik oldida", null, null);
        assertThatThrownBy(() -> orders.create(USER, onlyText)).hasMessageContaining("xaritadan");

        var badSlot = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.PICKUP, null, null, null, TOMORROW, "03:00–04:00", PaymentMethod.CASH, null, null, null);
        assertThatThrownBy(() -> orders.create(USER, badSlot)).hasMessageContaining("Vaqt");
    }

    @Test
    void deliveryWithMapPointOnlyIsAccepted() {
        long v = variantId("Chocoboom", null);
        var req = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.DELIVERY, "", 41.3111, 69.2797, TOMORROW, "13:00–15:00", PaymentMethod.CASH, null, null, null);
        OrderDetails o = orders.create(USER, req);
        assertThat(o.hasLocation()).isTrue();
        assertThat(o.address()).isEqualTo("Xaritada belgilangan joy");
        assertThat(o.yandexMapUrl()).contains("pt=69.2797,41.3111");
        assertThat(o.googleMapUrl()).contains("q=41.3111,69.2797");
    }

    @Test
    void pickupIgnoresLocation() {
        long v = variantId("Chocoboom", null);
        var req = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.PICKUP, "Chilonzor", 41.3, 69.2, TOMORROW, "13:00–15:00", PaymentMethod.CASH, null, null, null);
        OrderDetails o = orders.create(USER, req);
        assertThat(o.hasLocation()).isFalse();
        assertThat(o.address()).isNull();
    }

    @Test
    void soldOutProductCannotBeOrdered() {
        long v = variantId("Olçali desert", null);
        Long productId = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(p -> p.name().equals("Olçali desert")).findFirst().orElseThrow().id();
        menu.toggleAvailability(productId);
        try {
            assertThatThrownBy(() -> orders.create(USER, request(DeliveryType.PICKUP, PaymentMethod.CASH,
                    new CreateOrderRequest.Item(v, 1)))).hasMessageContaining("tugagan");
        } finally {
            menu.toggleAvailability(productId);
        }
    }

    @Test
    void pickupOrderFullLifecycleWithReceipt() {
        long v = variantId("Damskiy palçik", "Kiçik");
        OrderDetails o = orders.create(USER, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER,
                new CreateOrderRequest.Item(v, 3)));

        assertThatThrownBy(() -> orders.apply(o.id(), AdminAction.ON_WAY)).isInstanceOf(BusinessException.class);

        assertThat(orders.apply(o.id(), AdminAction.ACCEPT).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.attachReceipt(USER.id(), "file-1").paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        assertThat(orders.apply(o.id(), AdminAction.PAY_NO).paymentStatus()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
        orders.attachReceipt(USER.id(), "file-2");
        assertThat(orders.apply(o.id(), AdminAction.PAY_OK).paymentStatus()).isEqualTo(PaymentStatus.PAID);

        assertThat(orders.apply(o.id(), AdminAction.PREPARING).status()).isEqualTo(OrderStatus.PREPARING);
        assertThatThrownBy(() -> orders.apply(o.id(), AdminAction.ON_WAY)).isInstanceOf(BusinessException.class);
        assertThat(orders.apply(o.id(), AdminAction.READY).status()).isEqualTo(OrderStatus.READY);
        assertThat(orders.apply(o.id(), AdminAction.DONE).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThatThrownBy(() -> orders.apply(o.id(), AdminAction.REJECT)).isInstanceOf(BusinessException.class);
    }

    @Test
    void cashOrderBecomesPaidOnDelivery() {
        long v = variantId("Bananli trayfel", null);
        OrderDetails o = orders.create(new TelegramUser(556L, "Vali", null, null),
                request(DeliveryType.DELIVERY, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        assertThat(o.paymentStatus()).isEqualTo(PaymentStatus.PAY_ON_DELIVERY);
        orders.apply(o.id(), AdminAction.ACCEPT);
        orders.apply(o.id(), AdminAction.PREPARING);
        orders.apply(o.id(), AdminAction.ON_WAY);
        OrderDetails done = orders.apply(o.id(), AdminAction.DONE);
        assertThat(done.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThatThrownBy(() -> orders.attachReceipt(556L, "x")).isInstanceOf(BusinessException.class);
    }

    @Test
    void orderReachesAdminImmediately() {
        long v = variantId("Chocoboom", null);
        OrderDetails card = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        assertThat(card.adminNotified()).isTrue();
        assertThat(visibleToAdmin(card.id())).isTrue();
        assertThat(card.customerCanCancel(java.time.Instant.now())).isFalse();
        // chek keyin keladi — buyurtma adminda qoladi, chek tekshirilmaganlar ro'yxatiga tushadi
        OrderDetails r = orders.attachReceiptToOrder(USER.id(), card.id(), "receipt-file");
        assertThat(r.paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        assertThat(orders.uncheckedReceipts()).extracting(OrderDetails::id).contains(card.id());

        OrderDetails cash = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        assertThat(visibleToAdmin(cash.id())).isTrue();
        jdbc.update("update orders set status = 'CANCELLED' where id in (?, ?)", card.id(), cash.id());
    }

    @Test
    void unpaidOrderIsCancelledAfterThirtyMinutes() {
        long v = variantId("Chocoboom", null);
        OrderDetails unpaid = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        OrderDetails paid = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(USER.id(), paid.id(), "receipt");
        java.sql.Timestamp old = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(29)));
        jdbc.update("update orders set created_at = ? where id in (?, ?)", old, unpaid.id(), paid.id());
        orders.expireUnpaid();
        assertThat(statusOf(unpaid.id())).isNotEqualTo("CANCELLED");   // 29 daqiqa — hali kutamiz
        old = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(31)));
        jdbc.update("update orders set created_at = ? where id in (?, ?)", old, unpaid.id(), paid.id());
        OrderDetails cash = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        OrderDetails accepted = orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.apply(accepted.id(), AdminAction.ACCEPT);
        jdbc.update("update orders set created_at = ? where id in (?, ?)", old, cash.id(), accepted.id());
        orders.expireUnpaid();
        assertThat(statusOf(unpaid.id())).isEqualTo("CANCELLED");
        assertThat(statusOf(cash.id())).isNotEqualTo("CANCELLED");      // naqd — chek kutilmaydi
        assertThat(statusOf(accepted.id())).isNotEqualTo("CANCELLED");  // admin qabul qilgan — admin hal qiladi
        jdbc.update("update orders set status = 'CANCELLED' where id in (?, ?)", cash.id(), accepted.id());
        assertThat(statusOf(paid.id())).isNotEqualTo("CANCELLED");     // chek kelgani tegilmaydi
        assertThatThrownBy(() -> orders.attachReceiptToOrder(USER.id(), unpaid.id(), "late")).isNotNull();
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", paid.id());   // boshqa testlarga xalaqit bermasin
    }

    private String statusOf(Long id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    @Test
    void adminCanAddRenameAndDeleteProduct() {
        Long cat = menu.activeCategories().getFirst().getId();
        Long id = menu.createProduct(cat, "Sinov tort", "Mazali", MenuService.parseSizes("Butun - 300000\nYarim - 160 000"), null);
        var p = menu.allProducts().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertThat(p.getVariants()).extracting(ProductVariant::getPrice).containsExactly(300000L, 160000L);
        assertThat(menu.menu().categories().stream().flatMap(c -> c.products().stream()).anyMatch(x -> x.id().equals(id))).isTrue();
        menu.rename(id, "Yangi nom");
        menu.setDescription(id, "-");
        menu.archive(id);
        assertThat(menu.allProducts()).noneMatch(x -> x.getId().equals(id));
        assertThat(menu.menu().categories().stream().flatMap(c -> c.products().stream()).anyMatch(x -> x.id().equals(id))).isFalse();
        // o'chirilgan mahsulotni buyurtma qilib bo'lmaydi
        long v = jdbc.queryForObject("select id from product_variant where product_id = ? order by sort_order limit 1", Long.class, id);
        assertThatThrownBy(() -> orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void parsesPricesAndHoursAndCards() {
        assertThat(MenuService.parseSizes("150 000 söm")).containsExactly(new MenuService.NewVariant(null, 150000));
        assertThatThrownBy(() -> MenuService.parseSizes("300000\n160000")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MenuService.parseSizes("arzon")).isInstanceOf(BusinessException.class);
        assertThat(SettingsService.parseHours("10-22")).containsExactly(java.time.LocalTime.of(10, 0), java.time.LocalTime.of(22, 0));
        assertThat(SettingsService.parseHours("10:30 – 23:00")).containsExactly(java.time.LocalTime.of(10, 30), java.time.LocalTime.of(23, 0));
        assertThat(SettingsService.parseHours("kechqurun")).isNull();
        assertThat(SettingsService.normalizeCard("8600123412341234")).isEqualTo("8600 1234 1234 1234");
        assertThat(SettingsService.normalizeCard("8600 1234")).isNull();
    }

    @Test
    void settingsOverrideConfigAndManageAdmins() {
        settings.setCard("8600 0000 0000 0001", "Test Egasi");
        assertThat(settings.shop().cardNumber()).isEqualTo("8600 0000 0000 0001");
        settings.setHours(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(21, 0));
        assertThat(settings.shop().opens()).isEqualTo(java.time.LocalTime.of(9, 0));
        assertThatThrownBy(() -> settings.setHours(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(9, 0)))
                .isInstanceOf(BusinessException.class);
        settings.setPaused(true);
        assertThat(settings.isPaused()).isTrue();
        settings.setPaused(false);
        settings.addAdmin(777L);
        assertThat(settings.isAdmin(777L)).isTrue();
        assertThat(settings.adminChats()).contains(777L);
        settings.removeAdmin(777L);
        assertThat(settings.isAdmin(777L)).isFalse();
        settings.setHours(java.time.LocalTime.of(11, 0), java.time.LocalTime.of(23, 0));
    }

    @Test
    void adminSearchFindsByIdAndPhoneAndListsUncheckedReceipts() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(561L, "Qidir", null, null);
        var req = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Qidir", "+998 93 777 11 22",
                DeliveryType.DELIVERY, "Chilonzor", 41.3, 69.2, null, null, PaymentMethod.CARD_TRANSFER, null, null, null);
        OrderDetails o = orders.create(u, req);
        orders.attachReceiptToOrder(u.id(), o.id(), "chek");
        assertThat(orders.search("#" + o.id())).extracting(OrderDetails::id).containsExactly(o.id());
        assertThat(orders.search("93 777 11 22")).extracting(OrderDetails::id).contains(o.id());
        assertThat(orders.uncheckedReceipts()).extracting(OrderDetails::id).contains(o.id());
        orders.apply(o.id(), AdminAction.PAY_OK);
        assertThat(orders.uncheckedReceipts()).extracting(OrderDetails::id).doesNotContain(o.id());
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", o.id());   // statistika testiga xalaqit bermasin
    }

    @Test
    void adminCanSearchCustomers() {
        TelegramUser u = new TelegramUser(562L, "Telegram", null, "gulnoza_uz");
        customers.setName(u, "Gulnoza Oʻrinova");
        customers.setPhone(u, "+998 97 555 43 21");
        assertThat(customers.search("gulnoza")).extracting(CustomerSummary::telegramId).contains(562L);
        assertThat(customers.search("o'rinova")).extracting(CustomerSummary::telegramId).contains(562L);
        assertThat(customers.search("@gulnoza_uz")).extracting(CustomerSummary::telegramId).contains(562L);
        assertThat(customers.search("97 555 43 21")).extracting(CustomerSummary::telegramId).contains(562L);
        assertThat(customers.search("+998975554321")).extracting(CustomerSummary::telegramId).contains(562L);
        assertThat(customers.search("4321")).isEmpty();          // raqamning qismi bilan topilmaydi
        assertThat(customers.search("97 555")).isEmpty();
        assertThat(customers.search("zzzz")).isEmpty();
    }

    @Test
    void adminSeesUnconfirmedAndTodaysCancelledOrders() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(563L, "Bolim", null, null);
        OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(u.id(), o.id(), "chek");
        sixMinutesPassed(o.id());
        orders.releaseDue();
        assertThat(orders.unconfirmed()).extracting(OrderDetails::id).contains(o.id());
        orders.apply(o.id(), AdminAction.REJECT);
        assertThat(orders.unconfirmed()).extracting(OrderDetails::id).doesNotContain(o.id());
        assertThat(orders.cancelledToday(java.time.ZoneId.of("Asia/Tashkent"))).extracting(OrderDetails::id).contains(o.id());
    }

    @Test
    void receiptsSectionListsTodaysAndUncheckedReceipts() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(564L, "Chek", null, null);
        OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(u.id(), o.id(), "chek-file");
        sixMinutesPassed(o.id());
        orders.releaseDue();
        orders.saveReceiptFileId(o.id(), "chek-file");   // bot chekni adminga yuborganda saqlaydi
        var zone = java.time.ZoneId.of("Asia/Tashkent");
        assertThat(orders.receiptsToday(zone)).extracting(OrderDetails::id).contains(o.id());
        assertThat(orders.uncheckedReceiptPhotos()).extracting(OrderDetails::id).contains(o.id());
        orders.apply(o.id(), AdminAction.PAY_OK);
        assertThat(orders.uncheckedReceiptPhotos()).extracting(OrderDetails::id).doesNotContain(o.id());
        assertThat(orders.receiptsToday(zone)).filteredOn(x -> x.id().equals(o.id()))
                .extracting(OrderDetails::paymentStatus).containsExactly(PaymentStatus.PAID);
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", o.id());
    }

    @Test
    void dispatchSavesYandexTrackingLink() {
        assertThat(OrderService.extractTrackingUrl("Следите за курьером: https://go.yandex/route/abc123?lang=ru."))
                .isEqualTo("https://go.yandex/route/abc123?lang=ru");
        assertThat(OrderService.extractTrackingUrl("https://3.ya.cc/xyz")).isEqualTo("https://3.ya.cc/xyz");
        assertThat(OrderService.extractTrackingUrl("https://evil.example.com/x")).isNull();
        assertThat(OrderService.extractTrackingUrl("havola yo'q")).isNull();

        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(565L, "Yol", null, null);
        OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(u.id(), o.id(), "chek");
        sixMinutesPassed(o.id());
        orders.releaseDue();
        orders.apply(o.id(), AdminAction.PAY_OK);
        orders.apply(o.id(), AdminAction.ACCEPT);
        orders.apply(o.id(), AdminAction.PREPARING);
        OrderDetails sent = orders.dispatch(o.id(), "https://go.yandex/route/abc");
        assertThat(sent.status()).isEqualTo(OrderStatus.ON_THE_WAY);
        assertThat(sent.trackingUrl()).isEqualTo("https://go.yandex/route/abc");
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", o.id());
    }

    @Test
    void onlyBuyersCanRateProducts() {
        long v = variantId("Chocoboom", null);
        Long productId = jdbc.queryForObject("select product_id from product_variant where id = ?", Long.class, v);
        TelegramUser u = new TelegramUser(566L, "Baho", null, null);
        assertThatThrownBy(() -> ratings.rate(u.id(), productId, 5)).hasMessageContaining("buyurtma");
        OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(u.id(), o.id(), "chek");
        sixMinutesPassed(o.id());
        orders.releaseDue();
        assertThatThrownBy(() -> ratings.rate(u.id(), productId, 5)).isInstanceOf(BusinessException.class);  // hali to'lanmagan
        orders.apply(o.id(), AdminAction.PAY_OK);
        assertThatThrownBy(() -> ratings.rate(u.id(), productId, 6)).isInstanceOf(BusinessException.class);
        var r = ratings.rate(u.id(), productId, 4);
        assertThat(r.mine()).isEqualTo(4);
        assertThat(ratings.rate(u.id(), productId, 5).count()).isEqualTo(r.count());   // qayta baho — yangilanadi
        assertThat(ratings.mine(u.id()).ratings()).containsEntry(productId, 5);
        var view = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(x -> x.id().equals(productId)).findFirst().orElseThrow();
        assertThat(view.ratingCount()).isGreaterThanOrEqualTo(1);
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", o.id());
    }

    @Test
    void periodReportCountsOrdersRevenueAndPaid() {
        var zone = java.time.ZoneId.of("Asia/Tashkent");
        java.time.Instant from = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        java.time.Instant to = from.plus(java.time.Duration.ofDays(1));
        OrderStats.Report before = orders.report(from, to);
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(567L, "Hisobot", null, null);
        OrderDetails paid = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 2)));
        OrderDetails rejected = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        for (OrderDetails o : List.of(paid, rejected)) {
            orders.attachReceiptToOrder(u.id(), o.id(), "chek");
            sixMinutesPassed(o.id());
        }
        orders.releaseDue();
        orders.apply(paid.id(), AdminAction.PAY_OK);
        orders.apply(rejected.id(), AdminAction.REJECT);
        OrderStats.Report after = orders.report(from, to);
        assertThat(after.total() - before.total()).isEqualTo(2);
        assertThat(after.cancelled() - before.cancelled()).isEqualTo(1);
        assertThat(after.revenue() - before.revenue()).isEqualTo(paid.itemsTotal());
        assertThat(after.paid() - before.paid()).isEqualTo(paid.itemsTotal());
        assertThat(orders.ordersBetween(from, to)).extracting(OrderDetails::id).contains(paid.id(), rejected.id());
        // oyning boshidan oldingi davrda bu buyurtmalar yo'q
        assertThat(orders.ordersBetween(java.time.Instant.EPOCH, from)).extracting(OrderDetails::id).doesNotContain(paid.id());
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", paid.id());
    }

    @Test
    void adminCanAddRemoveSizesAndMoveProduct() {
        var cats = menu.activeCategories();
        Long id = menu.createProduct(cats.get(0).getId(), "Olcham tort", null, MenuService.parseSizes("Butun - 300000"), null);
        menu.addVariant(id, "Kichik", 120000);
        assertThatThrownBy(() -> menu.addVariant(id, "kichik", 1000)).isInstanceOf(BusinessException.class);
        var p = menu.allProducts().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertThat(p.getVariants()).extracting(ProductVariant::getName).containsExactly("Butun", "Kichik");
        menu.removeVariant(p.getVariants().get(0).getId());
        p = menu.allProducts().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertThat(p.getVariants()).extracting(ProductVariant::getName).containsExactly("Kichik");
        Long last = p.getVariants().get(0).getId();
        assertThatThrownBy(() -> menu.removeVariant(last)).hasMessageContaining("Oxirgi");
        menu.moveProduct(id, cats.get(1).getId());
        p = menu.allProducts().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        assertThat(p.getCategory().getId()).isEqualTo(cats.get(1).getId());
        menu.archive(id);
    }

    @Test
    void adminCanCreateCategoryThatAppearsOnceItHasProducts() {
        Long cat = menu.createCategory("Bayram tortlari", "🎂");
        assertThatThrownBy(() -> menu.createCategory("bayram tortlari", null)).isInstanceOf(BusinessException.class);
        assertThat(menu.activeCategories()).extracting(Category::getId).contains(cat);
        assertThat(menu.menu().categories()).extracting(MenuView.CategoryView::id).doesNotContain(cat);   // bo'sh — ko'rinmaydi
        Long id = menu.createProduct(cat, "Yangi yil torti", null, MenuService.parseSizes("250000"), null);
        assertThat(menu.menu().categories()).extracting(MenuView.CategoryView::id).contains(cat);
        menu.archive(id);
    }

    @Test
    void newProductsAndCategoriesComeFirstWithNewBadge() {
        Long cat = menu.activeCategories().getFirst().getId();
        Long id = menu.createProduct(cat, "Eng yangi tort", null, MenuService.parseSizes("200000"), null);
        var catView = menu.menu().categories().stream().filter(c -> c.id().equals(cat)).findFirst().orElseThrow();
        assertThat(catView.products().getFirst().id()).isEqualTo(id);
        assertThat(catView.products().getFirst().isNew()).isTrue();
        assertThat(catView.products().stream().filter(x -> !x.id().equals(id))).allMatch(x -> !x.isNew());
        Long newCat = menu.createCategory("Eng yangi kategoriya", "🍓");
        Long p2 = menu.createProduct(newCat, "Qulupnayli", null, MenuService.parseSizes("50000"), null);
        var first = menu.menu().categories().getFirst();
        assertThat(first.id()).isEqualTo(newCat);
        assertThat(first.isNew()).isTrue();
        menu.archive(id);
        menu.archive(p2);
    }

    private OrderDetails onTheWay(TelegramUser u) {
        long v = variantId("Chocoboom", null);
        OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        orders.attachReceiptToOrder(u.id(), o.id(), "chek");
        sixMinutesPassed(o.id());
        orders.releaseDue();
        orders.apply(o.id(), AdminAction.PAY_OK);
        orders.apply(o.id(), AdminAction.ACCEPT);
        orders.apply(o.id(), AdminAction.PREPARING);
        return orders.dispatch(o.id(), null);
    }

    private void onWayMinutesAgo(Long id, long minutes) {
        var t = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(minutes)));
        var next = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofMinutes(minutes)).plus(Order.FIRST_ASK));
        jdbc.update("update orders set on_way_at = ?, next_ask_at = ? where id = ?", t, next, id);
    }

    private String deliveredBy(Long id) {
        return jdbc.queryForObject("select delivered_by from orders where id = ?", String.class, id);
    }

    @Test
    void customerConfirmsDeliveryOrItClosesAutomatically() {
        TelegramUser u = new TelegramUser(568L, "Yetkaz", null, null);
        // 1) 45 daqiqadan keyin so'raladi, mijoz «Ha, oldim» bosadi
        OrderDetails a = onTheWay(u);
        orders.deliveryCheck();
        assertThat(jdbc.queryForObject("select delivery_asks from orders where id = ?", Integer.class, a.id())).isZero();
        onWayMinutesAgo(a.id(), 46);
        orders.deliveryCheck();
        assertThat(jdbc.queryForObject("select delivery_asks from orders where id = ?", Integer.class, a.id())).isEqualTo(1);
        assertThatThrownBy(() -> orders.customerReceived(999L, a.id())).isInstanceOf(BusinessException.class);
        assertThat(orders.customerReceived(u.id(), a.id()).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(deliveredBy(a.id())).isEqualTo("CUSTOMER");
        assertThatThrownBy(() -> orders.customerReceived(u.id(), a.id())).hasMessageContaining("allaqaçon");

        // 2) javob yo'q — 3 soatdan keyin avtomatik
        OrderDetails b = onTheWay(u);
        onWayMinutesAgo(b.id(), 181);
        orders.deliveryCheck();
        assertThat(statusOf(b.id())).isEqualTo("DELIVERED");
        assertThat(deliveredBy(b.id())).isEqualTo("AUTO");

        // 3) «hali kelmadi» degan bo'lsa — avtomatik yopilmaydi
        OrderDetails c = onTheWay(u);
        orders.customerNotArrived(u.id(), c.id());
        onWayMinutesAgo(c.id(), 181);
        orders.deliveryCheck();
        assertThat(statusOf(c.id())).isEqualTo("ON_THE_WAY");
        orders.apply(c.id(), AdminAction.DONE);
        assertThat(deliveredBy(c.id())).isEqualTo("ADMIN");
    }

    @Test
    void customerRatesDeliveredOrderAndProductsGetTheRating() {
        TelegramUser u = new TelegramUser(569L, "Bahochi", null, null);
        OrderDetails o = onTheWay(u);
        assertThatThrownBy(() -> orders.rateOrder(u.id(), o.id(), 5)).isInstanceOf(BusinessException.class);  // hali yo'lda
        orders.customerReceived(u.id(), o.id());
        assertThatThrownBy(() -> orders.rateOrder(999L, o.id(), 5)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.rateOrder(u.id(), o.id(), 0)).isInstanceOf(BusinessException.class);
        assertThat(orders.rateOrder(u.id(), o.id(), 2).rating()).isEqualTo(2);
        Long productId = o.items().getFirst().productId();
        assertThat(ratings.mine(u.id()).ratings()).containsEntry(productId, 2);
        assertThat(orders.rateComment(u.id(), o.id(), "Krem biroz eritilgan edi").ratingComment()).contains("Krem");
        assertThat(orders.rateOrder(u.id(), o.id(), 4).rating()).isEqualTo(4);   // fikrini o'zgartirsa — yangilanadi
        var zone = java.time.ZoneId.of("Asia/Tashkent");
        java.time.Instant from = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        assertThat(orders.ratingBetween(from, from.plus(java.time.Duration.ofDays(1)))[1]).isGreaterThanOrEqualTo(1);
        assertThat(orders.ratedOrders()).extracting(OrderDetails::id).contains(o.id());
        // «Barcha mahsulotlar» fayli raqamlari: sotilgan dona va baho
        var numbers = menu.productNumbers();
        assertThat(numbers.sold().getOrDefault(productId, 0L)).isGreaterThanOrEqualTo(1);
        assertThat(numbers.ratings().get(productId).count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void customerKeepsUpToThreeAddresses() {
        long me = 570L;
        var home = addresses.save(me, null, new AddressService.AddressRequest("Uy", 41.31, 69.28, "Chilonzor", "9-kvartal, 12-uy"));
        addresses.save(me, null, new AddressService.AddressRequest("Iş", 41.30, 69.24, null, null));
        addresses.save(me, null, new AddressService.AddressRequest("Onamning uyi", 41.35, 69.30, null, null));
        assertThatThrownBy(() -> addresses.save(me, null, new AddressService.AddressRequest("Yana", 41.0, 69.0, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> addresses.save(me, null, new AddressService.AddressRequest("", 41.0, 69.0, null, null)))
                .isInstanceOf(BusinessException.class);
        // boshqa odam o'zgartira olmaydi
        assertThatThrownBy(() -> addresses.save(999L, home.id(), new AddressService.AddressRequest("X", 1.0, 1.0, null, null)))
                .isInstanceOf(BusinessException.class);
        addresses.save(me, home.id(), new AddressService.AddressRequest("Uyim", 41.32, 69.29, null, "Yangi uy"));
        assertThat(addresses.list(me)).extracting(AddressService.AddressView::label).containsExactly("Uyim", "Iş", "Onamning uyi");
        addresses.delete(999L, home.id());   // begona — o'chmaydi
        assertThat(addresses.list(me)).hasSize(3);
        addresses.delete(me, home.id());
        assertThat(addresses.list(me)).hasSize(2);
    }

    @Test
    void adminReordersProductsAndManagesCategories() {
        Long cat = menu.createCategory("Tartib sinovi", "🍪");
        Long a = menu.createProduct(cat, "Birinchi", null, MenuService.parseSizes("10000"), null);
        Long b = menu.createProduct(cat, "Ikkinchi", null, MenuService.parseSizes("20000"), null);
        // yangi qo'shilgan birinchi turadi: [b, a]
        assertThat(menu.positionInCategory(b)[0]).isEqualTo(1);
        menu.moveProductInCategory(a, true);
        assertThat(menu.positionInCategory(a)).containsExactly(1, 2);
        menu.moveProductInCategory(a, true);   // allaqachon tepada — o'zgarmaydi
        assertThat(menu.positionInCategory(a)[0]).isEqualTo(1);

        menu.renameCategory(cat, "Tartib");
        assertThatThrownBy(() -> menu.renameCategory(cat, "x")).isInstanceOf(BusinessException.class);
        // yashirish: mijozlar menyusida yo'q, buyurtma ham qilib bo'lmaydi
        assertThat(menu.toggleCategoryVisible(cat)).isFalse();
        assertThat(menu.menu().categories()).extracting(MenuView.CategoryView::id).doesNotContain(cat);
        assertThat(menu.activeCategories()).extracting(Category::getId).contains(cat);   // admin ko'radi
        long v = jdbc.queryForObject("select id from product_variant where product_id = ?", Long.class, a);
        assertThatThrownBy(() -> orders.create(USER, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1))))
                .isInstanceOf(BusinessException.class);
        assertThat(menu.toggleCategoryVisible(cat)).isTrue();
        // mahsulotli kategoriyani o'chirib bo'lmaydi
        assertThatThrownBy(() -> menu.deleteCategory(cat)).hasMessageContaining("2 ta mahsulot");
        menu.archive(a);
        menu.archive(b);
        menu.deleteCategory(cat);
        assertThat(menu.activeCategories()).extracting(Category::getId).doesNotContain(cat);
    }

    @Test
    void newProductIsFirstForFiveDaysThenTakesItsPricePlace() {
        Long cat = menu.createCategory("Narx tartibi", "🍰");
        Long cheap = menu.createProduct(cat, "Arzon", null, MenuService.parseSizes("50000"), null);
        Long dear = menu.createProduct(cat, "Qimmat", null, MenuService.parseSizes("300000"), null);
        jdbc.update("update product set created_at = null where id in (?, ?)", cheap, dear);
        Long fresh = menu.createProduct(cat, "Yangi o'rta", null, MenuService.parseSizes("100000"), null);
        java.util.function.Supplier<List<Long>> order = () -> menu.menu().categories().stream()
                .filter(c -> c.id().equals(cat)).findFirst().orElseThrow().products().stream().map(MenuView.ProductView::id).toList();
        assertThat(order.get()).containsExactly(fresh, dear, cheap);          // 5 kun — birinchi
        jdbc.update("update product set created_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(6))), fresh);
        assertThat(order.get()).containsExactly(dear, fresh, cheap);          // keyin — narxiga qarab
        menu.archive(cheap);
        menu.archive(dear);
        menu.archive(fresh);
        jdbc.update("update category set created_at = null where id = ?", cat);
        menu.deleteCategory(cat);
    }

    @Test
    void mostOrderedCategoryComesFirst() {
        // Setlardagi trayfeldan buyurtma (admin ko'rgan) — Setlar Desertlardan oldinga chiqadi
        long v = variantId("Bananli trayfel", null);
        TelegramUser u = new TelegramUser(571L, "Mashhur", null, null);
        for (int i = 0; i < 2; i++) {
            OrderDetails o = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
            orders.attachReceiptToOrder(u.id(), o.id(), "chek");
            sixMinutesPassed(o.id());
        }
        orders.releaseDue();
        var names = menu.menu().categories().stream().map(MenuView.CategoryView::name).toList();
        assertThat(names.indexOf("Setlar")).isLessThan(names.indexOf("Desertlar"));
    }

    @Test
    void onboardingRequiresAcceptedTerms() {
        TelegramUser u = new TelegramUser(560L, "Shart", null, null);
        customers.setLanguage(u, uz.habibi.bakery.i18n.Lang.UZ);
        customers.setName(u, "Shart");
        customers.setPhone(u, "901234567");
        assertThat(customers.isOnboarded(u.id())).isFalse();
        assertThat(customers.hasAcceptedTerms(u.id())).isFalse();
        customers.acceptTerms(u.id());
        assertThat(customers.isOnboarded(u.id())).isTrue();
        customers.resetProfile(u.id());
        assertThat(customers.hasAcceptedTerms(u.id())).isFalse();
    }

    @Test
    void deletedCustomerStartsOverButKeepsOrders() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(561L, "Ochir", null, null);
        customers.setLanguage(u, uz.habibi.bakery.i18n.Lang.UZ);
        customers.setName(u, "Ochir");
        customers.setPhone(u, "901112233");
        customers.acceptTerms(u.id());
        customers.setBlocked(u.id(), true);
        addresses.save(u.id(), null, new AddressService.AddressRequest("Uy", 41.31, 69.28, null, null));
        Long product = menu.allProducts().stream().filter(p -> p.getName().equals("Chocoboom")).findFirst().orElseThrow().getId();
        likes.toggle(u.id(), product);
        customers.setBlocked(u.id(), false);
        orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        customers.setBlocked(u.id(), true);

        customers.deleteProfile(u.id());

        assertThat(customers.isOnboarded(u.id())).isFalse();
        assertThat(customers.language(u.id())).isEmpty();
        assertThat(customers.phone(u.id())).isEmpty();
        assertThat(customers.isBlocked(u.id())).isFalse();
        assertThat(addresses.list(u.id())).isEmpty();
        assertThat(likes.likedBy(u.id())).isEmpty();
        assertThat(orders.myOrders(u.id())).hasSize(1);
    }

    @Test
    void customerCannotCancelOrder() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(558L, "Bekor", null, null);
        OrderDetails a = orders.create(u, request(DeliveryType.DELIVERY, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        assertThat(a.customerCanCancel(java.time.Instant.now())).isFalse();
        assertThatThrownBy(() -> orders.cancelByCustomer(u.id(), a.id())).hasMessageContaining("boğlaning");
        assertThat(statusOf(a.id())).isEqualTo("NEW");
        jdbc.update("update orders set status = 'CANCELLED' where id = ?", a.id());
    }

    @Test
    void statsCountCancelledSeparately() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(557L, "Stat", null, null);
        OrderStats before = orders.stats();
        OrderDetails kept = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 2)));
        OrderDetails cancelled = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 5)));
        orders.apply(cancelled.id(), AdminAction.REJECT);

        OrderStats after = orders.stats();
        assertThat(after.today().revenue() - before.today().revenue()).isEqualTo(20000);
        assertThat(after.today().orders() - before.today().orders()).isEqualTo(1);
        assertThat(after.today().cancelled() - before.today().cancelled()).isEqualTo(1);
        assertThat(after.month().cancelled()).isGreaterThanOrEqualTo(after.today().cancelled());
        assertThat(after.activeCount() - before.activeCount()).isEqualTo(1);
        assertThat(after.topProducts()).hasSizeLessThanOrEqualTo(3).isNotEmpty();
    }

    @Test
    void clickAndPaymeAreNotAvailable() {
        long v = variantId("Chocoboom", null);
        assertThatThrownBy(() -> orders.create(USER, request(DeliveryType.PICKUP, PaymentMethod.CLICK,
                new CreateOrderRequest.Item(v, 1)))).hasMessageContaining("Click orqali tölov hozirça mavjud emas");
        assertThatThrownBy(() -> orders.create(USER, request(DeliveryType.PICKUP, PaymentMethod.PAYME,
                new CreateOrderRequest.Item(v, 1)))).hasMessageContaining("Payme");
    }

    @Test
    void menuIsLocalized() {
        // kategoriyalar tartibi mashhurlikka qarab o'zgaradi — Tortlarni nomi bo'yicha topamiz
        var ru = menu.menu(uz.habibi.bakery.i18n.Lang.RU).categories().stream()
                .filter(c -> c.name().equals("Десерты")).findFirst().orElseThrow();
        var dubai = ru.products().stream().filter(p -> p.name().equals("Торт «Дубай»")).findFirst().orElseThrow();
        assertThat(dubai.variants().getFirst().name()).isEqualTo("Целый");
        var cyr = menu.menu(uz.habibi.bakery.i18n.Lang.CYR).categories().stream()
                .filter(c -> c.name().equals("Десертлар")).findFirst().orElseThrow();
        assertThat(cyr.products().getFirst().variants().get(1).name()).isEqualTo("Ярим");
    }

    @Test
    void orderRemembersLanguageAndRussianNames() {
        long v = variantId("Dubai torti", "Çorak");
        var req = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Иван", "+998901234567",
                DeliveryType.PICKUP, null, null, null, TOMORROW, "13:00–15:00", PaymentMethod.CASH, null, "ru", null);
        OrderDetails o = orders.create(new TelegramUser(560L, "Иван", null, null), req);
        assertThat(o.customerLanguage()).isEqualTo(uz.habibi.bakery.i18n.Lang.RU);
        assertThat(o.items().getFirst().name(uz.habibi.bakery.i18n.Lang.RU)).isEqualTo("Торт «Дубай»");
        assertThat(o.items().getFirst().variant(uz.habibi.bakery.i18n.Lang.RU)).isEqualTo("Четверть");
        assertThat(o.items().getFirst().productName()).isEqualTo("Dubai torti"); // admin uchun o'zbekcha
    }

    @Test
    void orderWithoutDateAndTimeIsForToday() {
        long v = variantId("Chocoboom", null);
        var req = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.DELIVERY, null, 41.3111, 69.2797, null, null, PaymentMethod.CARD_TRANSFER, null, null, null);
        OrderDetails o = orders.create(USER, req);
        assertThat(o.deliveryTime()).isNull();
        assertThat(o.deliveryDate()).isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tashkent")));
    }

    @Test
    void errorsAreLocalized() {
        long v = variantId("Chocoboom", null);
        var noAddress = new CreateOrderRequest(List.of(new CreateOrderRequest.Item(v, 1)), "Ali", "+998901234567",
                DeliveryType.DELIVERY, " ", null, null, TOMORROW, "13:00–15:00", PaymentMethod.CASH, null, "ru", null);
        try {
            orders.create(USER, noAddress);
        } catch (BusinessException e) {
            assertThat(e.localized(uz.habibi.bakery.i18n.Lang.RU)).isEqualTo("Отметьте адрес доставки на карте 📍");
            assertThat(e.localized(uz.habibi.bakery.i18n.Lang.CYR)).startsWith("Етказиб бериш манзилини");
            assertThat(e.getMessage()).startsWith("Yetkazib beriş manzilini");
            return;
        }
        throw new AssertionError("xato kutilgan edi");
    }

    @Test
    void receiptUploadedInMiniApp() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(570L, "Up", null, null);
        OrderDetails o = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        // boshqa mijoz birovning buyurtmasiga chek yuklay olmaydi
        assertThatThrownBy(() -> orders.uploadReceipt(571L, o.id(), new byte[]{1})).isInstanceOf(BusinessException.class);
        assertThat(orders.uploadReceipt(570L, o.id(), new byte[]{1, 2, 3}).paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        // qayta yuklash mumkin (noto'g'ri chek yuborgan bo'lsa)
        assertThat(orders.uploadReceipt(570L, o.id(), new byte[]{4}).paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        orders.apply(o.id(), AdminAction.PAY_OK);
        assertThatThrownBy(() -> orders.uploadReceipt(570L, o.id(), new byte[]{5})).isInstanceOf(BusinessException.class);

        OrderDetails cash = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        assertThatThrownBy(() -> orders.uploadReceipt(570L, cash.id(), new byte[]{1})).isInstanceOf(BusinessException.class);
    }

    @Test
    void typedPhoneIsNotVerifiedButContactPhoneIs() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(590L, "Prof", null, null);
        orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        // Mini App'da yozilgan raqam bor, lekin tasdiqlanmagan — bot raqam so'rashi kerak
        assertThat(customers.phone(590L)).isPresent();
        assertThat(customers.hasVerifiedPhone(590L)).isFalse();
        assertThat(customers.name(590L)).contains("Ali"); // buyurtmadagi ism

        customers.setPhone(u, "+998 90 000 00 01");
        assertThat(customers.hasVerifiedPhone(590L)).isTrue();
        // keyingi buyurtmada boshqa raqam yozilsa ham tasdiqlangan raqam o'zgarmaydi
        orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 1)));
        assertThat(customers.phone(590L)).contains("+998900000001");

        customers.setName(u, "Prof Test");
        assertThat(customers.name(590L)).contains("Prof Test");
        customers.resetProfile(590L);
        assertThat(customers.name(590L)).isEmpty();
        assertThat(customers.hasVerifiedPhone(590L)).isFalse();
    }

    @Test
    void receiptGoesToChosenOrder() {
        long v = variantId("Chocoboom", null);
        TelegramUser u = new TelegramUser(600L, "Ko'p", null, null);
        OrderDetails first = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 1)));
        OrderDetails second = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 2)));
        orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 3))); // naqd — chek kerak emas

        assertThat(orders.awaitingReceipt(600L)).extracting(OrderDetails::id).containsExactly(second.id(), first.id());

        // eski (birinchi) buyurtmaga chek — oxirgisiga emas
        assertThat(orders.attachReceiptToOrder(600L, first.id(), "file-a").paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        assertThat(orders.details(second.id()).paymentStatus()).isEqualTo(PaymentStatus.AWAITING_RECEIPT);
        // «Boshqa chek yuborish» — o'sha buyurtmaga qayta
        assertThat(orders.attachReceiptToOrder(600L, first.id(), "file-b").paymentStatus()).isEqualTo(PaymentStatus.RECEIPT_SENT);
        // birovning buyurtmasiga mumkin emas
        assertThatThrownBy(() -> orders.attachReceiptToOrder(601L, second.id(), "x")).isInstanceOf(BusinessException.class);

        orders.apply(first.id(), AdminAction.PAY_OK);
        assertThat(orders.awaitingReceipt(600L)).extracting(OrderDetails::id).containsExactly(second.id());
    }

    @Test
    void customerSummariesForAdmin() {
        long v = variantId("Chocoboom", null); // 10 000
        TelegramUser buyer = new TelegramUser(610L, "Xaridor", null, "xaridor");
        orders.create(buyer, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 2)));
        OrderDetails cancelled = orders.create(buyer, request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 9)));
        orders.apply(cancelled.id(), AdminAction.REJECT);
        customers.setLanguage(new TelegramUser(611L, "Yangi", null, null), uz.habibi.bakery.i18n.Lang.RU); // buyurtmasiz

        var all = customers.summaries();
        var b = all.stream().filter(c -> c.telegramId() == 610L).findFirst().orElseThrow();
        assertThat(b.orders()).isEqualTo(1);          // bekor qilingani sanalmaydi
        assertThat(b.spent()).isEqualTo(20000);
        assertThat(b.username()).isEqualTo("xaridor");
        assertThat(b.displayName()).isEqualTo("Ali"); // buyurtmadagi ism profilga yozilgan
        assertThat(b.lastOrderAt()).isNotNull();

        var fresh = all.stream().filter(c -> c.telegramId() == 611L).findFirst().orElseThrow();
        assertThat(fresh.orders()).isZero();
        assertThat(fresh.lastOrderAt()).isNull();
        assertThat(fresh.displayName()).isEqualTo("Yangi");
        // buyurtma bergan mijozlar buyurtmasizlardan oldin turadi
        assertThat(all.indexOf(b)).isLessThan(all.indexOf(fresh));
    }

    @Test
    void paidAndUnpaidPerCustomerAndBlocking() {
        long v = variantId("Chocoboom", null); // 10 000
        TelegramUser u = new TelegramUser(620L, "Blok", null, null);
        OrderDetails paid = orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 3)));
        orders.create(u, request(DeliveryType.PICKUP, PaymentMethod.CARD_TRANSFER, new CreateOrderRequest.Item(v, 2)));
        orders.apply(paid.id(), AdminAction.PAY_OK);

        CustomerSummary c = customers.summary(620L).orElseThrow();
        assertThat(c.spent()).isEqualTo(50000);
        assertThat(c.paid()).isEqualTo(30000);
        assertThat(c.unpaid()).isEqualTo(20000);
        assertThat(c.blocked()).isFalse();

        customers.setBlocked(620L, true);
        assertThat(customers.isBlocked(620L)).isTrue();
        assertThat(customers.summary(620L).orElseThrow().blocked()).isTrue();
        // ma'lumotlar saqlanadi
        assertThat(orders.myOrders(620L)).hasSize(2);

        customers.setBlocked(620L, false);
        assertThat(customers.isBlocked(620L)).isFalse();
        assertThat(customers.isBlocked(999999L)).isFalse(); // noma'lum foydalanuvchi bloklanmagan
    }

    @Test
    void discountChangesMenuAndOrderPrice() {
        long v = variantId("Bananli trayfel", null); // 80 000
        Long productId = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(p -> p.name().equals("Bananli trayfel")).findFirst().orElseThrow().id();
        menu.setDiscount(productId, 15);
        try {
            var pv = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                    .filter(p -> p.id().equals(productId)).findFirst().orElseThrow();
            assertThat(pv.discount()).isEqualTo(15);
            assertThat(pv.variants().getFirst().price()).isEqualTo(68000);
            assertThat(pv.variants().getFirst().oldPrice()).isEqualTo(80000);

            OrderDetails o = orders.create(new TelegramUser(630L, "Aksiya", null, null),
                    request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 2)));
            assertThat(o.itemsTotal()).isEqualTo(136000); // serverda chegirmali narx
            assertThatThrownBy(() -> menu.setDiscount(productId, 95)).isInstanceOf(BusinessException.class);
        } finally {
            menu.setDiscount(productId, 0);
        }
        var back = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(p -> p.id().equals(productId)).findFirst().orElseThrow();
        assertThat(back.variants().getFirst().oldPrice()).isNull();
        assertThat(back.variants().getFirst().price()).isEqualTo(80000);
    }

    @Test
    void trendingShowsMonthlySoldCount() {
        long v = variantId("Malinali desert", null);
        Long productId = menu.menu().categories().stream().flatMap(c -> c.products().stream())
                .filter(p -> p.name().equals("Malinali desert")).findFirst().orElseThrow().id();
        orders.create(new TelegramUser(631L, "Ko'p", null, null),
                request(DeliveryType.PICKUP, PaymentMethod.CASH, new CreateOrderRequest.Item(v, 50)));
        var trending = menu.menu().trending();
        assertThat(trending).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(trending.getFirst().id()).isEqualTo(productId);
        assertThat(trending.getFirst().count()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void likesToggleAndRankMostLiked() {
        Long a = menu.menu().categories().getFirst().products().get(0).id();
        Long b = menu.menu().categories().getFirst().products().get(1).id();
        assertThat(likes.toggle(700L, a).liked()).isTrue();
        assertThat(likes.toggle(701L, a).count()).isEqualTo(2);
        assertThat(likes.toggle(700L, b).count()).isEqualTo(1);
        // qayta bosilsa — like olinadi
        var undo = likes.toggle(700L, b);
        assertThat(undo.liked()).isFalse();
        assertThat(undo.count()).isZero();
        assertThat(likes.likedBy(700L)).containsExactly(a);

        var view = menu.menu();
        assertThat(view.mostLiked().getFirst().id()).isEqualTo(a);
        assertThat(view.mostLiked().getFirst().count()).isEqualTo(2);
        assertThat(view.mostLiked()).noneMatch(r -> r.id().equals(b)); // 0 like — ro'yxatda yo'q
        var pv = view.categories().getFirst().products().get(0);
        assertThat(pv.likes()).isEqualTo(2);
        assertThatThrownBy(() -> likes.toggle(700L, 999999L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void phoneIsSavedNormalized() {
        TelegramUser u = new TelegramUser(580L, "Tel", null, null);
        assertThat(customers.phone(580L)).isEmpty();
        customers.setPhone(u, "998901234567");
        assertThat(customers.phone(580L)).contains("+998901234567");
        customers.setPhone(u, "+998 (93) 111-22-33");
        assertThat(customers.phone(580L)).contains("+998931112233");
    }

    @Test
    void periodMath() {
        var p = new OrderStats.Period(3, 1, 300000);
        assertThat(p.averageCheck()).isEqualTo(100000);
        assertThat(p.cancelRate()).isEqualTo(25);
        assertThat(new OrderStats.Period(0, 0, 0).averageCheck()).isZero();
    }
}
