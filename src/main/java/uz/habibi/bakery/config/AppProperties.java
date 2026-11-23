package uz.habibi.bakery.config;

import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String timezone, boolean devMode, Bot bot, Shop shop) {

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public record Bot(String token, String username, Long adminChatId, String webAppUrl) {
        public boolean hasAdmin() {
            return adminChatId != null && adminChatId != 0;
        }

        public boolean hasWebApp() {
            return webAppUrl != null && webAppUrl.startsWith("https://");
        }
    }

    public record Shop(String name, String phone, String cardNumber, String cardHolder,
                       List<String> timeSlots, int daysAhead,
                       String telegram, String instagram, String groupUrl,
                       String openTime, String closeTime) {

        public java.time.LocalTime opens() {
            return java.time.LocalTime.parse(openTime == null || openTime.isBlank() ? "11:00" : openTime);
        }

        public java.time.LocalTime closes() {
            return java.time.LocalTime.parse(closeTime == null || closeTime.isBlank() ? "23:00" : closeTime);
        }

        /** Hozir buyurtma qabul qilinadimi (ish vaqti). */
        public boolean isOpen(java.time.LocalTime now) {
            return !now.isBefore(opens()) && now.isBefore(closes());
        }
    }
}
