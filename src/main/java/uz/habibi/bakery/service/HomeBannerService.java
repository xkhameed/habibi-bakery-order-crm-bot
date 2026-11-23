package uz.habibi.bakery.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.habibi.bakery.domain.AppSetting;
import uz.habibi.bakery.repo.AppSettingRepository;

/** Mini App bosh sahifasidagi katta rasm. Admin uni botga yuboradi (Telegram file_id saqlanadi). */
@Service
public class HomeBannerService {

    static final String KEY = "home.banner";

    private final AppSettingRepository settings;

    public HomeBannerService(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Optional<String> fileId() {
        return settings.findById(KEY).map(AppSetting::getValue);
    }

    @Transactional
    public void set(String fileId) {
        settings.findById(KEY).ifPresentOrElse(s -> s.setValue(fileId), () -> settings.save(new AppSetting(KEY, fileId)));
    }

    @Transactional
    public void remove() {
        settings.deleteById(KEY);
    }

    public String url() {
        return fileId().map(id -> "/api/banner?v=" + Integer.toHexString(id.hashCode())).orElse(null);
    }
}
