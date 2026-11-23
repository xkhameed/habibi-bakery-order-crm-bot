package uz.habibi.bakery.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.habibi.bakery.domain.ProductLike;

public interface ProductLikeRepository extends JpaRepository<ProductLike, Long> {
    Optional<ProductLike> findByProductIdAndTelegramId(Long productId, Long telegramId);

    long countByProductId(Long productId);

    List<ProductLike> findAllByTelegramId(Long telegramId);

    /** [productId, like soni] */
    @Query("select l.productId, count(l) from ProductLike l group by l.productId")
    List<Object[]> countsByProduct();
}
