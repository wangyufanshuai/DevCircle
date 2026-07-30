package io.github.devcircle.user;

import io.github.devcircle.common.config.KafkaConsumerReliabilityConfig;
import io.github.devcircle.common.config.ReliableMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@Import({ReliableMessagingConfig.class, KafkaConsumerReliabilityConfig.class})
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
