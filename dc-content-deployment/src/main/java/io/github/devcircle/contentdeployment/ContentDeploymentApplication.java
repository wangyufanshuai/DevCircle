package io.github.devcircle.contentdeployment;

import io.github.devcircle.common.config.CommonWebConfig;
import io.github.devcircle.messaging.config.KafkaConsumerReliabilityConfig;
import io.github.devcircle.messaging.config.ReliableMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Production content runtime. Content and media components run in this JVM;
 * comments and administration intentionally remain separate deployments.
 */
@SpringBootApplication(scanBasePackages = {
        "io.github.devcircle.content.api",
        "io.github.devcircle.content.application",
        "io.github.devcircle.content.domain",
        "io.github.devcircle.content.infrastructure",
        "io.github.devcircle.file.api",
        "io.github.devcircle.file.infrastructure"
})
@EnableKafka
@Import({
        CommonWebConfig.class,
        ReliableMessagingConfig.class,
        KafkaConsumerReliabilityConfig.class,
        ContentRuntimeFlywayConfig.class,
        ContentCompositionConfig.class,
        ContentPostDetailComposer.class
})
public class ContentDeploymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentDeploymentApplication.class, args);
    }
}
