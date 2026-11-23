package uz.habibi.bakery.repo;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import uz.habibi.bakery.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = {"category", "variants"})
    List<Product> findAllByOrderByCategorySortOrderAscSortOrderAsc();
}
