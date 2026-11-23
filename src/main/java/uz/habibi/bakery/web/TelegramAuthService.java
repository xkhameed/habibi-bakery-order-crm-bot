package uz.habibi.bakery.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.habibi.bakery.config.AppProperties;
import uz.habibi.bakery.service.TelegramUser;

/**
 * Mini App yuborgan initData'ni tekshiradi:
 * https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 * Shu tufayli boshqa odam birovning nomidan buyurtma bera olmaydi.
 */
@Service
public class TelegramAuthService {

    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final TelegramUser DEV_USER = new TelegramUser(1L, "Dev", "User", "dev_user");

    private final AppProperties props;
    private final ObjectMapper json;

    public TelegramAuthService(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public TelegramUser authenticate(String initData) {
        if (initData == null || initData.isBlank()) {
            if (props.devMode()) {
                return DEV_USER;
            }
            throw new UnauthorizedException("err.open_in_telegram");
        }
        String token = props.bot().token();
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("err.unauthorized");
        }

        Map<String, String> params = parse(initData);
        String hash = params.remove("hash");
        if (hash == null) {
            throw new UnauthorizedException("err.unauthorized");
        }
        String dataCheckString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), token);
        byte[] expected = hmac(secret, dataCheckString);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("err.unauthorized");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new UnauthorizedException("err.unauthorized");
        }

        long authDate = Long.parseLong(params.getOrDefault("auth_date", "0"));
        if (Instant.ofEpochSecond(authDate).isBefore(Instant.now().minus(MAX_AGE))) {
            throw new UnauthorizedException("err.session_expired");
        }

        String userJson = params.get("user");
        if (userJson == null) {
            throw new UnauthorizedException("err.unauthorized");
        }
        JsonNode u = json.readTree(userJson);
        return new TelegramUser(u.get("id").asLong(), text(u, "first_name"), text(u, "last_name"), text(u, "username"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> map = new TreeMap<>();
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
