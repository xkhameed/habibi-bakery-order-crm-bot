package uz.habibi.bakery.service;

/**
 * Buyurtma bilan nima bo'lganini bot xabarchisiga yetkazadi (tranzaksiya commit bo'lgandan keyin).
 * Chek: botga yuborilgan bo'lsa receiptFileId, Mini App'da yuklangan bo'lsa receiptBytes.
 */
public record OrderEvent(Kind kind, OrderDetails order, String receiptFileId, byte[] receiptBytes) {

    public enum Kind {
        CREATED, STATUS_CHANGED, RECEIPT_SENT, PAYMENT_CONFIRMED, PAYMENT_REJECTED,
        /** Mijozga «chek qabul qilindi» (eski tartibdagi buyurtmalar uchun). */
        RECEIPT_HELD,
        /** Buyurtma (chek bilan birga) endi adminga yuboriladi. */
        RELEASED,
        /** Eski: mijoz o'zi bekor qildi (endi ishlatilmaydi). */
        CUSTOMER_CANCELLED,
        /** 30 daqiqa ichida chek kelmadi — avtomatik bekor (mijoz va adminlar xabardor). */
        EXPIRED,
        /** Mijozdan «buyurtmangiz yetib keldimi?» deb so'raladi. */
        DELIVERY_ASK,
        /** Mijoz «hali kelmadi» dedi — adminlarga signal. */
        NOT_ARRIVED,
        /** Mijoz «qabul qildim» dedi — buyurtma topshirildi. */
        RECEIVED,
        /** Mijoz javob bermadi — 3 soatdan keyin avtomatik topshirildi. */
        AUTO_DELIVERED,
        /** Mijoz baho berdi (past baho bo'lsa — adminlarga signal). */
        RATED,
        /** Mijoz past bahoga izoh yozdi — adminlarga. */
        RATING_COMMENT
    }

    public static OrderEvent of(Kind kind, OrderDetails order) {
        return new OrderEvent(kind, order, null, null);
    }
}
