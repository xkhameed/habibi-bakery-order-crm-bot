package uz.habibi.bakery.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.habibi.bakery.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByTelegramId(Long telegramId);

    /**
     * Mijozlar va ularning buyurtmalari bo'yicha yig'ma: [Customer, buyurtmalar soni (bekorlarsiz),
     * jami summa (bekorlarsiz), oxirgi buyurtma vaqti, to'langan summa]. Saralash CustomerService'da.
     */
    @Query("select c, "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then 1 else 0 end), 0), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED then o.itemsTotal else 0 end), 0), "
            + "max(o.createdAt), "
            + "coalesce(sum(case when o.status <> uz.habibi.bakery.domain.OrderStatus.CANCELLED "
            + "and o.paymentStatus = uz.habibi.bakery.domain.PaymentStatus.PAID then o.itemsTotal else 0 end), 0) "
            + "from Customer c left join Order o on o.customer = c "
            + "group by c")
    List<Object[]> summaries();
}
