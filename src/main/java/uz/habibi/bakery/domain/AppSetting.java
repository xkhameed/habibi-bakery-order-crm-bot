package uz.habibi.bakery.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "app_setting")
public class AppSetting {
    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value")
    private String value;

    protected AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
