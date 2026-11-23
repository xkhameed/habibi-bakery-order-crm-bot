package uz.habibi.bakery.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.domain.PaymentMethod;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.service.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final MenuService menuService;
    private final OrderService orderService;
    private final TelegramAuthService auth;
    private final ProductPhotoService photos;
    private final AppProperties props;
    private final CustomerService customers;
    @org.springframework.beans.factory.annotation.Autowired
    private HomeBannerService banner;
    @org.springframework.beans.factory.annotation.Autowired
    private LikeService likeService;
    @org.springframework.beans.factory.annotation.Autowired
    private uz.habibi.bakery.service.SettingsService settings;
    @org.springframework.beans.factory.annotation.Autowired
    private uz.habibi.bakery.service.RatingService ratings;

    public record RateRequest(int stars) {
    }

    @org.springframework.beans.factory.annotation.Autowired
    private uz.habibi.bakery.service.AddressService addressService;

    /** «Mening manzillarim». */
    @GetMapping("/me/addresses")
    public List<uz.habibi.bakery.service.AddressService.AddressView> addresses(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData) {
        return addressService.list(user(initData).id());
    }

    @PostMapping("/me/addresses")
    public uz.habibi.bakery.service.AddressService.AddressView addAddress(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @RequestBody uz.habibi.bakery.service.AddressService.AddressRequest req) {
        return addressService.save(user(initData).id(), null, req);
    }

    @PutMapping("/me/addresses/{id}")
    public uz.habibi.bakery.service.AddressService.AddressView updateAddress(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @PathVariable Long id, @RequestBody uz.habibi.bakery.service.AddressService.AddressRequest req) {
        return addressService.save(user(initData).id(), id, req);
    }

    @DeleteMapping("/me/addresses/{id}")
    public Map<String, Object> deleteAddress(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                             @PathVariable Long id) {
        addressService.delete(user(initData).id(), id);
        return Map.of("ok", true);
    }

    /** Mahsulotga 1–5 yulduz (faqat sotib olgan mijoz). */
    @PostMapping("/products/{id}/rating")
    public uz.habibi.bakery.service.RatingService.RateResult rate(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                                                  @PathVariable Long id, @RequestBody RateRequest req) {
        return ratings.rate(user(initData).id(), id, req.stars());
    }

    /** Mijozning baholari va baho bera oladigan mahsulotlari. */
    @GetMapping("/me/ratings")
    public uz.habibi.bakery.service.RatingService.Mine myRatings(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData) {
        return ratings.mine(user(initData).id());
    }

    public ApiController(MenuService menuService, OrderService orderService, TelegramAuthService auth,
                         ProductPhotoService photos, AppProperties props,
                         CustomerService customers) {
        this.customers = customers;
        this.menuService = menuService;
        this.orderService = orderService;
        this.auth = auth;
        this.photos = photos;
        this.props = props;
    }

    public record ShopConfig(String name, String deliveryNote, String phone, String botUsername,
                             List<String> timeSlots, int daysAhead, LocalDate today,
                             String telegram, String instagram, String groupUrl, String bannerUrl,
                             String openTime, String closeTime, boolean openNow, boolean paused) {
    }

    /** Telegram orqali tekshirilgan, bloklanmagan mijoz. */
    private TelegramUser user(String initData) {
        TelegramUser user = auth.authenticate(initData);
        if (customers.isBlocked(user.id())) {
            throw new ForbiddenException("err.blocked");
        }
        return user;
    }

    @GetMapping("/config")
    public ShopConfig config(@RequestParam(required = false) String lang) {
        var shop = settings.shop();
        return new ShopConfig(shop.name(), I18n.t(Lang.fromCode(lang), "shop.delivery_note"), shop.phone(), props.bot().username(),
                shop.timeSlots(), shop.daysAhead(), LocalDate.now(props.zone()),
                shop.telegram(), shop.instagram(), shop.groupUrl(), banner.url(),
                shop.opens().toString(), shop.closes().toString(),
                shop.isOpen(java.time.LocalTime.now(props.zone())), settings.isPaused());
    }

    @GetMapping("/menu")
    public MenuView menu(@RequestParam(required = false) String lang) {
        return menuService.menu(Lang.fromCode(lang));
    }

    /** Mini App matnlari (app.*) va umumiy so'zlar — tanlangan tilda. Kirill avtomatik o'giriladi. */
    @GetMapping("/i18n")
    public ResponseEntity<Map<String, String>> i18n(@RequestParam(required = false) String lang) {
        Lang l = Lang.fromCode(lang);
        Map<String, String> out = new TreeMap<>();
        for (Object k : I18n.raw(Lang.UZ).keySet()) {
            String key = (String) k;
            if (key.startsWith("app.") || key.equals("som") || key.equals("months") || key.equals("weekdays")
                    || key.equals("date.format") || key.startsWith("err.") || key.equals("bot.terms")) {
                out.put(key, I18n.t(l, key));
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(10))).body(out);
    }

    /** phone — botda ulashilgan raqam, +998 siz 9 ta raqam (Mini App'da avtomatik to'ldiriladi). */
    public record Me(String language, String phone, String name) {
    }

    public record LanguageRequest(String language) {
    }

    /** Mahsulotga like bosish / olish. */
    @PostMapping("/products/{id}/like")
    public LikeService.LikeResult like(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                       @PathVariable Long id) {
        return likeService.toggle(user(initData).id(), id);
    }


    /** Mijozning saqlangan tili (hali tanlamagan bo'lsa null). */
    @GetMapping("/me")
    public Me me(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData) {
        TelegramUser user = user(initData);
        String phone = customers.phone(user.id())
                .map(p -> p.replaceAll("\\D", ""))
                .map(p -> p.startsWith("998") && p.length() == 12 ? p.substring(3) : null)
                .orElse(null);
        return new Me(customers.language(user.id()).map(Lang::code).orElse(null), phone,
                customers.name(user.id()).orElse(null));
    }

    /** Mini App'da til o'zgartirilsa — bot xabarlari ham shu tilda keladi. */
    @PostMapping("/me/language")
    public Me setLanguage(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                          @RequestBody LanguageRequest request) {
        TelegramUser user = user(initData);
        Lang lang = Lang.fromCode(request.language());
        customers.setLanguage(user, lang);
        return new Me(lang.code(), null, null);
    }

    @GetMapping("/products/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        return photos.load(id)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                        .body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/banner")
    public ResponseEntity<byte[]> banner() {
        return photos.loadBanner()
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                        .body(bytes))
                .orElse(ResponseEntity.notFound().build());
    }

    public record PaymentInfo(String cardNumber, String cardHolder, long amount) {
    }

    /** cancelUntil — endi doim null (mijoz o'zi bekor qila olmaydi); eski Mini App bilan moslik uchun qoldirilgan. */
    public record CreatedOrder(Long id, long total, PaymentMethod paymentMethod, PaymentInfo payment, Long cancelUntil) {
    }

    @PostMapping("/orders")
    public CreatedOrder create(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                               @Valid @RequestBody CreateOrderRequest request) {
        TelegramUser user = user(initData);
        boolean isAdmin = settings.isAdmin(user.id());
        if (!props.devMode() && !isAdmin && !customers.hasAcceptedTerms(user.id())) {
            throw new BusinessException("err.terms_required");
        }
        if (!isAdmin && settings.isPaused()) {
            throw new BusinessException("err.paused");
        }
        var shop = settings.shop();
        if (!isAdmin && !shop.isOpen(java.time.LocalTime.now(props.zone()))) {
            throw new BusinessException("err.closed", shop.opens().toString(), shop.closes().toString());
        }
        OrderDetails order = orderService.create(user, request);
        PaymentInfo payment = order.paymentMethod() == PaymentMethod.CASH ? null
                : new PaymentInfo(settings.shop().cardNumber(), settings.shop().cardHolder(), order.itemsTotal());
        return new CreatedOrder(order.id(), order.itemsTotal(), order.paymentMethod(), payment, null);
    }

    public record ReceiptResult(Long orderId, String paymentStatus, String paymentLabel) {
    }

    private static final long MAX_RECEIPT_BYTES = 10L * 1024 * 1024;

    /** Mijoz to'lov chekini (skrinshot) Mini App ichida yuklaydi — u adminga bot orqali boradi. */
    @PostMapping(value = "/orders/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReceiptResult uploadReceipt(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                       @RequestHeader(value = "X-Lang", required = false) String lang,
                                       @PathVariable Long id,
                                       @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        TelegramUser user = user(initData);
        if (file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw new BusinessException("err.not_image");
        }
        if (file.getSize() > MAX_RECEIPT_BYTES) {
            throw new BusinessException("err.file_too_big");
        }
        OrderDetails o = orderService.uploadReceipt(user.id(), id, file.getBytes());
        return new ReceiptResult(o.id(), o.paymentStatus().name(),
                I18n.t(Lang.fromCode(lang), "payment." + o.paymentStatus().name()));
    }

    /** Eski Mini App uchun: mijoz o'zi bekor qila olmaydi — doim err.cancel_expired. */
    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancel(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                      @PathVariable Long id) {
        TelegramUser user = user(initData);
        OrderDetails o = orderService.cancelByCustomer(user.id(), id);
        return Map.of("id", o.id(), "status", o.status().name());
    }

    /** Mijoz Mini App'da «✅ Qabul qildim» bosdi. */
    @PostMapping("/orders/{id}/received")
    public Map<String, Object> received(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                        @PathVariable Long id) {
        OrderDetails o = orderService.customerReceived(user(initData).id(), id);
        return Map.of("id", o.id(), "status", o.status().name());
    }

    public record MyOrderItem(String productName, String variantName, int quantity, long total) {
    }

    /** canUploadReceipt — chek kutilayotgan bo'lsa, Mini App'da «Chek yuklash» tugmasi chiqadi. */
    public record MyOrder(Long id, String status, String statusLabel, boolean canUploadReceipt, String paymentLabel, String deliveryLabel,
                          LocalDate deliveryDate, String deliveryTime, long total, Instant createdAt,
                          List<MyOrderItem> items, Long cancelUntil, String group, String methodLabel, String trackingUrl,
                          boolean canConfirmReceived) {
    }

    @GetMapping("/orders/my")
    public List<MyOrder> myOrders(@RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
                                  @RequestParam(required = false) String lang) {
        TelegramUser user = user(initData);
        Lang l = Lang.fromCode(lang);
        return orderService.myOrders(user.id()).stream()
                .map(o -> new MyOrder(o.id(), o.status().name(),
                        I18n.t(l, "status." + o.status().name()),
                        o.status() != uz.habibi.bakery.domain.OrderStatus.CANCELLED
                                && (o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.AWAITING_RECEIPT
                                || o.paymentStatus() == uz.habibi.bakery.domain.PaymentStatus.RECEIPT_SENT),
                        I18n.t(l, "payment." + o.paymentStatus().name()),
                        I18n.t(l, "delivery." + o.deliveryType().name()),
                        o.deliveryDate(), o.deliveryTime(), o.itemsTotal(), o.createdAt(),
                        o.items().stream().map(i -> new MyOrderItem(i.name(l),
                                i.variantName() == null ? null : i.variant(l), i.quantity(), i.total())).toList(),
                        o.customerCanCancel(Instant.now()) ? o.cancelDeadline().toEpochMilli() : null,
                        o.group().name(), I18n.t(l, "method." + o.paymentMethod().name()),
                        o.status() == uz.habibi.bakery.domain.OrderStatus.ON_THE_WAY ? o.trackingUrl() : null,
                        o.status() == uz.habibi.bakery.domain.OrderStatus.ON_THE_WAY))
                .toList();
    }
}
