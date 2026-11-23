package uz.habibi.bakery.domain;

import java.time.Instant;

import jakarta.persistence.*;

@Entity
@Table(name = "customer")
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long telegramId;
    private String firstName;
    private String lastName;
    private String username;
    private String phone;
    private String language;
    private String name;
    private boolean phoneVerified;
    private boolean blocked;
    private Instant blockedAt;
    private Instant createdAt;
    private Instant termsAcceptedAt;

    protected Customer() {
    }

    public Customer(Long telegramId) {
        this.telegramId = telegramId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getTelegramId() { return telegramId; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isBlocked() { return blocked; }
    public Instant getBlockedAt() { return blockedAt; }
    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
        this.blockedAt = blocked ? Instant.now() : null;
    }
    public boolean isPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Instant getTermsAcceptedAt() { return termsAcceptedAt; }
    public void setTermsAcceptedAt(Instant termsAcceptedAt) { this.termsAcceptedAt = termsAcceptedAt; }
}
