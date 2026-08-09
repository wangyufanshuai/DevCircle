package io.github.devcircle.discovery;

import io.github.devcircle.common.config.CommonWebConfig;
import io.github.devcircle.messaging.config.KafkaConsumerReliabilityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * The production discovery process owns search directly. The search module is
 * still a normal Maven dependency so its executable can be selected for a
 * legacy rollback, but its application class is deliberately outside this scan.
 */
@SpringBootApplication(scanBasePackages = {
        "io.github.devcircle.discovery",
        "io.github.devcircle.search.api",
        "io.github.devcircle.search.application",
        "io.github.devcircle.search.infrastructure"
})
@Import({CommonWebConfig.class, KafkaConsumerReliabilityConfig.class})
public class DiscoveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryApplication.class, args);
    }
}
