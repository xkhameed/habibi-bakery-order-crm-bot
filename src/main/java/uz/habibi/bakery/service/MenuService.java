package uz.habibi.bakery.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.Category;
import uz.habibi.bakery.domain.Product;
import uz.habibi.bakery.domain.ProductVariant;
import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.repo.CategoryRepository;
import uz.habibi.bakery.repo.ProductRepository;
import uz.habibi.bakery.repo.ProductVariantRepository;

@Service
public class MenuService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final uz.habibi.bakery.repo.OrderRepository orders;
    @org.springframework.beans.factory.annotation.Autowired
    private LikeService likes;
    @org.springframework.beans.factory.annotation.Autowired
    private RatingService ratingService;

    public MenuService(CategoryRepository categories, ProductRepository products, ProductVariantRepository variants,
                       uz.habibi.bakery.repo.OrderRepository orders) {
        this.categories = categories;
        this.products = products;
        this.variants = variants;
        this.orders = orders;
    }

    private static final int TOP_LIMIT = 10;

    /** Qancha vaqt «Yangi» belgisi bilan turadi. */
    public static final java.time.Duration NEW_FOR = java.time.Duration.ofDays(5);

    /** Kategoriyalar mashhurligi shuncha vaqt ichidagi buyurtmalar bo'yicha hisoblanadi. */
    public static final java.time.Duration POPULAR_WINDOW = java.time.Duration.ofDays(30);

    static boolean isNew(java.time.Instant createdAt) {
        return createdAt != null && createdAt.isAfter(java.time.Instant.now().minus(NEW_FOR));
    }

    /** Butun / Yarim / Chorak bo'lib sotiladigan kategoriya (🎂 «Desertlar») — o'lchamlar faqat unda. */
    public static final List<String> CAKE_SIZES = List.of("Butun", "Yarim", "Çorak");

    /** 🎂 belgili kategoriya (nomi o'zgarsa ham), yoki nomida «tort» bo'lsa. */
    public static boolean isCakeCategory(Category c) {
        return c != null && ("🎂".equals(c.getEmoji())
                || (c.getName() != null && c.getName().toLowerCase(java.util.Locale.ROOT).contains("tort")));
    }

    /** Shu oyning eng ko'p sotilganlari; sotuv kam bo'lsa — «Hit» belgili mahsulotlar (sold = 0) bilan to'ldiriladi. */
    private List<MenuView.Ranked> trending(List<Product> all) {
        java.util.Set<Long> existing = new java.util.HashSet<>();
        all.forEach(p -> existing.add(p.getId()));
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Tashkent");
        java.time.Instant monthStart = java.time.LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        java.util.LinkedHashMap<Long, Long> ranked = new java.util.LinkedHashMap<>();
        for (Object[] r : orders.bestSellersSince(monthStart, org.springframework.data.domain.PageRequest.of(0, TOP_LIMIT))) {
            Long id = ((Number) r[0]).longValue();
            if (existing.contains(id)) {
                ranked.put(id, ((Number) r[1]).longValue());
            }
        }
        for (Product p : all) {
            if (ranked.size() >= TOP_LIMIT) {
                break;
            }
            if (p.isSignature()) {
                ranked.putIfAbsent(p.getId(), 0L);
            }
        }
        return ranked.entrySet().stream().map(e -> new MenuView.Ranked(e.getKey(), e.getValue())).toList();
    }

    /** Eng ko'p like olganlar (kamida 1 ta like). */
    private static List<MenuView.Ranked> mostLiked(List<Product> all, java.util.Map<Long, Long> likeCounts) {
        java.util.Set<Long> existing = new java.util.HashSet<>();
        all.forEach(p -> existing.add(p.getId()));
        return likeCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0 && existing.contains(e.getKey()))
                .sorted(java.util.Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(TOP_LIMIT)
                .map(e -> new MenuView.Ranked(e.getKey(), e.getValue()))
                .toList();
    }

    /** Aksiya: 0 — o'chirish, 1–90 foiz. Mahsulot nomini qaytaradi. */
    @Transactional
    public String setDiscount(Long productId, int percent) {
        if (percent < 0 || percent > 90) {
            throw new BusinessException("Chegirma 0 dan 90 gaça bölişi kerak");
        }
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        p.setDiscountPercent(percent);
        return p.getName();
    }

    /** Set tarkibi: bo'sh yoki «-» — o'chirish. Mahsulot nomini qaytaradi. */
    @Transactional
    public String setContents(Long productId, String text) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        String clean = text == null ? "" : text.strip();
        p.setContents(clean.isEmpty() || clean.equals("-") ? null : clean);
        return p.getName();
    }

    @Transactional(readOnly = true)
    public MenuView menu() {
        return menu(Lang.UZ);
    }

    @Transactional(readOnly = true)
    public MenuView menu(Lang lang) {
        Map<Long, List<MenuView.ProductView>> byCategory = new LinkedHashMap<>();
        List<Product> all = products.findAllByOrderByCategorySortOrderAscSortOrderAsc();
        java.util.Map<Long, Long> likeCounts = likes.counts();
        java.util.Map<Long, RatingService.Stat> rated = ratingService.stats();
        // o'chirilgan mahsulotlar va yashirilgan/o'chirilgan kategoriyalar mahsulotlari mijozlarga ko'rinmaydi
        all = all.stream().filter(p -> !p.isArchived() && p.getCategory().isActive() && !p.getCategory().isDeleted())
                // yangi mahsulot (5 kun) kategoriyada birinchi, qolganlari eng qimmatidan arzoniga
                .sorted(java.util.Comparator.<Product>comparingInt(p -> isNew(p.getCreatedAt()) ? 0 : 1)
                        .thenComparing(p -> isNew(p.getCreatedAt()) ? p.getCreatedAt() : java.time.Instant.EPOCH,
                                java.util.Comparator.reverseOrder())
                        .thenComparing(MenuService::topPrice, java.util.Comparator.reverseOrder())
                        .thenComparingInt(Product::getSortOrder)
                        .thenComparing(Product::getId))
                .toList();
        for (Product p : all) {
            if (p.getVariants().isEmpty()) {
                continue;
            }
            byCategory.computeIfAbsent(p.getCategory().getId(), k -> new java.util.ArrayList<>()).add(toView(p, lang, likeCounts.getOrDefault(p.getId(), 0L), rated.get(p.getId())));
        }
        java.util.Map<Long, Long> popularity = new java.util.HashMap<>();
        for (Object[] r : orders.categoryPopularity(java.time.Instant.now().minus(POPULAR_WINDOW))) {
            popularity.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        return new MenuView(categories.findAllByActiveTrueAndDeletedFalseOrderBySortOrder().stream()
                // yangi kategoriya (5 kun) birinchi, keyin eng ko'p buyurtma qilinayotgani, keyin admin tartibi
                .sorted(java.util.Comparator.<Category>comparingInt(c -> isNew(c.getCreatedAt()) ? 0 : 1)
                        .thenComparing(c -> popularity.getOrDefault(c.getId(), 0L), java.util.Comparator.reverseOrder())
                        .thenComparingInt(Category::getSortOrder))
                .filter(c -> byCategory.containsKey(c.getId()))
                .map(c -> new MenuView.CategoryView(c.getId(), I18n.content(lang, c.getName(), c.getNameRu()), c.getEmoji(), byCategory.get(c.getId()),
                        isNew(c.getCreatedAt())))
                .toList(), trending(all), mostLiked(all, likeCounts));
    }

    /** Mahsulotning eng qimmat varianti narxi (tartiblash uchun). */
    private static long topPrice(Product p) {
        return p.getVariants().stream().mapToLong(p::priceOf).max().orElse(0);
    }

    private MenuView.ProductView toView(Product p, Lang lang, long likeCount, RatingService.Stat rating) {
        String photoUrl = p.getPhotoFileId() == null ? null
                : "/api/products/" + p.getId() + "/photo?v=" + Integer.toHexString(p.getPhotoFileId().hashCode());
        return new MenuView.ProductView(p.getId(), I18n.content(lang, p.getName(), p.getNameRu()),
                I18n.content(lang, p.getDescription(), p.getDescriptionRu()), p.getEmoji(), photoUrl,
                p.isAvailable(), p.isSignature(), p.getDiscountPercent(), likeCount,
                p.getVariants().stream()
                        // narxlar eng qimmatidan arzoniga
                        .sorted(java.util.Comparator.comparing((ProductVariant v) -> p.priceOf(v), java.util.Comparator.reverseOrder())
                                .thenComparing(ProductVariant::getId))
                        .map(v -> new MenuView.VariantView(v.getId(), I18n.content(lang, v.getName(), v.getNameRu()),
                                p.priceOf(v), p.getDiscountPercent() > 0 ? v.getPrice() : null))
                        .toList(),
                I18n.content(lang, p.getContents(), p.getContentsRu()),
                rating == null ? 0 : rating.avg(), rating == null ? 0 : rating.count(), isNew(p.getCreatedAt()), p.isPerPiece());
    }

    // ---- Admin amallari (bot orqali) ----

    /** «📋 Barcha mahsulotlar» fayli uchun: like soni, baho va sotilgan dona (productId bo'yicha). */
    public record ProductNumbers(Map<Long, Long> likes, Map<Long, RatingService.Stat> ratings, Map<Long, Long> sold) {
    }

    @Transactional(readOnly = true)
    public ProductNumbers productNumbers() {
        Map<Long, Long> sold = new java.util.HashMap<>();
        for (Object[] r : orders.soldPaidByProduct()) {
            sold.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }
        return new ProductNumbers(likes.counts(), ratingService.stats(), sold);
    }

    @Transactional(readOnly = true)
    public List<Product> allProducts() {
        List<Product> list = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(p -> !p.isArchived()).toList();
        list.forEach(p -> p.getVariants().size());
        return list;
    }

    /** Yangi mahsulot o'lchamlari: nomi (bitta o'lchamli bo'lsa null) va narxi. */
    public record NewVariant(String name, long price) {
    }

    /** Admin botdan yangi mahsulot qo'shadi. Yangi mahsulot id sini qaytaradi. */
    @Transactional
    public Long createProduct(Long categoryId, String name, String description, List<NewVariant> sizes, String photoFileId) {
        Category c = categories.findById(categoryId).orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        if (name == null || name.isBlank()) {
            throw new BusinessException("Mahsulot nomi bo'sh");
        }
        if (sizes == null || sizes.isEmpty()) {
            throw new BusinessException("Kamida bitta narx kerak");
        }
        // 5 kun «Yangi» bo'lib birinchi turadi (menyuda), keyin narxiga qarab o'z joyida: qimmatidan arzoniga
        long newMax = sizes.stream().mapToLong(NewVariant::price).max().orElse(0);
        List<Product> siblings = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(x -> !x.isArchived() && x.getCategory().getId().equals(categoryId))
                .sorted(java.util.Comparator.comparingInt(Product::getSortOrder).thenComparing(Product::getId))
                .toList();
        int at = siblings.size();
        for (int k = 0; k < siblings.size(); k++) {
            long max = siblings.get(k).getVariants().stream().mapToLong(ProductVariant::getPrice).max().orElse(0);
            if (max < newMax) {
                at = k;
                break;
            }
        }
        for (int k = 0; k < siblings.size(); k++) {
            siblings.get(k).setSortOrder(k < at ? k + 1 : k + 2);
        }
        int sort = at + 1;
        Product p = new Product();
        p.setCreatedAt(java.time.Instant.now());
        p.setCategory(c);
        p.setName(name.strip());
        p.setDescription(description == null || description.isBlank() ? null : description.strip());
        p.setEmoji(c.getEmoji());
        p.setSortOrder(sort);
        p.setPhotoFileId(photoFileId);
        products.save(p);
        int i = 1;
        for (NewVariant v : sizes) {
            if (v.price() <= 0) {
                throw new BusinessException("Narx musbat bölişi kerak");
            }
            variants.save(new ProductVariant(p, v.name(), v.price(), i++));
        }
        return p.getId();
    }

    @Transactional
    public String rename(Long productId, String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new BusinessException("Nom 1–100 belgi bölişi kerak");
        }
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        p.setName(name.strip());
        return p.getName();
    }

    /** Tavsif: «-» — o'chirish. */
    @Transactional
    public String setDescription(Long productId, String text) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        String clean = text == null ? "" : text.strip();
        p.setDescription(clean.isEmpty() || clean.equals("-") ? null : clean);
        return p.getName();
    }

    /** Yangi kategoriya (boshiga). Menyuda unga mahsulot qo'shilgandan keyin ko'rinadi. */
    @Transactional
    public Long createCategory(String name, String emoji) {
        String n = name == null ? "" : name.strip();
        if (n.length() < 2 || n.length() > 40) {
            throw new BusinessException("Kategoriya nomi 2–40 belgi bölişi kerak");
        }
        boolean exists = categories.findAll().stream().anyMatch(c -> c.isActive() && n.equalsIgnoreCase(c.getName()));
        if (exists) {
            throw new BusinessException("«" + n + "» kategoriyasi allaqaçon bor");
        }
        // yangi kategoriya menyuda birinchi turadi
        int sort = categories.findAll().stream().mapToInt(Category::getSortOrder).min().orElse(1) - 1;
        return categories.save(new Category(n, emoji, sort)).getId();
    }

    /** «Donada sotiladi» belgisini yoqish/o'chirish. Yangi holatni qaytaradi. */
    @Transactional
    public boolean togglePerPiece(Long productId) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        p.setPerPiece(!p.isPerPiece());
        return p.isPerPiece();
    }

    /** Mavjud mahsulotga yangi o'lcham (nomi majburiy). Mahsulot nomini qaytaradi. */
    @Transactional
    public String addVariant(Long productId, String name, long price) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        if (name == null || name.isBlank() || name.strip().length() > 60) {
            throw new BusinessException("Ölçam nomini yozing, masalan: «Kiçik - 120000»");
        }
        if (price <= 0) {
            throw new BusinessException("Narx musbat bölişi kerak");
        }
        boolean exists = p.getVariants().stream().anyMatch(v -> name.strip().equalsIgnoreCase(v.getName()));
        if (exists) {
            throw new BusinessException("«" + name.strip() + "» ölçami allaqaçon bor — narxini «💰 Narx» orqali özgartiring");
        }
        int sort = p.getVariants().stream().mapToInt(ProductVariant::getSortOrder).max().orElse(0) + 1;
        variants.save(new ProductVariant(p, name.strip(), price, sort));
        return p.getName();
    }

    /** O'lchamni o'chirish (oxirgisini emas). Eski buyurtmalarda o'lcham nomi saqlanib qoladi. */
    @Transactional
    public String removeVariant(Long variantId) {
        ProductVariant v = variants.findAllWithProduct(List.of(variantId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("Ölçam topilmadi"));
        Product p = v.getProduct();
        if (p.getVariants().size() <= 1) {
            throw new BusinessException("Oxirgi ölçamni öçirib bölmaydi — mahsulotni öçiring");
        }
        String name = v.getName() == null ? "Standart" : v.getName();
        p.getVariants().remove(v);
        variants.delete(v);
        return name;
    }

    /** Mahsulotni boshqa kategoriyaga ko'chirish (kategoriya oxiriga). Yangi kategoriya nomini qaytaradi. */
    @Transactional
    public String moveProduct(Long productId, Long categoryId) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        Category c = categories.findById(categoryId).orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        int sort = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(x -> x.getCategory().getId().equals(categoryId))
                .mapToInt(Product::getSortOrder).max().orElse(0) + 1;
        p.setCategory(c);
        p.setSortOrder(sort);
        return c.getName();
    }

    /** O'chirish: menyudan yo'qoladi, eski buyurtmalardagi ma'lumot saqlanadi. */
    @Transactional
    public String archive(Long productId) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        p.setArchived(true);
        p.setDiscountPercent(0);
        return p.getName();
    }

    /**
     * Narxlar matni: «150000» (bitta o'lcham) yoki har qatorda «Butun - 300000».
     * Noto'g'ri bo'lsa BusinessException.
     */
    public static List<NewVariant> parseSizes(String text) {
        List<NewVariant> out = new java.util.ArrayList<>();
        List<String> lines = text == null ? List.of() : text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        for (String line : lines) {
            var m = java.util.regex.Pattern.compile("^(?:(.+?)\\s*[-–—:=]\\s*)?([\\d\\s.,]+?)\\s*(?:so'm|söm|сум|sum)?$").matcher(line);
            if (!m.matches()) {
                throw new BusinessException("Tuşunmadim: «" + line + "»");
            }
            String digits = m.group(2).replaceAll("\\D", "");
            if (!digits.matches("\\d{3,9}")) {
                throw new BusinessException("Narx notöğri: «" + line + "»");
            }
            String name = m.group(1) == null ? null : m.group(1).strip();
            if (name == null && lines.size() > 1) {
                throw new BusinessException("Bir nechta ölçam bölsa, har birining nomini yozing: «Butun - 300000»");
            }
            out.add(new NewVariant(name, Long.parseLong(digits)));
        }
        if (out.isEmpty()) {
            throw new BusinessException("Narxni yozing");
        }
        return out;
    }

    /** Stop-list: mahsulotni "tugadi" / "bor" qilish. Yangi holatni qaytaradi. */
    @Transactional
    public boolean toggleAvailability(Long productId) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        p.setAvailable(!p.isAvailable());
        return p.isAvailable();
    }

    @Transactional
    public ProductVariant changePrice(Long variantId, long newPrice) {
        if (newPrice <= 0) {
            throw new BusinessException("Narx musbat bölişi kerak");
        }
        ProductVariant v = variants.findAllWithProduct(List.of(variantId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("Variant #" + variantId + " topilmadi"));
        v.setPrice(newPrice);
        return v;
    }

    @Transactional
    public String setPhoto(Long productId, String fileId) {
        Product p = products.findById(productId)
                .orElseThrow(() -> new BusinessException("Mahsulot #" + productId + " topilmadi"));
        p.setPhotoFileId(fileId);
        return p.getName();
    }

    @Transactional(readOnly = true)
    public String photoFileId(Long productId) {
        return products.findById(productId).map(Product::getPhotoFileId).orElse(null);
    }

    /** Admin uchun kategoriyalar: yashirilganlari ham (🙈), o'chirilganlaridan tashqari. */
    @Transactional(readOnly = true)
    public List<Category> activeCategories() {
        return categories.findAllByDeletedFalseOrderBySortOrderAscIdAsc();
    }

    // ---- Tartib, kategoriyalarni boshqarish ----

    /** Mahsulotni kategoriya ichida bir pog'ona yuqoriga (up = true) yoki pastga suradi. */
    @Transactional
    public void moveProductInCategory(Long productId, boolean up) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        List<Product> list = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(x -> !x.isArchived() && x.getCategory().getId().equals(p.getCategory().getId()))
                .sorted(java.util.Comparator.comparingInt(Product::getSortOrder).thenComparing(Product::getId))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int i = indexOf(list, productId);
        int j = up ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= list.size()) {
            return;   // allaqachon chetda
        }
        java.util.Collections.swap(list, i, j);
        for (int k = 0; k < list.size(); k++) {
            list.get(k).setSortOrder(k + 1);   // tartibni 1, 2, 3... qilib qayta yozamiz
        }
    }

    /** Mahsulotning kategoriyadagi o'rni: [o'rni (1 dan), jami]. */
    @Transactional(readOnly = true)
    public int[] positionInCategory(Long productId) {
        Product p = products.findById(productId).orElseThrow(() -> new BusinessException("Mahsulot topilmadi"));
        List<Product> list = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(x -> !x.isArchived() && x.getCategory().getId().equals(p.getCategory().getId()))
                .sorted(java.util.Comparator.comparingInt(Product::getSortOrder).thenComparing(Product::getId))
                .toList();
        return new int[] {indexOf(list, productId) + 1, list.size()};
    }

    private static int indexOf(List<Product> list, Long id) {
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).getId().equals(id)) {
                return k;
            }
        }
        return -1;
    }

    @Transactional
    public void moveCategory(Long categoryId, boolean up) {
        List<Category> list = new java.util.ArrayList<>(categories.findAllByDeletedFalseOrderBySortOrderAscIdAsc());
        int i = -1;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).getId().equals(categoryId)) {
                i = k;
            }
        }
        int j = up ? i - 1 : i + 1;
        if (i < 0 || j < 0 || j >= list.size()) {
            return;
        }
        java.util.Collections.swap(list, i, j);
        for (int k = 0; k < list.size(); k++) {
            list.get(k).setSortOrder(k + 1);
        }
    }

    @Transactional
    public String renameCategory(Long categoryId, String name) {
        String n = name == null ? "" : name.strip();
        if (n.length() < 2 || n.length() > 40) {
            throw new BusinessException("Kategoriya nomi 2–40 belgi bölişi kerak");
        }
        boolean exists = categories.findAllByDeletedFalseOrderBySortOrderAscIdAsc().stream()
                .anyMatch(c -> !c.getId().equals(categoryId) && n.equalsIgnoreCase(c.getName()));
        if (exists) {
            throw new BusinessException("«" + n + "» kategoriyasi allaqaçon bor");
        }
        Category c = categories.findById(categoryId).orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        c.setName(n);
        return n;
    }

    /** Yashirish / ko'rsatish. Yangi holatni qaytaradi (true — mijozlarga ko'rinadi). */
    @Transactional
    public boolean toggleCategoryVisible(Long categoryId) {
        Category c = categories.findById(categoryId).orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        c.setActive(!c.isActive());
        return c.isActive();
    }

    /** O'chirish — faqat bo'sh kategoriya. Nomini qaytaradi. */
    @Transactional
    public String deleteCategory(Long categoryId) {
        Category c = categories.findById(categoryId).orElseThrow(() -> new BusinessException("Kategoriya topilmadi"));
        long n = products.findAllByOrderByCategorySortOrderAscSortOrderAsc().stream()
                .filter(p -> !p.isArchived() && p.getCategory().getId().equals(categoryId)).count();
        if (n > 0) {
            throw new BusinessException("Kategoriyada " + n + " ta mahsulot bor. Avval ularni boşqa kategoriyaga köçiring yoki öçiring.");
        }
        c.setDeleted(true);
        c.setActive(false);
        return c.getName();
    }
}
