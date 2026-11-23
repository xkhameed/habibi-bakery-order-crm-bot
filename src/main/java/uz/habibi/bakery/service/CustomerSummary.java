package uz.habibi.bakery.service;

import java.time.Instant;

import uz.habibi.bakery.i18n.Lang;

/** Admin uchun mijoz haqida qisqacha: profil va xaridlari. */
public record CustomerSummary(
        long telegramId,
        String name,
        String telegramName,
        String username,
        String phone,
        boolean phoneVerified,
        Lang language,
        long orders,
        long spent,
        Instant lastOrderAt,
        Instant registeredAt,
        long paid,
        boolean blocked) {

    /** To'lanmagan qism (bekor qilinmagan buyurtmalar bo'yicha). */
    public long unpaid() {
        return Math.max(0, spent - paid);
    }

    /** Ko'rsatiladigan ism: botda yozgani, bo'lmasa Telegram'dagisi. */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return telegramName == null || telegramName.isBlank() ? "—" : telegramName;
    }
}
