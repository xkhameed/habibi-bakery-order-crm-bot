package uz.habibi.bakery.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.ProductRating;
import uz.habibi.bakery.repo.OrderRepository;
import uz.habibi.bakery.repo.ProductRatingRepository;
import uz.habibi.bakery.repo.ProductRepository;

/** Mahsulotlarga 1–5 yulduzli baho. Faqat mahsulotni sotib olgan (to'lovi tasdiqlangan) mijoz baho bera oladi. */
@Service
public class RatingService {

    private final ProductRatingRepository ratings;
    private final ProductRepository products;
    private final OrderRepository orders;

    public RatingService(ProductRatingRepository ratings, ProductRepository products, OrderRepository orders) {
        this.ratings = ratings;
        this.products = products;
        this.orders = orders;
    }

    /** O'rtacha baho (1 xona aniqlikda) va baholar soni. */
    public record Stat(double avg, long count) {
    }

    public record RateResult(Long productId, int mine, double avg, long count) {
    }

    /** Mijozning baholari va baho bera oladigan (sotib olgan) mahsulotlari. */
    public record Mine(Map<Long, Integer> ratings, Set<Long> purchased) {
    }

    @Transactional
    public RateResult rate(long telegramId, Long productId, int stars) {
        if (stars < 1 || stars > 5) {
            throw new BusinessException("err.rating_range");
        }
        if (!products.existsById(productId)) {
            throw new BusinessException("err.not_found");
        }
        if (!orders.purchasedProductIds(telegramId).contains(productId)) {
            throw new BusinessException("err.rating_not_bought");
        }
        ratings.findByProductIdAndTelegramId(productId, telegramId)
                .ifPresentOrElse(r -> r.setStars(stars), () -> ratings.save(new ProductRating(productId, telegramId, stars)));
        ratings.flush();
        Stat s = stats().getOrDefault(productId, new Stat(stars, 1));
        return new RateResult(productId, stars, s.avg(), s.count());
    }

    @Transactional(readOnly = true)
    public Map<Long, Stat> stats() {
        Map<Long, Stat> map = new HashMap<>();
        for (Object[] r : ratings.stats()) {
            double avg = Math.round(((Number) r[1]).doubleValue() * 10) / 10.0;
            map.put(((Number) r[0]).longValue(), new Stat(avg, ((Number) r[2]).longValue()));
        }
        return map;
    }

    @Transactional(readOnly = true)
    public Mine mine(long telegramId) {
        Map<Long, Integer> mine = new HashMap<>();
        for (ProductRating r : ratings.findAllByTelegramId(telegramId)) {
            mine.put(r.getProductId(), r.getStars());
        }
        return new Mine(mine, new HashSet<>(orders.purchasedProductIds(telegramId)));
    }
}
