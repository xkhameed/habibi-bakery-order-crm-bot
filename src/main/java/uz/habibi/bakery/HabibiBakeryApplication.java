package uz.habibi.bakery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class HabibiBakeryApplication {

	public static void main(String[] args) {
		SpringApplication.run(HabibiBakeryApplication.class, args);
	}
}
