package com.nookx.ingester;

import com.nookx.ingester.config.IngesterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IngesterProperties.class)
public class NookxIngesterApplication {

    public static void main(final String[] args) {
        SpringApplication.run(NookxIngesterApplication.class, args);
    }
}
