package uz.habibi.bakery.domain;

/** Hozir faqat CARD_TRANSFER ishlatiladi; qolganlari eski buyurtmalar uchun saqlanadi. */
public enum PaymentMethod {
    /** Kartaga o'tkazma + chek rasmi. */
    CARD_TRANSFER("💳 Kartaga ötkazma"),
    CLICK("🔵 Click (QR)"),
    PAYME("🟢 Payme (QR)"),
    CASH("💵 Naqd (qabul qilganda)");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
