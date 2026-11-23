package uz.habibi.bakery.domain;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

@Entity
@Table(name = "product")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id")
    private Category category;

    private String name;
    private String description;
    private String nameRu;
    private String descriptionRu;
    /** Set tarkibi — har qatorda bittadan. */
    private String contents;
    private String contentsRu;
    private String emoji;
    private String photoFileId;
    private boolean available = true;
    private boolean signature;
    private int discountPercent;
    private int sortOrder;
    /** O'chirilgan — menyuda ko'rinmaydi, eski buyurtmalar uchun saqlanadi. */
    private boolean archived;
    private java.time.Instant createdAt;
    /** Donada sotiladi — narx yonida «/ dona». */
    private boolean perPiece;

    @OneToMany(mappedBy = "product")
    @OrderBy("sortOrder")
    private List<ProductVariant> variants = new ArrayList<>();

    public Long getId() { return id; }
    public Category getCategory() { return category; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getNameRu() { return nameRu; }
    public String getDescriptionRu() { return descriptionRu; }
    public String getContents() { return contents; }
    public void setContents(String contents) { this.contents = contents; }
    public String getContentsRu() { return contentsRu; }
    public String getEmoji() { return emoji; }
    public String getPhotoFileId() { return photoFileId; }
    public void setPhotoFileId(String photoFileId) { this.photoFileId = photoFileId; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public boolean isSignature() { return signature; }
    public int getSortOrder() { return sortOrder; }
    public int getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(int discountPercent) { this.discountPercent = discountPercent; }

    /** Aksiyani hisobga olgan narx (100 so'mgacha yaxlitlanadi). */
    public long priceOf(ProductVariant v) {
        if (discountPercent <= 0) {
            return v.getPrice();
        }
        return Math.round(v.getPrice() * (100 - discountPercent) / 100.0 / 100.0) * 100;
    }
    public List<ProductVariant> getVariants() { return variants; }
    public boolean isArchived() { return archived; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public boolean isPerPiece() { return perPiece; }
    public void setPerPiece(boolean perPiece) { this.perPiece = perPiece; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public void setCategory(Category category) { this.category = category; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
