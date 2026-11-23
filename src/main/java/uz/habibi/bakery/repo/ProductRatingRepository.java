package uz.habibi.bakery.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.habibi.bakery.domain.ProductRating;

public interface ProductRatingRepository extends JpaRepository<ProductRating, Long> {

    Optional<ProductRating> findByProductIdAndTelegramId(Long productId, Long telegramId);

    List<ProductRating> findAllByTelegramId(Long telegramId);

    /** [productId, o'rtacha baho, baholar soni] */
    @Query("select r.productId, avg(r.stars), count(r) from ProductRating r group by r.productId")
    List<Object[]> stats();
}
