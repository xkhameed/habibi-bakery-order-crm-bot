package uz.habibi.bakery.domain;

public enum PaymentStatus {
    AWAITING_RECEIPT("⏳ Çek kutilmoqda"),
    RECEIPT_SENT("🧾 Çek yuborildi, tekşirilmoqda"),
    PAID("✅ Tölangan"),
    PAY_ON_DELIVERY("💵 Qabul qilganda tölanadi");

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
