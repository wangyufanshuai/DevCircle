package io.github.devcircle.admin.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@Component
class KafkaDltRecordReader implements DltRecordReader {
    private static final Logger log = LoggerFactory.getLogger(KafkaDltRecordReader.class);
    private final String bootstrapServers;
    private final long fetchTimeoutMs;

    KafkaDltRecordReader(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${devcircle.kafka.dlt.replay-fetch-timeout-ms:5000}") long fetchTimeoutMs) {
        this.bootstrapServers = bootstrapServers;
        this.fetchTimeoutMs = Math.max(100, Math.min(60_000, fetchTimeoutMs));
    }

    @Override
    public DltRecord read(String sourceTopic, int partition, long offset) {
        String dltTopic = sourceTopic + ".DLT";
        TopicPartition topicPartition = new TopicPartition(dltTopic, partition);
        Properties properties = consumerProperties();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.assign(List.of(topicPartition));
            consumer.seek(topicPartition, offset);
            ConsumerRecord<String, String> record = consumer.poll(Duration.ofMillis(fetchTimeoutMs)).records(topicPartition)
                    .stream()
                    .filter(candidate -> candidate.offset() == offset)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                            "指定的死信记录不存在或尚未到达"));
            var originalTopicHeader = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
            String originalTopic = originalTopicHeader == null ? null
                    : new String(originalTopicHeader.value(), StandardCharsets.UTF_8);
            return new DltRecord(dltTopic, partition, offset, originalTopic, record.key(), record.value());
        } catch (BusinessException ex) {
            throw ex;
        } catch (KafkaException ex) {
            log.warn("DLT read failed sourceTopic={} partition={} offset={}",
                    sourceTopic, partition, offset, ex);
            BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "死信队列暂时不可读取，请稍后重试");
            failure.initCause(ex);
            throw failure;
        }
    }

    Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dc-admin-dlt-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        int apiTimeoutMs = Math.toIntExact(fetchTimeoutMs);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, apiTimeoutMs);
        int requestTimeoutMs = Math.max(5000, apiTimeoutMs);
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, requestTimeoutMs);
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
                Math.max(1000, Math.min(10000, requestTimeoutMs - 1000)));
        return properties;
    }
}
