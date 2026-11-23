package uz.habibi.bakery.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.habibi.bakery.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    /** Mijozlarga ko'rinadigan kategoriyalar. */
    List<Category> findAllByActiveTrueAndDeletedFalseOrderBySortOrder();

    /** Admin uchun: yashirilganlari ham (o'chirilganlaridan tashqari). */
    List<Category> findAllByDeletedFalseOrderBySortOrderAscIdAsc();
}
