package uz.habibi.bakery.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.habibi.bakery.domain.Order;
import uz.habibi.bakery.domain.OrderStatus;
import uz.habibi.bakery.domain.PaymentStatus;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"items", "customer"})
    Optional<Order> findWithItemsById(Long id);

    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findTop20ByCustomerTelegramIdOrderByCreatedAtDesc(Long telegramId);

    /** Bugun bekor qilinganlar (bekor qilingan payti — updatedAt). */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByStatusAndUpdatedAtAfterOrderByUpdatedAtDesc(OrderStatus status, Instant after);

    /** Shu paytdan keyin kelgan cheklar (rasmi saqlangan). */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByReceiptFileIdNotNullAndReceiptAtAfterOrderByReceiptAtAsc(Instant after);

    /** Mijoz sotib olgan (to'langan, bekor qilinmagan) mahsulotlar — baho berish uchun. */
    @Query("select distinct i.productId from OrderItem i where i.order.customer.telegramId = :telegramId "
            + "and i.order.paymentStatus = uz.habibi.bakery.domain.PaymentStatus.PAID "
            + "and i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED")
    List<Long> purchasedProductIds(Long telegramId);

    /** [o'rtacha baho, baholar soni] — [from, to) oralig'ida. */
    @Query("select avg(o.rating), count(o.rating) from Order o where o.rating is not null and o.createdAt >= :from and o.createdAt < :to")
    List<Object[]> ratingBetween(Instant from, Instant to);

    /** Kategoriyalar mashhurligi: [kategoriya id, buyurtmalar soni] — admin ko'rgan, bekor qilinmagan, shu paytdan beri. */
    @Query("select p.category.id, count(distinct i.order.id) from OrderItem i, Product p "
            + "where p.id = i.productId and i.order.createdAt >= :from and i.order.adminNotified = true "
            + "and i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED group by p.category.id")
    List<Object[]> categoryPopularity(Instant from);

    /** Baholangan buyurtmalar (eng yangisi birinchi). */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByRatingNotNullOrderByCreatedAtDesc();

    /** Yo'ldagi buyurtmalar (mijozdan so'rash va avtomatik yopish uchun). */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByStatus(OrderStatus status);

    /** Qidiruv uchun oxirgi buyurtmalar. */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findTop500ByAdminNotifiedTrueOrderByCreatedAtDesc();

    /** Chek kelgan, lekin admin hali tasdiqlamagan buyurtmalar. */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByAdminNotifiedTrueAndPaymentStatusAndStatusNotOrderByCreatedAtAsc(PaymentStatus paymentStatus, OrderStatus excluded);

    @EntityGraph(attributePaths = {"items", "customer"})
    Optional<Order> findFirstByCustomerTelegramIdAndPaymentStatusInAndStatusNotOrderByCreatedAtDesc(
            Long telegramId, Collection<PaymentStatus> paymentStatuses, OrderStatus excludedStatus);

    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByStatusNotInOrderByDeliveryDateAscCreatedAtAsc(Collection<OrderStatus> statuses);

    /** [bekor qilinmaganlar soni, bekor qilinganlar soni, bekor qilinmaganlar summasi] — bitta so'rovda. */
    @Query("select "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status = uz.habibi.bakery.domain.OrderStatus.CANCELLED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then o.itemsTotal else 0 end), 0) "
            + "from Order o where o.createdAt >= :from and o.adminNotified = true")
    List<Object[]> periodStats(Instant from);

    /** [jami, bekor qilinmagan, bekor, tushum, to'langan summa, to'langanlar soni] — [from, to) oralig'ida. */
    @Query("select count(o), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status = uz.habibi.bakery.domain.OrderStatus.CANCELLED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then o.itemsTotal else 0 end), 0), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "and o.paymentStatus = uz.habibi.bakery.domain.PaymentStatus.PAID then o.itemsTotal else 0 end), 0), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "and o.paymentStatus = uz.habibi.bakery.domain.PaymentStatus.PAID then 1 else 0 end), 0) "
            + "from Order o where o.createdAt >= :from and o.createdAt < :to and o.adminNotified = true")
    List<Object[]> statsBetween(Instant from, Instant to);

    @Query("select i.productName, sum(i.quantity) from OrderItem i "
            + "where i.order.createdAt >= :from and i.order.createdAt < :to "
            + "and i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED and i.order.adminNotified = true "
            + "group by i.productName order by sum(i.quantity) desc")
    List<Object[]> topProductsBetween(Instant from, Instant to, org.springframework.data.domain.Pageable page);

    /** Excel fayl uchun: oraliqdagi admin ko'rgan buyurtmalar. */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByAdminNotifiedTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(Instant from, Instant to);

    long countByStatusNotInAndAdminNotifiedTrue(Collection<OrderStatus> statuses);

    /** Chek kelgan (yoki naqd), 5 daqiqasi o'tgan, lekin hali adminga yuborilmagan buyurtmalar. */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByAdminNotifiedFalseAndPaymentStatusInAndStatusNotAndCreatedAtBefore(
            Collection<PaymentStatus> paymentStatuses, OrderStatus excludedStatus, Instant before);

    /** Chek kelmagan va muddati o'tgan buyurtmalar (avtomatik bekor qilish uchun). */
    @EntityGraph(attributePaths = {"items", "customer"})
    List<Order> findAllByPaymentStatusAndStatusAndCreatedAtBefore(
            PaymentStatus paymentStatus, OrderStatus status, Instant before);

    /** Eng ko'p sotilgan mahsulotlar (bekorlarsiz): [productId, jami dona]. */
    @Query("select i.productId, sum(i.quantity) from OrderItem i "
            + "where i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "group by i.productId order by sum(i.quantity) desc")
    List<Object[]> bestSellers(org.springframework.data.domain.Pageable page);

    /** Sotilgan dona (to'langan, bekor qilinmagan): [productId, jami dona]. */
    @Query("select i.productId, sum(i.quantity) from OrderItem i "
            + "where i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "and i.order.paymentStatus = uz.habibi.bakery.domain.PaymentStatus.PAID group by i.productId")
    List<Object[]> soldPaidByProduct();

    /** Berilgan vaqtdan beri eng ko'p sotilganlar (bekorlarsiz): [productId, jami dona]. */
    @Query("select i.productId, sum(i.quantity) from OrderItem i "
            + "where i.order.createdAt >= :from and i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "group by i.productId order by sum(i.quantity) desc")
    List<Object[]> bestSellersSince(Instant from, org.springframework.data.domain.Pageable page);

    /** Eng ko'p sotilgan mahsulotlar: [nomi, jami dona]. */
    @Query("select i.productName, sum(i.quantity) from OrderItem i "
            + "where i.order.createdAt >= :from and i.order.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "and i.order.adminNotified = true "
            + "group by i.productName order by sum(i.quantity) desc")
    List<Object[]> topProductsSince(Instant from, org.springframework.data.domain.Pageable page);
}
