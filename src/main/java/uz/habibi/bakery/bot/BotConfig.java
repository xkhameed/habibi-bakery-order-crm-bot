package uz.habibi.bakery.bot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import uz.habibi.bakery.config.AppProperties;

/** BOT_TOKEN berilmagan bo'lsa, bot qismi umuman ishga tushmaydi (API va Mini App baribir ishlaydi). */
@Configuration
@ConditionalOnExpression(BotConfig.ENABLED)
public class BotConfig {

    public static final String ENABLED = "!'${app.bot.token:}'.isBlank()";

    @Bean
    TelegramClient telegramClient(AppProperties props) {
        return new OkHttpTelegramClient(props.bot().token());
    }

    @Bean(destroyMethod = "close")
    TelegramBotsLongPollingApplication telegramBotsApplication() {
        return new TelegramBotsLongPollingApplication();
    }
}
