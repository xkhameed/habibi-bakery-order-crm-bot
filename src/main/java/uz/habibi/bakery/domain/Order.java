package uz.habibi.bakery.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus;
    @Enumerated(EnumType.STRING)
    private DeliveryType deliveryType;

    private String customerName;
    private String phone;
    private String address;
    private Double latitude;
    private Double longitude;
    private LocalDate deliveryDate;
    private String deliveryTime;
    private String comment;
    private String inscription;
    private long itemsTotal;
    private Integer adminMessageId;
    private String adminCopies;
    /** false — hali adminga yuborilmagan (bekor qilish vaqti o'tmagan yoki chek kelmagan). */
    private boolean adminNotified = true;
    private String heldReceiptFileId;
    private String receiptFileId;
    private String trackingUrl;
    private Instant onWayAt;
    private Instant nextAskAt;
    private int deliveryAsks;
    private Instant notArrivedAt;
    private String deliveredBy;
    private Short rating;
    private String ratingComment;
    private Instant receiptAt;
    private String heldReceiptImage;
    private Instant createdAt;
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    /** Mijoz buyurtmani shu vaqt ichida o'zi bekor qila oladi; shu vaqt o'tmaguncha admin bilmaydi. */
    public static final java.time.Duration CANCEL_WINDOW = java.time.Duration.ofMinutes(5);
    /** Shu vaqt ichida chek kelmasa — buyurtma avtomatik bekor qilinadi. */
    public static final java.time.Duration RECEIPT_TIMEOUT = java.time.Duration.ofMinutes(30);

    protected Order() {
    }

    public boolean isAdminNotified() { return adminNotified; }
    public void setAdminNotified(boolean adminNotified) { this.adminNotified = adminNotified; }
    public String getHeldReceiptFileId() { return heldReceiptFileId; }
    public String getReceiptFileId() { return receiptFileId; }
    public void setReceiptFileId(String receiptFileId) {
        this.receiptFileId = receiptFileId;
        this.receiptAt = Instant.now();
    }
    public Instant getReceiptAt() { return receiptAt; }
    public String getTrackingUrl() { return trackingUrl; }
    public Instant getOnWayAt() { return onWayAt; }
    public Instant getNextAskAt() { return nextAskAt; }
    public void setNextAskAt(Instant nextAskAt) { this.nextAskAt = nextAskAt; }
    public int getDeliveryAsks() { return deliveryAsks; }
    public void setDeliveryAsks(int deliveryAsks) { this.deliveryAsks = deliveryAsks; }
    public Instant getNotArrivedAt() { return notArrivedAt; }
    public void setNotArrivedAt(Instant notArrivedAt) { this.notArrivedAt = notArrivedAt; }
    public String getDeliveredBy() { return deliveredBy; }
    public Integer getRating() { return rating == null ? null : rating.intValue(); }
    public void setRating(int rating) { this.rating = (short) rating; }
    public String getRatingComment() { return ratingComment; }
    public void setRatingComment(String ratingComment) { this.ratingComment = ratingComment; }
    public void setDeliveredBy(String deliveredBy) { this.deliveredBy = deliveredBy; }

    /** Mijozdan «yetib keldimi?» deb birinchi so'rash — yo'lga chiqqandan shuncha keyin. */
    public static final java.time.Duration FIRST_ASK = java.time.Duration.ofMinutes(45);
    /** Javob bo'lmasa — eslatma. */
    public static final java.time.Duration SECOND_ASK = java.time.Duration.ofMinutes(90);
    /** Javob bo'lmasa — avtomatik «Topshirildi». */
    public static final java.time.Duration AUTO_DELIVER = java.time.Duration.ofHours(3);

    public void markOnWay() {
        this.onWayAt = Instant.now();
        this.nextAskAt = onWayAt.plus(FIRST_ASK);
        this.deliveryAsks = 0;
        this.notArrivedAt = null;
    }
    public void setTrackingUrl(String trackingUrl) { this.trackingUrl = trackingUrl; }
    public String getHeldReceiptImage() { return heldReceiptImage; }
    public void holdReceipt(String fileId, String imageBase64) {
        this.heldReceiptFileId = fileId;
        this.heldReceiptImage = imageBase64;
    }
    public Instant cancelDeadline() { return createdAt.plus(CANCEL_WINDOW); }

    public Order(Customer customer) {
        this.customer = customer;
        this.status = OrderStatus.NEW;
        this.adminNotified = false;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void addItem(ProductVariant variant, int quantity) {
        items.add(new OrderItem(this, variant, quantity));
        itemsTotal = items.stream().mapToLong(OrderItem::lineTotal).sum();
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
        touch();
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public OrderStatus getStatus() { return status; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public DeliveryType getDeliveryType() { return deliveryType; }
    public void setDeliveryType(DeliveryType deliveryType) { this.deliveryType = deliveryType; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public void setLocation(Double latitude, Double longitude) { this.latitude = latitude; this.longitude = longitude; }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public String getDeliveryTime() { return deliveryTime; }
    public void setDeliveryTime(String deliveryTime) { this.deliveryTime = deliveryTime; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getInscription() { return inscription; }
    public void setInscription(String inscription) { this.inscription = inscription; }
    public long getItemsTotal() { return itemsTotal; }
    public Integer getAdminMessageId() { return adminMessageId; }
    public void setAdminMessageId(Integer adminMessageId) { this.adminMessageId = adminMessageId; }
    public String getAdminCopies() { return adminCopies; }
    public void setAdminCopies(String adminCopies) { this.adminCopies = adminCopies; }
    public Instant getCreatedAt() { return createdAt; }
    public List<OrderItem> getItems() { return items; }
}
