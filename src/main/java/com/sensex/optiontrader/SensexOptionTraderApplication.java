package com.sensex.optiontrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class SensexOptionTraderApplication {
    public static void main(String[] args) {
        SpringApplication.run(SensexOptionTraderApplication.class, args);
    }
}