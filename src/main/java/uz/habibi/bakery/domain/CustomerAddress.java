package uz.habibi.bakery.domain;

import java.time.Instant;

import jakarta.persistence.*;

/** Mijozning saqlangan manzili: nomi (Uy, Ish...), xaritadagi nuqta va ko'cha/izoh. */
@Entity
@Table(name = "customer_address")
public class CustomerAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long telegramId;
    private String label;
    private double latitude;
    private double longitude;
    private String mapLabel;
    private String address;
    private Instant createdAt;

    protected CustomerAddress() {
    }

    public CustomerAddress(Long telegramId) {
        this.telegramId = telegramId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTelegramId() { return telegramId; }
    public String getLabel() { return label; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getMapLabel() { return mapLabel; }
    public String getAddress() { return address; }

    public void update(String label, double latitude, double longitude, String mapLabel, String address) {
        this.label = label;
        this.latitude = latitude;
        this.longitude = longitude;
        this.mapLabel = mapLabel;
        this.address = address;
    }
}
