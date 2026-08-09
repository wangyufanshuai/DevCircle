package io.github.devcircle.identity;

import io.github.devcircle.common.config.CommonWebConfig;
import io.github.devcircle.messaging.config.ReliableMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = {
        "io.github.devcircle.identity",
        "io.github.devcircle.auth.api",
        "io.github.devcircle.auth.application",
        "io.github.devcircle.auth.config"
})
@Import({CommonWebConfig.class, ReliableMessagingConfig.class})
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
