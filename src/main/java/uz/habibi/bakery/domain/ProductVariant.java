package uz.habibi.bakery.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "product_variant")
public class ProductVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Masalan "Butun", "Yarim". Bitta o'lchamli mahsulotda null. */
    private String name;
    private String nameRu;
    private long price;
    private int sortOrder;

    protected ProductVariant() {
    }

    public ProductVariant(Product product, String name, long price, int sortOrder) {
        this.product = product;
        this.name = name;
        this.price = price;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public String getName() { return name; }
    public String getNameRu() { return nameRu; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public int getSortOrder() { return sortOrder; }
}
