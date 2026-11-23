package uz.habibi.bakery.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import uz.habibi.bakery.domain.*;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;

/** Tranzaksiyadan tashqarida (bot, API) xavfsiz ishlatiladigan buyurtma nusxasi. */
public record OrderDetails(
        Long id,
        Long customerTelegramId,
        String customerUsername,
        Lang customerLanguage,
        OrderStatus status,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        DeliveryType deliveryType,
        String customerName,
        String phone,
        String address,
        Double latitude,
        Double longitude,
        LocalDate deliveryDate,
        String deliveryTime,
        String comment,
        long itemsTotal,
        Integer adminMessageId,
        Instant createdAt,
        List<Line> items,
        boolean adminNotified,
        String inscription,
        String adminCopies,
        String receiptFileId,
        String trackingUrl,
        String deliveredBy,
        Instant notArrivedAt,
        Integer rating,
        String ratingComment) {

    public record Line(Long productId, String productName, String variantName, String productNameRu,
                       String variantNameRu, long price, int quantity) {
        public long total() {
            return price * quantity;
        }

        public String name(Lang lang) {
            return I18n.content(lang, productName, productNameRu);
        }

        public String variant(Lang lang) {
            return I18n.content(lang, variantName, variantNameRu);
        }
    }

    /** Mijoz hozir o'zi bekor qila oladimi: 5 daqiqa o'tmagan, admin hali bilmaydi, chek yuborilmagan. */
    public boolean customerCanCancel(Instant now) {
        return status == OrderStatus.NEW && !adminNotified && paymentStatus == PaymentStatus.AWAITING_RECEIPT
                && now.isBefore(cancelDeadline());
    }

    /** Qo'shimcha adminlarga yuborilgan kartochkalar: chatId → messageId. */
    public java.util.Map<Long, Integer> adminCopyMap() {
        java.util.Map<Long, Integer> map = new java.util.LinkedHashMap<>();
        if (adminCopies != null) {
            for (String pair : adminCopies.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    try {
                        map.put(Long.parseLong(kv[0]), Integer.parseInt(kv[1]));
                    } catch (NumberFormatException ignored) {
                        // buzilgan yozuv
                    }
                }
            }
        }
        return map;
    }

    /** Mijozning «Buyurtmalarim» filtri uchun guruh. */
    public enum Group { PENDING, CONFIRMED, CANCELLED }

    public Group group() {
        if (status == OrderStatus.CANCELLED) {
            return Group.CANCELLED;
        }
        return paymentStatus == PaymentStatus.AWAITING_RECEIPT || paymentStatus == PaymentStatus.RECEIPT_SENT
                ? Group.PENDING : Group.CONFIRMED;
    }

    public Instant cancelDeadline() {
        return createdAt.plus(Order.CANCEL_WINDOW);
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null;
    }

    public String yandexMapUrl() {
        return "https://yandex.uz/maps/?pt=" + longitude + "," + latitude + "&z=17&l=map";
    }

    public String googleMapUrl() {
        return "https://maps.google.com/?q=" + latitude + "," + longitude;
    }

    public static OrderDetails of(Order o) {
        return new OrderDetails(o.getId(), o.getCustomer().getTelegramId(), o.getCustomer().getUsername(), Lang.fromCode(o.getCustomer().getLanguage()),
                o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(), o.getDeliveryType(),
                o.getCustomerName(), o.getPhone(), o.getAddress(), o.getLatitude(), o.getLongitude(), o.getDeliveryDate(), o.getDeliveryTime(),
                o.getComment(), o.getItemsTotal(), o.getAdminMessageId(), o.getCreatedAt(),
                o.getItems().stream()
                        .map(i -> new Line(i.getProductId(), i.getProductName(), i.getVariantName(),
                                i.getProductNameRu(), i.getVariantNameRu(), i.getPrice(), i.getQuantity()))
                        .toList(),
                o.isAdminNotified(), o.getInscription(), o.getAdminCopies(), o.getReceiptFileId(), o.getTrackingUrl(), o.getDeliveredBy(), o.getNotArrivedAt(), o.getRating(), o.getRatingComment());
    }
}
