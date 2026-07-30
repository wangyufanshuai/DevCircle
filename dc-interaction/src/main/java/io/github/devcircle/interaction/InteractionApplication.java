package io.github.devcircle.interaction;

import io.github.devcircle.common.config.ReliableMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ReliableMessagingConfig.class)
public class InteractionApplication {
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
