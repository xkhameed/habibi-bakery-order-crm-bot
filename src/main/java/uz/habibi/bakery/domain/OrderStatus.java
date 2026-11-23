package uz.habibi.bakery.domain;

public enum OrderStatus {
    NEW("🆕 Yangi"),
    CONFIRMED("✅ Qabul qilindi"),
    PREPARING("👩‍🍳 Tayyorlanmoqda"),
    ON_THE_WAY("🚕 Yölda"),
    READY("📦 Olib ketişga tayyor"),
    DELIVERED("🎉 Topşirildi"),
    CANCELLED("❌ Bekor qilindi");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
