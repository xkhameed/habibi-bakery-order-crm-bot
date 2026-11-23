package uz.habibi.bakery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.service.TelegramUser;

class TelegramAuthServiceTest {

    private static final String TOKEN = "123456:TEST-token";

    private TelegramAuthService service(boolean devMode) {
        var props = new AppProperties("Asia/Tashkent", devMode,
                new AppProperties.Bot(TOKEN, "bot", 0L, ""),
                new AppProperties.Shop("Habibi", "", "", "", List.of(), 7, null, null, null, null, null));
        return new TelegramAuthService(props, JsonMapper.builder().build());
    }

    /** Telegram qanday imzolasa, xuddi shunday imzolangan initData yasaydi. */
    private static String signedInitData(long authDate) {
        Map<String, String> params = new TreeMap<>();
        params.put("auth_date", String.valueOf(authDate));
        params.put("query_id", "AAHdF6IQAAAAAN0XohDhrOrc");
        params.put("user", "{\"id\":777,\"first_name\":\"Ali\",\"last_name\":null,\"username\":\"ali_uz\"}");
        String check = params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
        byte[] secret = TelegramAuthService.hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN);
        params.put("hash", HexFormat.of().formatHex(TelegramAuthService.hmac(secret, check)));
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    @Test
    void acceptsValidSignature() {
        TelegramUser user = service(false).authenticate(signedInitData(Instant.now().getEpochSecond()));
        assertThat(user.id()).isEqualTo(777);
        assertThat(user.firstName()).isEqualTo("Ali");
        assertThat(user.lastName()).isNull();
        assertThat(user.username()).isEqualTo("ali_uz");
    }

    @Test
    void rejectsTamperedUser() {
        String forged = signedInitData(Instant.now().getEpochSecond()).replace("777", "778");
        assertThatThrownBy(() -> service(false).authenticate(forged)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsExpiredSession() {
        String old = signedInitData(Instant.now().minusSeconds(2 * 24 * 3600).getEpochSecond());
        assertThatThrownBy(() -> service(false).authenticate(old)).hasMessageContaining("eskirgan");
    }

    @Test
    void emptyInitDataOnlyAllowedInDevMode() {
        assertThatThrownBy(() -> service(false).authenticate("")).isInstanceOf(UnauthorizedException.class);
        assertThat(service(true).authenticate("").id()).isEqualTo(1L);
    }

    @org.junit.jupiter.api.Test
    void workingHoursDefaultTo11to23() {
        var shop = new AppProperties.Shop("Habibi", "", "", "", List.of(), 7, null, null, null, null, null);
        org.assertj.core.api.Assertions.assertThat(shop.isOpen(java.time.LocalTime.of(10, 59))).isFalse();
        org.assertj.core.api.Assertions.assertThat(shop.isOpen(java.time.LocalTime.of(11, 0))).isTrue();
        org.assertj.core.api.Assertions.assertThat(shop.isOpen(java.time.LocalTime.of(22, 59))).isTrue();
        org.assertj.core.api.Assertions.assertThat(shop.isOpen(java.time.LocalTime.of(23, 0))).isFalse();
    }
}
