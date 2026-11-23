package uz.habibi.bakery.domain;

import java.time.Instant;

import jakarta.persistence.*;

/** Mijozning mahsulotga bergan bahosi (1–5 yulduz). */
@Entity
@Table(name = "product_rating")
public class ProductRating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private Long telegramId;
    private short stars;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProductRating() {
    }

    public ProductRating(Long productId, Long telegramId, int stars) {
        this.productId = productId;
        this.telegramId = telegramId;
        this.stars = (short) stars;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public Long getProductId() { return productId; }
    public int getStars() { return stars; }

    public void setStars(int stars) {
        this.stars = (short) stars;
        this.updatedAt = Instant.now();
    }
}
