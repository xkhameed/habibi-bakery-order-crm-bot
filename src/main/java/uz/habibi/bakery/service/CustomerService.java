package uz.habibi.bakery.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.Customer;
import uz.habibi.bakery.i18n.Lang;
import uz.habibi.bakery.repo.CustomerAddressRepository;
import uz.habibi.bakery.repo.CustomerRepository;
import uz.habibi.bakery.repo.ProductLikeRepository;

/** Mijozning tanlagan tili. */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final CustomerAddressRepository addresses;
    private final ProductLikeRepository likes;

    public CustomerService(CustomerRepository customers, CustomerAddressRepository addresses, ProductLikeRepository likes) {
        this.customers = customers;
        this.addresses = addresses;
        this.likes = likes;
    }

    /** Mijoz hali til tanlamagan bo'lsa — bo'sh. */
    @Transactional(readOnly = true)
    public Optional<Lang> language(long telegramId) {
        return customers.findByTelegramId(telegramId)
                .map(Customer::getLanguage)
                .filter(code -> code != null && !code.isBlank())
                .map(Lang::fromCode);
    }

    /** Til tanlagan barcha mijozlar (telegramId → til) — menyu tugmalarini yangilash uchun. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Lang> languages() {
        java.util.Map<Long, Lang> map = new java.util.LinkedHashMap<>();
        for (Customer c : customers.findAll()) {
            if (c.getLanguage() != null && !c.getLanguage().isBlank() && !c.isBlocked()) {
                map.put(c.getTelegramId(), Lang.fromCode(c.getLanguage()));
            }
        }
        return map;
    }

    /** E'lon yuboriladigan mijozlar: to'liq ro'yxatdan o'tgan va bloklanmagan (telegramId → til). */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Lang> broadcastTargets() {
        java.util.Map<Long, Lang> map = new java.util.LinkedHashMap<>();
        for (Customer c : customers.findAll()) {
            if (!c.isBlocked() && c.getLanguage() != null && c.isPhoneVerified() && c.getTermsAcceptedAt() != null) {
                map.put(c.getTelegramId(), Lang.fromCode(c.getLanguage()));
            }
        }
        return map;
    }

    /** Telefon raqami bo'yicha ro'yxatdan o'tgan mijoz (admin qo'shish uchun). */
    @Transactional(readOnly = true)
    public Optional<Long> findByPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 9) {
            return Optional.empty();
        }
        String tail = digits.substring(digits.length() - 9);
        return customers.findAll().stream()
                .filter(c -> c.getPhone() != null && c.getPhone().replaceAll("\\D", "").endsWith(tail))
                .map(Customer::getTelegramId)
                .findFirst();
    }

    /** Mijoz bloklanganmi (bloklangan mijoz bot va Mini App'dan foydalana olmaydi). */
    @Transactional(readOnly = true)
    public boolean isBlocked(long telegramId) {
        return customers.findByTelegramId(telegramId).map(Customer::isBlocked).orElse(false);
    }

    @Transactional
    public void setBlocked(long telegramId, boolean blocked) {
        Customer c = customers.findByTelegramId(telegramId)
                .orElseThrow(() -> new BusinessException("Mijoz topilmadi"));
        c.setBlocked(blocked);
    }

    /** Bitta mijozning yig'masi (admin kartochkasi uchun). */
    @Transactional(readOnly = true)
    public Optional<CustomerSummary> summary(long telegramId) {
        return summaries().stream().filter(c -> c.telegramId() == telegramId).findFirst();
    }

    /** Admin: barcha mijozlar (oxirgi buyurtma bergani birinchi). */
    @Transactional(readOnly = true)
    public java.util.List<CustomerSummary> summaries() {
        return customers.summaries().stream().map(r -> {
            Customer c = (Customer) r[0];
            String tgName = java.util.stream.Stream.of(c.getFirstName(), c.getLastName())
                    .filter(s -> s != null && !s.isBlank()).collect(java.util.stream.Collectors.joining(" "));
            return new CustomerSummary(c.getTelegramId(), c.getName(), tgName, c.getUsername(), c.getPhone(),
                    c.isPhoneVerified(), c.getLanguage() == null ? null : Lang.fromCode(c.getLanguage()),
                    ((Number) r[1]).longValue(), ((Number) r[2]).longValue(), (java.time.Instant) r[3], c.getCreatedAt(),
                    ((Number) r[4]).longValue(), c.isBlocked());
        }).sorted(java.util.Comparator
                // oxirgi buyurtma bergani birinchi, buyurtmasizlar oxirida (yangi ro'yxatdan o'tgani oldinroq)
                .comparing(CustomerSummary::lastOrderAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(CustomerSummary::registeredAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Admin qidiruvi: ism / Telegram ismi / @username bo'yicha (katta-kichik harf, apostrof farq qilmaydi)
     * yoki to'liq telefon raqami bilan (qismi bilan emas). Ko'pi bilan 20 ta.
     */
    @Transactional(readOnly = true)
    public java.util.List<CustomerSummary> search(String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return java.util.List.of();
        }
        String digits = q.replaceAll("\\D", "");
        boolean numeric = q.replaceAll("[\\s+()\\-]", "").matches("\\d+");
        String text = normalize(q.startsWith("@") ? q.substring(1) : q);
        return summaries().stream().filter(c -> {
            if (numeric) {
                // to'liq raqam: 90 123 45 67 yoki +998 90 123 45 67
                String full = digits.length() == 9 ? "998" + digits : digits;
                return full.length() == 12 && c.phone() != null && c.phone().replaceAll("\\D", "").equals(full);
            }
            return text.length() >= 2 && (normalize(c.name()).contains(text) || normalize(c.telegramName()).contains(text)
                    || normalize(c.username()).contains(text));
        }).limit(20).toList();
    }

    /** «Oʻgʻil», «O'g'il», «Öğil» → «ogil» — yozilish farqlarini yo'qotadi. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[ʼʻ'‘’`]", "")
                .replace('ö', 'o').replace('ğ', 'g').replace("ş", "sh").replace("ç", "ch");
    }

    /** Mijozning raqami (Telegram orqali tasdiqlangani yoki oxirgi buyurtmada yozgani). */
    @Transactional(readOnly = true)
    public Optional<String> phone(long telegramId) {
        return customers.findByTelegramId(telegramId).map(Customer::getPhone).filter(p -> p != null && !p.isBlank());
    }

    /** Raqam botda «Raqamni yuborish» tugmasi orqali (Telegram tasdiqlagan holda) ulashilganmi. */
    @Transactional(readOnly = true)
    public boolean hasVerifiedPhone(long telegramId) {
        return customers.findByTelegramId(telegramId).map(Customer::isPhoneVerified).orElse(false);
    }

    /** Shartlarni qachon tasdiqlagan (tasdiqlamagan bo'lsa bo'sh). */
    @Transactional(readOnly = true)
    public Optional<java.time.Instant> termsAcceptedAt(long telegramId) {
        return customers.findByTelegramId(telegramId).map(Customer::getTermsAcceptedAt);
    }

    /** Buyurtma berish shartlarini tasdiqlaganmi. */
    @Transactional(readOnly = true)
    public boolean hasAcceptedTerms(long telegramId) {
        return customers.findByTelegramId(telegramId).map(c -> c.getTermsAcceptedAt() != null).orElse(false);
    }

    @Transactional
    public void acceptTerms(long telegramId) {
        customers.findByTelegramId(telegramId).ifPresent(c -> {
            if (c.getTermsAcceptedAt() == null) {
                c.setTermsAcceptedAt(java.time.Instant.now());
            }
        });
    }

    /** To'liq ro'yxatdan o'tgan: ism, tasdiqlangan raqam va shartlar. */
    @Transactional(readOnly = true)
    public boolean isOnboarded(long telegramId) {
        return customers.findByTelegramId(telegramId)
                .map(c -> c.getLanguage() != null && c.getName() != null && !c.getName().isBlank()
                        && c.isPhoneVerified() && c.getTermsAcceptedAt() != null)
                .orElse(false);
    }

    /** Botda so'ralgan ism. */
    @Transactional(readOnly = true)
    public Optional<String> name(long telegramId) {
        return customers.findByTelegramId(telegramId).map(Customer::getName).filter(n -> n != null && !n.isBlank());
    }

    @Transactional
    public void setName(TelegramUser user, String name) {
        Customer c = customers.findByTelegramId(user.id()).orElseGet(() -> new Customer(user.id()));
        c.setName(name.trim());
        customers.save(c);
    }

    /** /mijoz — admin sinov uchun: profilni tozalab, ro'yxatdan o'tishni boshidan ko'radi. */
    @Transactional
    public void resetProfile(long telegramId) {
        customers.findByTelegramId(telegramId).ifPresent(c -> {
            c.setLanguage(null);
            c.setName(null);
            c.setPhoneVerified(false);
            c.setTermsAcceptedAt(null);
        });
    }

    /**
     * Admin «O'chiriş»: profil (til, ism, raqam, shartlar, blok), manzillar va yoqtirganlar o'chadi —
     * mijoz keyingi safar ro'yxatdan boshidan o'tadi. Buyurtmalar, çeklar va baholar saqlanadi.
     */
    @Transactional
    public void deleteProfile(long telegramId) {
        Customer c = customers.findByTelegramId(telegramId)
                .orElseThrow(() -> new BusinessException("Mijoz topilmadi"));
        c.setLanguage(null);
        c.setName(null);
        c.setPhone(null);
        c.setPhoneVerified(false);
        c.setTermsAcceptedAt(null);
        c.setBlocked(false);
        addresses.deleteAll(addresses.findAllByTelegramIdOrderByCreatedAtAsc(telegramId));
        likes.deleteAll(likes.findAllByTelegramId(telegramId));
    }

    @Transactional
    public void setPhone(TelegramUser user, String phone) {
        Customer c = customers.findByTelegramId(user.id()).orElseGet(() -> new Customer(user.id()));
        if (user.firstName() != null) {
            c.setFirstName(user.firstName());
        }
        if (user.username() != null) {
            c.setUsername(user.username());
        }
        c.setPhone(normalizePhone(phone));
        c.setPhoneVerified(true);
        customers.save(c);
    }

    /** "998901234567" / "+998 90 123-45-67" -> "+998901234567" */
    static String normalizePhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 9) {
            digits = "998" + digits;
        }
        return "+" + digits;
    }

    @Transactional
    public void setLanguage(TelegramUser user, Lang lang) {
        Customer c = customers.findByTelegramId(user.id()).orElseGet(() -> new Customer(user.id()));
        if (user.firstName() != null) {
            c.setFirstName(user.firstName());
        }
        if (user.lastName() != null) {
            c.setLastName(user.lastName());
        }
        if (user.username() != null) {
            c.setUsername(user.username());
        }
        c.setLanguage(lang.code());
        customers.save(c);
    }
}
