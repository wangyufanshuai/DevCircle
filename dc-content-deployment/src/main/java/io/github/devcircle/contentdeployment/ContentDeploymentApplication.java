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
 * Comment can run with isolated persistence in this JVM, but the standalone
 * service remains the runtime owner until cutover evidence is available.
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
        ContentDataAccessConfig.class,
        ContentRuntimeFlywayConfig.class,
        ContentCommentRuntimeConfig.class,
        ContentCompositionConfig.class,
        ContentPostDetailComposer.class
})
public class ContentDeploymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentDeploymentApplication.class, args);
    }
}
