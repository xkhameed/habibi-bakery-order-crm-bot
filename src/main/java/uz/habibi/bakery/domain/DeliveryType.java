package uz.habibi.bakery.domain;

public enum DeliveryType {
    DELIVERY("🚕 Yetkazib beriş"),
    PICKUP("🏠 Olib ketiş");

    private final String label;

    DeliveryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
