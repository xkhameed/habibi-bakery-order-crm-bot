package uz.habibi.bakery.service;

import java.time.LocalDate;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.domain.*;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.repo.CustomerRepository;
import uz.habibi.bakery.repo.OrderRepository;
import uz.habibi.bakery.repo.ProductVariantRepository;

@Service
public class OrderService {

    @org.springframework.beans.factory.annotation.Autowired
    private RatingService ratingService;

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ProductVariantRepository variants;
    private final ApplicationEventPublisher events;
    private final AppProperties props;

    public OrderService(OrderRepository orders, CustomerRepository customers, ProductVariantRepository variants,
                        ApplicationEventPublisher events, AppProperties props) {
        this.orders = orders;
        this.customers = customers;
        this.variants = variants;
        this.events = events;
        this.props = props;
    }

    @Transactional
    public OrderDetails create(TelegramUser user, CreateOrderRequest req) {
        validateSchedule(req);
        // Click / Payme ulanmagan
        if (req.paymentMethod() == PaymentMethod.CLICK || req.paymentMethod() == PaymentMethod.PAYME) {
            throw new BusinessException("err.qr_unavailable", req.paymentMethod() == PaymentMethod.CLICK ? "Click" : "Payme");
        }
        boolean hasLocation = req.latitude() != null && req.longitude() != null;
        // Yetkazib berishda xaritadagi nuqta majburiy (faqat izoh yoki ko'cha nomi bilan qabul qilinmaydi)
        if (req.deliveryType() == DeliveryType.DELIVERY && !hasLocation) {
            throw new BusinessException("err.location_required");
        }

        // Bir xil variant ikki marta kelsa, miqdorini qo'shamiz
        Map<Long, Integer> qty = new LinkedHashMap<>();
        req.items().forEach(i -> qty.merge(i.variantId(), i.quantity(), Integer::sum));

        Map<Long, ProductVariant> found = variants.findAllWithProduct(qty.keySet()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, Function.identity()));

        Customer customer = customers.findByTelegramId(user.id()).orElseGet(() -> new Customer(user.id()));
        customer.setFirstName(user.firstName());
        customer.setLastName(user.lastName());
        customer.setUsername(user.username());
        // Telegram orqali tasdiqlangan raqam saqlanib qoladi; buyurtmadagi raqam buyurtmaning o'zida turadi
        if (!customer.isPhoneVerified()) {
            customer.setPhone(req.phone().trim());
        }
        if (customer.getName() == null || customer.getName().isBlank()) {
            customer.setName(req.customerName().trim());
        }
        if (req.language() != null) {
            customer.setLanguage(Lang.fromCode(req.language()).code());
        }
        customer = customers.save(customer);

        Order order = new Order(customer);
        for (var e : qty.entrySet()) {
            ProductVariant v = found.get(e.getKey());
            if (v == null) {
                throw new BusinessException("err.menu_changed");
            }
            if (!v.getProduct().isAvailable() || v.getProduct().isArchived()
                    || !v.getProduct().getCategory().isActive() || v.getProduct().getCategory().isDeleted()) {
                throw new BusinessException("err.sold_out",
                        I18n.content(Lang.fromCode(req.language()), v.getProduct().getName(), v.getProduct().getNameRu()));
            }
            order.addItem(v, e.getValue());
        }
        order.setCustomerName(req.customerName().trim());
        order.setPhone(req.phone().trim());
        order.setDeliveryType(req.deliveryType());
        if (req.deliveryType() == DeliveryType.DELIVERY) {
            order.setAddress(isBlank(req.address()) ? I18n.t(Lang.UZ, "delivery.map_point") : req.address().trim());
            if (hasLocation) {
                order.setLocation(req.latitude(), req.longitude());
            }
        }
        order.setDeliveryDate(req.deliveryDate() != null ? req.deliveryDate() : LocalDate.now(props.zone()));
        order.setDeliveryTime(isBlank(req.deliveryTime()) ? null : req.deliveryTime());
        order.setComment(isBlank(req.comment()) ? null : req.comment().trim());
        order.setInscription(isBlank(req.inscription()) ? null : req.inscription().trim());
        order.setPaymentMethod(req.paymentMethod());
        order.setPaymentStatus(req.paymentMethod() == PaymentMethod.CASH
                ? PaymentStatus.PAY_ON_DELIVERY : PaymentStatus.AWAITING_RECEIPT);
        order.setAdminNotified(true);   // buyurtma adminga darhol boradi (chek keyin alohida keladi)

        OrderDetails details = OrderDetails.of(orders.save(order));
        events.publishEvent(OrderEvent.of(OrderEvent.Kind.CREATED, details));
        return details;
    }

    private void validateSchedule(CreateOrderRequest req) {
        if (req.deliveryDate() == null && isBlank(req.deliveryTime())) {
            return;   // sana/vaqt tanlanmagan — «iloji boricha tezroq»
        }
        if (req.deliveryDate() == null || isBlank(req.deliveryTime())) {
            throw new BusinessException("err.bad_time");
        }
        LocalDate today = LocalDate.now(props.zone());
        if (req.deliveryDate().isBefore(today)
                || req.deliveryDate().isAfter(today.plusDays(props.shop().daysAhead()))) {
            throw new BusinessException("err.bad_date");
        }
        if (!props.shop().timeSlots().contains(req.deliveryTime())) {
            throw new BusinessException("err.bad_time");
        }
    }

    @Transactional(readOnly = true)
    public List<OrderDetails> myOrders(long telegramId) {
        return orders.findTop20ByCustomerTelegramIdOrderByCreatedAtDesc(telegramId).stream()
                .map(OrderDetails::of).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderDetails> activeOrders() {
        return orders.findAllByStatusNotInOrderByDeliveryDateAscCreatedAtAsc(
                        EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED)).stream()
                .filter(Order::isAdminNotified)   // admin hali ko'rmagan (to'lanmagan) buyurtmalar ro'yxatda chiqmaydi
                .map(OrderDetails::of).toList();
    }

    @Transactional(readOnly = true)
    public OrderDetails details(Long orderId) {
        return orders.findWithItemsById(orderId).map(OrderDetails::of)
                .orElseThrow(() -> new BusinessException("Buyurtma #" + orderId + " topilmadi"));
    }

    @Transactional
    public void saveAdminMessageId(Long orderId, Integer messageId) {
        orders.findById(orderId).ifPresent(o -> o.setAdminMessageId(messageId));
    }

    /**
     * Kuryerni kuzatish havolasidan Yandex/Yango havolasini ajratib oladi (admin butun «ulashish» matnini yuborishi mumkin).
     * Topilmasa null.
     */
    public static String extractTrackingUrl(String text) {
        if (text == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[)\\].,!?»\"']+$", "");
            try {
                String host = java.net.URI.create(url).getHost();
                if (host != null && url.length() <= 500) {
                    String h = host.toLowerCase(java.util.Locale.ROOT);
                    if (h.contains("yandex") || h.contains("yango") || h.endsWith("ya.cc") || h.endsWith("ya.ru")) {
                        return url;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // noto'g'ri havola — keyingisini ko'ramiz
            }
        }
        return null;
    }

    /** «Yo'lga chiqdi»: kuzatish havolasi (bo'lsa) saqlanadi va holat o'zgaradi — mijozga havola bilan xabar boradi. */
    @Transactional
    public OrderDetails dispatch(Long orderId, String trackingUrl) {
        orders.findById(orderId).ifPresent(o -> o.setTrackingUrl(trackingUrl));
        return apply(orderId, AdminAction.ON_WAY);
    }

    /** Chek rasmi (Telegram file_id) — keyin buyurtmani ochganda ko'rsatish uchun. */
    @Transactional
    public void saveReceiptFileId(Long orderId, String fileId) {
        orders.findById(orderId).ifPresent(o -> o.setReceiptFileId(fileId));
    }

    /** Qo'shimcha admin chatidagi kartochka (keyin holat o'zgarganda u ham yangilanadi). */
    @Transactional
    public void saveAdminCopy(Long orderId, Long chatId, Integer messageId) {
        orders.findById(orderId).ifPresent(o -> {
            java.util.Map<Long, Integer> map = OrderDetails.of(o).adminCopyMap();
            map.put(chatId, messageId);
            String joined = String.join(",", map.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList());
            o.setAdminCopies(joined.length() > 500 ? null : joined);
        });
    }

    /**
     * Admin qidiruvi: «45» / «#45» — buyurtma raqami; 7+ raqam — telefon (oxirgi raqamlari bo'yicha).
     * Faqat admin ko'rgan buyurtmalar orasidan, eng yangisi birinchi.
     */
    @Transactional(readOnly = true)
    public List<OrderDetails> search(String query) {
        String q = query == null ? "" : query.strip();
        String digits = q.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return List.of();
        }
        if (digits.length() <= 6) {
            return orders.findWithItemsById(Long.parseLong(digits))
                    .filter(Order::isAdminNotified).map(OrderDetails::of).stream().toList();
        }
        String tail = digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
        return orders.findTop500ByAdminNotifiedTrueOrderByCreatedAtDesc().stream()
                .filter(o -> o.getPhone() != null && o.getPhone().replaceAll("\\D", "").endsWith(tail))
                .limit(15)
                .map(OrderDetails::of)
                .toList();
    }

    /** Bugun kelgan cheklar (eng eskisi birinchi). */
    @Transactional(readOnly = true)
    public List<OrderDetails> receiptsToday(java.time.ZoneId zone) {
        Instant start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        return orders.findAllByReceiptFileIdNotNullAndReceiptAtAfterOrderByReceiptAtAsc(start).stream()
                .map(OrderDetails::of).toList();
    }

    /** Rasmi bor, lekin hali tekshirilmagan cheklar. */
    @Transactional(readOnly = true)
    public List<OrderDetails> uncheckedReceiptPhotos() {
        return uncheckedReceipts().stream().filter(o -> o.receiptFileId() != null).toList();
    }

    /** Bugun bekor qilingan buyurtmalar (admin, mijoz yoki 30 daqiqalik muddat tugagani sababli). */
    @Transactional(readOnly = true)
    public List<OrderDetails> cancelledToday(java.time.ZoneId zone) {
        Instant start = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        return orders.findAllByStatusAndUpdatedAtAfterOrderByUpdatedAtDesc(OrderStatus.CANCELLED, start).stream()
                .map(OrderDetails::of).toList();
    }

    /** Admin hali tasdiqlamagan: qabul qilinmagan (Yangi) yoki chek tekshirilmagan. Eng eskisi birinchi. */
    @Transactional(readOnly = true)
    public List<OrderDetails> unconfirmed() {
        return activeOrders().stream()
                .filter(o -> o.status() == OrderStatus.NEW || o.paymentStatus() == PaymentStatus.RECEIPT_SENT)
                .sorted(java.util.Comparator.comparing(OrderDetails::createdAt))
                .toList();
    }

    /** Chek kelgan, lekin hali tasdiqlanmagan buyurtmalar (eng eskisi birinchi). */
    @Transactional(readOnly = true)
    public List<OrderDetails> uncheckedReceipts() {
        return orders.findAllByAdminNotifiedTrueAndPaymentStatusAndStatusNotOrderByCreatedAtAsc(
                PaymentStatus.RECEIPT_SENT, OrderStatus.CANCELLED).stream().map(OrderDetails::of).toList();
    }

    /** Mijoz botga chek rasmini yubordi. Chek kutilayotgan oxirgi buyurtmaga biriktiriladi. */
    @Transactional
    public OrderDetails attachReceipt(long telegramId, String photoFileId) {
        Order order = orders.findFirstByCustomerTelegramIdAndPaymentStatusInAndStatusNotOrderByCreatedAtDesc(
                        telegramId, EnumSet.of(PaymentStatus.AWAITING_RECEIPT, PaymentStatus.RECEIPT_SENT),
                        OrderStatus.CANCELLED)
                .orElseThrow(() -> new BusinessException("err.no_awaiting_order"));
        return handleReceipt(order, photoFileId, null);
    }

    /** Chek kutilayotgan (bekor qilinmagan) buyurtmalar — yangisi birinchi. */
    @Transactional(readOnly = true)
    public List<OrderDetails> awaitingReceipt(long telegramId) {
        return orders.findTop20ByCustomerTelegramIdOrderByCreatedAtDesc(telegramId).stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getStatus() != OrderStatus.DELIVERED)
                .filter(o -> o.getPaymentStatus() == PaymentStatus.AWAITING_RECEIPT
                        || o.getPaymentStatus() == PaymentStatus.RECEIPT_SENT)
                .map(OrderDetails::of)
                .toList();
    }

    /** Mijoz botda aniq buyurtma uchun chek yubordi (Telegram file_id). */
    @Transactional
    public OrderDetails attachReceiptToOrder(long telegramId, Long orderId, String photoFileId) {
        return receipt(telegramId, orderId, photoFileId, null);
    }

    /** Mijoz chek rasmini Mini App ichida yukladi (aniq buyurtma uchun). */
    @Transactional
    public OrderDetails uploadReceipt(long telegramId, Long orderId, byte[] image) {
        return receipt(telegramId, orderId, null, image);
    }

    private OrderDetails receipt(long telegramId, Long orderId, String fileId, byte[] image) {
        Order order = orders.findWithItemsById(orderId)
                .filter(o -> o.getCustomer().getTelegramId() == telegramId)
                .orElseThrow(() -> new BusinessException("err.receipt_not_allowed"));
        boolean awaiting = order.getPaymentStatus() == PaymentStatus.AWAITING_RECEIPT
                || order.getPaymentStatus() == PaymentStatus.RECEIPT_SENT;
        if (!awaiting || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("err.receipt_not_allowed");
        }
        return handleReceipt(order, fileId, image);
    }

    /** Chek keldi — adminlarga yuboriladi (buyurtma kartochkasi ular tomonda allaqachon bor). */
    private OrderDetails handleReceipt(Order order, String fileId, byte[] image) {
        order.setPaymentStatus(PaymentStatus.RECEIPT_SENT);
        if (!order.isAdminNotified()) {
            // eski (o'zgarishdan oldingi) buyurtma — kartochka va chek birga ketadi
            order.setAdminNotified(true);
            order.holdReceipt(null, null);
            OrderDetails details = OrderDetails.of(order);
            events.publishEvent(OrderEvent.of(OrderEvent.Kind.RECEIPT_HELD, details));   // mijozga «chek qabul qilindi»
            events.publishEvent(new OrderEvent(OrderEvent.Kind.RELEASED, details, fileId, image));
            return details;
        }
        OrderDetails details = OrderDetails.of(order);
        events.publishEvent(new OrderEvent(OrderEvent.Kind.RECEIPT_SENT, details, fileId, image));
        return details;
    }

    /** Har 15 soniyada: eski tartibda ushlab turilgan buyurtmalar (chek kelgan yoki naqd) qolgan bo'lsa — adminga yuboradi. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    @Transactional
    public void releaseDue() {
        for (Order o : orders.findAllByAdminNotifiedFalseAndPaymentStatusInAndStatusNotAndCreatedAtBefore(
                EnumSet.of(PaymentStatus.RECEIPT_SENT, PaymentStatus.PAY_ON_DELIVERY), OrderStatus.CANCELLED,
                Instant.now())) {
            String fileId = o.getHeldReceiptFileId();
            byte[] image = o.getHeldReceiptImage() == null ? null : java.util.Base64.getDecoder().decode(o.getHeldReceiptImage());
            o.setAdminNotified(true);
            o.holdReceipt(null, null);
            events.publishEvent(new OrderEvent(OrderEvent.Kind.RELEASED, OrderDetails.of(o), fileId, image));
        }
    }

    /**
     * Har daqiqada: karta bilan berilib, 30 daqiqa ichida chek kelmagan va admin hali qabul qilmagan (Yangi)
     * buyurtmalarni avtomatik bekor qiladi. Naqd buyurtmalarga tegmaydi.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void expireUnpaid() {
        for (Order o : orders.findAllByPaymentStatusAndStatusAndCreatedAtBefore(
                PaymentStatus.AWAITING_RECEIPT, OrderStatus.NEW, Instant.now().minus(Order.RECEIPT_TIMEOUT))) {
            o.setStatus(OrderStatus.CANCELLED);
            events.publishEvent(OrderEvent.of(OrderEvent.Kind.EXPIRED, OrderDetails.of(o)));
        }
    }

    /** Mijoz «✅ Qabul qildim / Ha, oldim» bosdi. */
    @Transactional
    public OrderDetails customerReceived(long telegramId, Long orderId) {
        Order o = onTheWayOf(telegramId, orderId);
        o.setStatus(OrderStatus.DELIVERED);
        o.setDeliveredBy("CUSTOMER");
        o.setNextAskAt(null);
        OrderDetails details = OrderDetails.of(o);
        events.publishEvent(OrderEvent.of(OrderEvent.Kind.RECEIVED, details));
        return details;
    }

    /**
     * Mijoz buyurtmaga 1–5 baho berdi. Baho buyurtmadagi har bir mahsulotga ham qo'yiladi
     * (mahsulot bahosi faqat to'langan buyurtmalarda — RatingService tekshiradi). Qayta bossa — yangilanadi.
     */
    @Transactional
    public OrderDetails rateOrder(long telegramId, Long orderId, int stars) {
        if (stars < 1 || stars > 5) {
            throw new BusinessException("err.rating_range");
        }
        Order o = orders.findWithItemsById(orderId)
                .filter(x -> x.getCustomer().getTelegramId() == telegramId)
                .orElseThrow(() -> new BusinessException("err.rating_not_allowed"));
        if (o.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("err.rating_not_allowed");
        }
        o.setRating(stars);
        for (Long productId : o.getItems().stream().map(i -> i.getProductId()).distinct().toList()) {
            try {
                ratingService.rate(telegramId, productId, stars);
            } catch (BusinessException ignored) {
                // mahsulot o'chirilgan yoki to'lanmagan — buyurtma bahosi baribir saqlanadi
            }
        }
        OrderDetails details = OrderDetails.of(o);
        events.publishEvent(OrderEvent.of(OrderEvent.Kind.RATED, details));
        return details;
    }

    /** Past bahoga mijoz izohi — adminlarga yuboriladi. */
    @Transactional
    public OrderDetails rateComment(long telegramId, Long orderId, String comment) {
        Order o = orders.findWithItemsById(orderId)
                .filter(x -> x.getCustomer().getTelegramId() == telegramId)
                .orElseThrow(() -> new BusinessException("err.rating_not_allowed"));
        String c = comment == null ? "" : comment.strip();
        o.setRatingComment(c.length() > 500 ? c.substring(0, 500) : c);
        OrderDetails details = OrderDetails.of(o);
        events.publishEvent(OrderEvent.of(OrderEvent.Kind.RATING_COMMENT, details));
        return details;
    }

    /** Hamma baholangan buyurtmalar (eng yangisi birinchi). */
    @Transactional(readOnly = true)
    public List<OrderDetails> ratedOrders() {
        return orders.findAllByRatingNotNullOrderByCreatedAtDesc().stream().map(OrderDetails::of).toList();
    }

    /** O'rtacha baho va soni — statistika uchun. */
    @Transactional(readOnly = true)
    public double[] ratingBetween(Instant from, Instant to) {
        Object[] r = orders.ratingBetween(from, to).getFirst();
        double avg = r[0] == null ? 0 : Math.round(((Number) r[0]).doubleValue() * 10) / 10.0;
        return new double[] {avg, num(r[1])};
    }

    /** Mijoz «❌ Hali kelmadi» bosdi: adminlarga signal, 30 daqiqadan keyin yana so'raladi. */
    @Transactional
    public OrderDetails customerNotArrived(long telegramId, Long orderId) {
        Order o = onTheWayOf(telegramId, orderId);
        o.setNotArrivedAt(Instant.now());
        o.setNextAskAt(Instant.now().plus(java.time.Duration.ofMinutes(30)));
        OrderDetails details = OrderDetails.of(o);
        events.publishEvent(OrderEvent.of(OrderEvent.Kind.NOT_ARRIVED, details));
        return details;
    }

    private Order onTheWayOf(long telegramId, Long orderId) {
        Order o = orders.findWithItemsById(orderId)
                .filter(x -> x.getCustomer().getTelegramId() == telegramId)
                .orElseThrow(() -> new BusinessException("err.received_not_allowed"));
        if (o.getStatus() != OrderStatus.ON_THE_WAY) {
            throw new BusinessException(o.getStatus() == OrderStatus.DELIVERED ? "err.received_already" : "err.received_not_allowed");
        }
        return o;
    }

    /**
     * Har daqiqada yo'ldagi buyurtmalar: 45 daqiqada mijozdan so'raladi, 1,5 soatda eslatiladi,
     * 3 soatda javob bo'lmasa avtomatik «Topshirildi» (mijoz «hali kelmadi» degan bo'lsa — yo'q, admin hal qiladi).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    @Transactional
    public void deliveryCheck() {
        Instant now = Instant.now();
        for (Order o : orders.findAllByStatus(OrderStatus.ON_THE_WAY)) {
            if (o.getOnWayAt() == null) {
                continue; // bu funksiyadan oldin yo'lga chiqqan
            }
            if (o.getNotArrivedAt() == null && !now.isBefore(o.getOnWayAt().plus(Order.AUTO_DELIVER))) {
                o.setStatus(OrderStatus.DELIVERED);
                o.setDeliveredBy("AUTO");
                o.setNextAskAt(null);
                events.publishEvent(OrderEvent.of(OrderEvent.Kind.AUTO_DELIVERED, OrderDetails.of(o)));
                continue;
            }
            if (o.getNextAskAt() != null && !now.isBefore(o.getNextAskAt()) && o.getDeliveryAsks() < 4) {
                o.setDeliveryAsks(o.getDeliveryAsks() + 1);
                o.setNextAskAt(o.getDeliveryAsks() == 1 && o.getNotArrivedAt() == null ? o.getOnWayAt().plus(Order.SECOND_ASK) : null);
                events.publishEvent(OrderEvent.of(OrderEvent.Kind.DELIVERY_ASK, OrderDetails.of(o)));
            }
        }
    }

    /**
     * Mijoz o'zi bekor qila olmaydi (buyurtma adminga darhol boradi) — bekor qilish faqat biz bilan bog'lanib.
     * Eski xabarlardagi «Bekor qilish» tugmasi bosilsa shu xato chiqadi.
     */
    @Transactional(readOnly = true)
    public OrderDetails cancelByCustomer(long telegramId, Long orderId) {
        throw new BusinessException("err.cancel_expired");
    }

    /** Admin tugmasi bosilganda chaqiriladi. */
    @Transactional
    public OrderDetails apply(Long orderId, AdminAction action) {
        Order o = orders.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException("Buyurtma #" + orderId + " topilmadi"));
        OrderStatus s = o.getStatus();
        OrderEvent.Kind kind = OrderEvent.Kind.STATUS_CHANGED;

        switch (action) {
            case ACCEPT -> {
                require(s == OrderStatus.NEW, s);
                o.setStatus(OrderStatus.CONFIRMED);
            }
            case REJECT -> {
                require(!s.isFinal(), s);
                o.setStatus(OrderStatus.CANCELLED);
            }
            case PREPARING -> {
                require(s == OrderStatus.CONFIRMED, s);
                o.setStatus(OrderStatus.PREPARING);
            }
            case ON_WAY -> {
                require(s == OrderStatus.PREPARING && o.getDeliveryType() == DeliveryType.DELIVERY, s);
                o.setStatus(OrderStatus.ON_THE_WAY);
                o.markOnWay();
            }
            case READY -> {
                require(s == OrderStatus.PREPARING && o.getDeliveryType() == DeliveryType.PICKUP, s);
                o.setStatus(OrderStatus.READY);
            }
            case DONE -> {
                require(s == OrderStatus.ON_THE_WAY || s == OrderStatus.READY, s);
                o.setStatus(OrderStatus.DELIVERED);
                o.setDeliveredBy("ADMIN");
                o.setNextAskAt(null);
                if (o.getPaymentStatus() == PaymentStatus.PAY_ON_DELIVERY) {
                    o.setPaymentStatus(PaymentStatus.PAID);
                }
            }
            case PAY_OK -> {
                if (o.getPaymentStatus() == PaymentStatus.PAID) {
                    throw new BusinessException("Tölov allaqaçon tasdiqlangan");
                }
                require(o.getPaymentStatus() != PaymentStatus.PAY_ON_DELIVERY, s);
                o.setPaymentStatus(PaymentStatus.PAID);
                kind = OrderEvent.Kind.PAYMENT_CONFIRMED;
            }
            case PAY_NO -> {
                if (o.getPaymentStatus() != PaymentStatus.RECEIPT_SENT) {
                    throw new BusinessException("Bu çek allaqaçon körib çiqilgan");
                }
                o.setPaymentStatus(PaymentStatus.AWAITING_RECEIPT);
                kind = OrderEvent.Kind.PAYMENT_REJECTED;
            }
        }
        OrderDetails details = OrderDetails.of(o);
        events.publishEvent(OrderEvent.of(kind, details));
        return details;
    }

    private static void require(boolean ok, OrderStatus current) {
        if (!ok) {
            throw new BusinessException("Bu amal mumkin emas. Hozirgi holat: " + current.label());
        }
    }

    @Transactional(readOnly = true)
    public OrderStats stats() {
        LocalDate today = LocalDate.now(props.zone());
        Instant dayStart = today.atStartOfDay(props.zone()).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(props.zone()).toInstant();
        long active = orders.countByStatusNotInAndAdminNotifiedTrue(EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED));
        List<OrderStats.TopProduct> top = orders.topProductsSince(monthStart, PageRequest.of(0, 3)).stream()
                .map(r -> new OrderStats.TopProduct((String) r[0], num(r[1]))).toList();
        return new OrderStats(period(dayStart), period(monthStart), active, top);
    }

    /** [from, to) davr hisoboti (faqat admin ko'rgan buyurtmalar). */
    @Transactional(readOnly = true)
    public OrderStats.Report report(Instant from, Instant to) {
        Object[] r = orders.statsBetween(from, to).getFirst();
        List<OrderStats.TopProduct> top = orders.topProductsBetween(from, to, PageRequest.of(0, 5)).stream()
                .map(x -> new OrderStats.TopProduct((String) x[0], num(x[1]))).toList();
        return new OrderStats.Report(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5]), top);
    }

    /** [from, to) davr buyurtmalari — Excel fayl uchun. */
    @Transactional(readOnly = true)
    public List<OrderDetails> ordersBetween(Instant from, Instant to) {
        return orders.findAllByAdminNotifiedTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to)
                .stream().map(OrderDetails::of).toList();
    }

    private OrderStats.Period period(Instant from) {
        Object[] r = orders.periodStats(from).getFirst();
        return new OrderStats.Period(num(r[0]), num(r[1]), num(r[2]));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
