package uz.habibi.bakery.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "category")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String nameRu;
    private String emoji;
    private int sortOrder;
    private boolean active = true;
    private java.time.Instant createdAt;
    private boolean deleted;

    protected Category() {
    }

    public Category(String name, String emoji, int sortOrder) {
        this.name = name;
        this.emoji = emoji;
        this.sortOrder = sortOrder;
        this.active = true;
        this.createdAt = java.time.Instant.now();
    }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setName(String name) { this.name = name; }
    public void setActive(boolean active) { this.active = active; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameRu() { return nameRu; }
    public String getEmoji() { return emoji; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
