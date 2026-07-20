package com.tabletophelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class TabletopHelperApplication {

    public static void main(String[] args) {
        SpringApplication.run(TabletopHelperApplication.class, args);
    }
}
