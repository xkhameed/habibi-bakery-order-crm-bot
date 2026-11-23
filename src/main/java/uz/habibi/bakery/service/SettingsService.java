package uz.habibi.bakery.service;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.domain.AppSetting;
import uz.habibi.bakery.repo.AppSettingRepository;

/**
 * Admin botdan o'zgartira oladigan sozlamalar (karta, ish vaqti, buyurtmalarni to'xtatish, qo'shimcha adminlar).
 * Bazada saqlanmagan bo'lsa — application.yml / .env dagi qiymat ishlatiladi.
 */
@Service
public class SettingsService {

    static final String CARD_NUMBER = "pay.card_number";
    static final String CARD_HOLDER = "pay.card_holder";
    static final String OPEN = "shop.open";
    static final String CLOSE = "shop.close";
    static final String PAUSED = "shop.paused";
    static final String EXTRA_ADMINS = "admin.extra";
    /** Super adminlar — qo'shimcha adminlar ichidan; adminlarni qo'sha/olib tashlay oladi. */
    static final String SUPER_ADMINS = "admin.super";

    private final AppSettingRepository settings;
    private final AppProperties props;

    public SettingsService(AppSettingRepository settings, AppProperties props) {
        this.settings = settings;
        this.props = props;
    }

    private Optional<String> get(String key) {
        return settings.findById(key).map(AppSetting::getValue).filter(v -> v != null && !v.isBlank());
    }

    private void put(String key, String value) {
        if (value == null) {
            settings.deleteById(key);
            return;
        }
        settings.findById(key).ifPresentOrElse(s -> s.setValue(value), () -> settings.save(new AppSetting(key, value)));
    }

    /** application.yml dagi do'kon sozlamalari + admin botda o'zgartirganlari. */
    @Transactional(readOnly = true)
    public AppProperties.Shop shop() {
        AppProperties.Shop s = props.shop();
        return new AppProperties.Shop(s.name(), s.phone(),
                get(CARD_NUMBER).orElse(s.cardNumber()), get(CARD_HOLDER).orElse(s.cardHolder()),
                s.timeSlots(), s.daysAhead(), s.telegram(), s.instagram(), s.groupUrl(),
                get(OPEN).orElse(s.openTime()), get(CLOSE).orElse(s.closeTime()));
    }

    // ---- to'lov kartasi ----

    @Transactional
    public void setCard(String number, String holder) {
        put(CARD_NUMBER, number);
        put(CARD_HOLDER, holder);
    }

    /** "8600123412341234" / "8600 1234 1234 1234" → "8600 1234 1234 1234"; noto'g'ri bo'lsa null. */
    public static String normalizeCard(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() != 16) {
            return null;
        }
        return d.substring(0, 4) + " " + d.substring(4, 8) + " " + d.substring(8, 12) + " " + d.substring(12);
    }

    // ---- ish vaqti va to'xtatish ----

    @Transactional
    public void setHours(LocalTime open, LocalTime close) {
        if (!open.isBefore(close)) {
            throw new BusinessException("Oçiliş vaqti yopiliş vaqtidan oldin bölişi kerak");
        }
        put(OPEN, open.toString());
        put(CLOSE, close.toString());
    }

    /** "10:00-22:00", "10-22", "10.00 – 22.00" → [10:00, 22:00]; noto'g'ri bo'lsa null. */
    public static LocalTime[] parseHours(String text) {
        var m = java.util.regex.Pattern.compile("^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*[-–—]\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*$")
                .matcher(text == null ? "" : text);
        if (!m.matches()) {
            return null;
        }
        try {
            LocalTime a = LocalTime.of(Integer.parseInt(m.group(1)), m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
            int h2 = Integer.parseInt(m.group(3));
            int m2 = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            LocalTime b = h2 == 24 && m2 == 0 ? LocalTime.of(23, 59) : LocalTime.of(h2, m2);
            return new LocalTime[] {a, b};
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public boolean isPaused() {
        return get(PAUSED).map(Boolean::parseBoolean).orElse(false);
    }

    @Transactional
    public void setPaused(boolean paused) {
        put(PAUSED, paused ? "true" : null);
    }


    // ---- adminlar ----

    @Transactional(readOnly = true)
    public Set<Long> extraAdmins() {
        Set<Long> ids = new LinkedHashSet<>();
        for (String s : get(EXTRA_ADMINS).orElse("").split(",")) {
            if (s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s.strip()));
            } catch (NumberFormatException ignored) {
                // buzilgan yozuv — tashlab ketamiz
            }
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Set<Long> superAdmins() {
        Set<Long> ids = new LinkedHashSet<>();
        for (String s : get(SUPER_ADMINS).orElse("").split(",")) {
            if (s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s.strip()));
            } catch (NumberFormatException ignored) {
                // buzilgan yozuv — tashlab ketamiz
            }
        }
        ids.retainAll(extraAdmins());
        return ids;
    }

    @Transactional(readOnly = true)
    public boolean isSuperAdmin(long id) {
        return superAdmins().contains(id);
    }

    /** Adminlarni qo'sha / olib tashlay oladimi: asosiy admin yoki super admin. */
    @Transactional(readOnly = true)
    public boolean canManageAdmins(long id) {
        return isMainAdmin(id) || isSuperAdmin(id);
    }

    @Transactional
    public void setSuperAdmin(long telegramId, boolean on) {
        Set<Long> ids = superAdmins();
        if (on && extraAdmins().contains(telegramId)) {
            ids.add(telegramId);
        } else {
            ids.remove(telegramId);
        }
        put(SUPER_ADMINS, ids.isEmpty() ? null : join(ids));
    }

    @Transactional
    public void addAdmin(long telegramId) {
        Set<Long> ids = extraAdmins();
        ids.add(telegramId);
        put(EXTRA_ADMINS, join(ids));
    }

    @Transactional
    public void removeAdmin(long telegramId) {
        Set<Long> ids = extraAdmins();
        ids.remove(telegramId);
        put(EXTRA_ADMINS, ids.isEmpty() ? null : join(ids));
        setSuperAdmin(telegramId, false);
    }

    private static String join(Set<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    /** Asosiy admin (.env dagi ADMIN_CHAT_ID). */
    public boolean isMainAdmin(long id) {
        return props.bot().hasAdmin() && props.bot().adminChatId() == id;
    }

    /** Asosiy yoki qo'shimcha admin. */
    @Transactional(readOnly = true)
    public boolean isAdmin(long id) {
        return isMainAdmin(id) || extraAdmins().contains(id);
    }

    /** Buyurtma va cheklar boradigan barcha admin chatlari (asosiysi birinchi). */
    @Transactional(readOnly = true)
    public java.util.List<Long> adminChats() {
        java.util.List<Long> list = new java.util.ArrayList<>();
        if (props.bot().hasAdmin()) {
            list.add(props.bot().adminChatId());
        }
        for (Long id : extraAdmins()) {
            if (!list.contains(id)) {
                list.add(id);
            }
        }
        return list;
    }
}
