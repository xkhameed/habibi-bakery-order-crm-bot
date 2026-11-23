package uz.habibi.bakery.service;

import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;

/**
 * Foydalanuvchiga ko'rsatiladigan xato. key — tarjima kaliti (i18n/messages_*.properties);
 * kalit topilmasa, matnning o'zi ishlatiladi (admin uchun o'zbekcha xabarlar).
 */
public class BusinessException extends RuntimeException {
    private final String key;
    private final transient Object[] args;

    public BusinessException(String key, Object... args) {
        super(I18n.t(Lang.UZ, key, args));
        this.key = key;
        this.args = args;
    }

    public String localized(Lang lang) {
        return I18n.t(lang, key, args);
    }
}
