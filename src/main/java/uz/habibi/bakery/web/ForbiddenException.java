package uz.habibi.bakery.web;

import uz.habibi.bakery.i18n.I18n;
import uz.habibi.bakery.i18n.Lang;

/** Ruxsat yo'q (masalan, bloklangan mijoz). */
public class ForbiddenException extends RuntimeException {
    private final String key;

    public ForbiddenException(String key) {
        super(I18n.t(Lang.UZ, key));
        this.key = key;
    }

    public String localized(Lang lang) {
        return I18n.t(lang, key);
    }
}
