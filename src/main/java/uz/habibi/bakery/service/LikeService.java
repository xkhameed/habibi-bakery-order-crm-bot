package uz.habibi.bakery.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.ProductLike;
import uz.habibi.bakery.repo.ProductLikeRepository;
import uz.habibi.bakery.repo.ProductRepository;

/** Mahsulotlarga like: bosilsa qo'yiladi, qayta bosilsa olinadi. */
@Service
public class LikeService {

    private final ProductLikeRepository likes;
    private final ProductRepository products;

    public LikeService(ProductLikeRepository likes, ProductRepository products) {
        this.likes = likes;
        this.products = products;
    }

    public record LikeResult(Long productId, boolean liked, long count) {
    }

    @Transactional
    public LikeResult toggle(long telegramId, Long productId) {
        if (!products.existsById(productId)) {
            throw new BusinessException("err.not_found");
        }
        var existing = likes.findByProductIdAndTelegramId(productId, telegramId);
        boolean liked;
        if (existing.isPresent()) {
            likes.delete(existing.get());
            liked = false;
        } else {
            likes.save(new ProductLike(productId, telegramId));
            liked = true;
        }
        likes.flush();
        return new LikeResult(productId, liked, likes.countByProductId(productId));
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> counts() {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] r : likes.countsByProduct()) {
            map.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        return map;
    }

    @Transactional(readOnly = true)
    public List<Long> likedBy(long telegramId) {
        return likes.findAllByTelegramId(telegramId).stream().map(ProductLike::getProductId).toList();
    }
}
