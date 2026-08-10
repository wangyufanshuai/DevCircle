package io.github.devcircle.identity;

import io.github.devcircle.common.config.CommonWebConfig;
import io.github.devcircle.messaging.config.ReliableMessagingConfig;
import io.github.devcircle.messaging.config.KafkaConsumerReliabilityConfig;
import io.github.devcircle.user.infrastructure.LegacyProfileBackfillRepository;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication(scanBasePackages = {
        "io.github.devcircle.identity",
        "io.github.devcircle.auth.api",
        "io.github.devcircle.auth.application",
        "io.github.devcircle.auth.config",
        "io.github.devcircle.user.api",
        "io.github.devcircle.user.application"
})
@EnableKafka
@Import({
        CommonWebConfig.class,
        ReliableMessagingConfig.class,
        KafkaConsumerReliabilityConfig.class,
        IdentityDataSourceConfig.class,
        UserGraphRepository.class,
        LegacyProfileBackfillRepository.class
})
public class IdentityApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
