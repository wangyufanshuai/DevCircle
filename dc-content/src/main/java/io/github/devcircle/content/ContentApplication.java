package io.github.devcircle.content;

import io.github.devcircle.messaging.config.ReliableMessagingConfig;
import io.github.devcircle.messaging.config.KafkaConsumerReliabilityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@Import({ReliableMessagingConfig.class, KafkaConsumerReliabilityConfig.class})
public class ContentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
