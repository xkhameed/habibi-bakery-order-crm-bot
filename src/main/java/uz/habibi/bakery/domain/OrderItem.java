package uz.habibi.bakery.domain;

import jakarta.persistence.*;

/** Buyurtma paytidagi nom va narx saqlanadi, keyin narx o'zgarsa ham eski buyurtma buzilmaydi. */
@Entity
@Table(name = "order_item")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    private Long productId;
    private String productName;
    private String variantName;
    private String productNameRu;
    private String variantNameRu;
    private long price;
    private int quantity;

    protected OrderItem() {
    }

    public OrderItem(Order order, ProductVariant variant, int quantity) {
        this.order = order;
        this.productId = variant.getProduct().getId();
        this.productName = variant.getProduct().getName();
        this.variantName = variant.getName();
        this.productNameRu = variant.getProduct().getNameRu();
        this.variantNameRu = variant.getNameRu();
        this.price = variant.getProduct().priceOf(variant); // aksiya bo'lsa — chegirmali narx
        this.quantity = quantity;
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getVariantName() { return variantName; }
    public String getProductNameRu() { return productNameRu; }
    public String getVariantNameRu() { return variantNameRu; }
    public long getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public long lineTotal() { return price * quantity; }
}
