package uz.habibi.bakery.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.habibi.bakery.domain.ProductVariant;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    @Query("select v from ProductVariant v join fetch v.product p join fetch p.category where v.id in :ids")
    List<ProductVariant> findAllWithProduct(Collection<Long> ids);
}
