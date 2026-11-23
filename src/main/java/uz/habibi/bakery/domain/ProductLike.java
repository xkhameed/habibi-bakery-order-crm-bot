package uz.habibi.bakery.domain;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "product_like")
public class ProductLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private Long telegramId;
    private Instant createdAt;

    protected ProductLike() {
    }

    public ProductLike(Long productId, Long telegramId) {
        this.productId = productId;
        this.telegramId = telegramId;
        this.createdAt = Instant.now();
    }

    public Long getProductId() { return productId; }
}
