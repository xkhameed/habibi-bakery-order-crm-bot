package uz.habibi.bakery.service;

import java.util.List;

/**
 * Mini App menyusi.
 * trending — shu oyning eng ko'p sotilganlari (sold — shu oyda necha dona sotilgan; sotuv bo'lmasa «Hit»lar, sold = 0).
 * mostLiked — eng ko'p like olgan mahsulotlar (count — like soni).
 */
public record MenuView(List<CategoryView> categories, List<Ranked> trending, List<Ranked> mostLiked) {

    public record CategoryView(Long id, String name, String emoji, List<ProductView> products, boolean isNew) {
    }

    /** discount — aksiya foizi (0 — aksiya yo'q); likes — like soni. */
    public record ProductView(Long id, String name, String description, String emoji, String photoUrl,
                              boolean available, boolean signature, int discount, long likes, List<VariantView> variants,
                              String contents, double rating, long ratingCount, boolean isNew, boolean perPiece) {
    }

    /** price — chegirmali (to'lanadigan) narx; oldPrice — aksiya bo'lsa asl narx, aks holda null. */
    public record VariantView(Long id, String name, long price, Long oldPrice) {
    }

    public record Ranked(Long id, long count) {
    }
}
