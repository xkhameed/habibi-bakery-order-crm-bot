package uz.habibi.bakery.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.habibi.bakery.domain.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
