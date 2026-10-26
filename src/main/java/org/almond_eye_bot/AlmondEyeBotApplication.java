package org.almond_eye_bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlmondEyeBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlmondEyeBotApplication.class, args);
    }

}
