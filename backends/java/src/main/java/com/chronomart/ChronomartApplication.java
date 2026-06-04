package com.chronomart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChronomartApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChronomartApplication.class, args);
    }
}
