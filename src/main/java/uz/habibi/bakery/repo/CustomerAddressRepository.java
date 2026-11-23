package uz.habibi.bakery.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.habibi.bakery.domain.CustomerAddress;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    List<CustomerAddress> findAllByTelegramIdOrderByCreatedAtAsc(Long telegramId);

    long countByTelegramId(Long telegramId);
}
