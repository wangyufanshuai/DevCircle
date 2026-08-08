package io.github.devcircle.common.event;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxStatusServiceTest {
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                "jdbc:h2:mem:outbox-status;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.sql("DROP TABLE IF EXISTS outbox_event").update();
        jdbc.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL)
                """).update();
    }

    @Test
    void reportsDeadRetryableAndBlockedEventsForOneProducerDomain() {
        insert("post.submitted", "post-1", "DEAD");
        insert("post.submitted", "post-1", "NEW");
        insert("content.rejected", "post-1", "FAILED");
        insert("post.submitted", "post-2", "PUBLISHED");
        OutboxStatusService service = new OutboxStatusService(jdbc, "dc-content");

        OutboxStatusView status = service.snapshot();

        assertThat(status).isEqualTo(new OutboxStatusView("dc-content", 3, 2, 1, 1));
    }

    @Test
    void internalEndpointRequiresTheSharedOperationsKey() {
        OutboxInternalController controller = new OutboxInternalController(
                new OutboxStatusService(jdbc, "dc-auth"), "correct-internal-key");

        assertThat(controller.status("correct-internal-key").service()).isEqualTo("dc-auth");
        assertThatThrownBy(() -> controller.status("wrong-key"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> controller.status(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private void insert(String topic, String aggregateId, String status) {
        jdbc.sql("INSERT INTO outbox_event(topic, aggregate_id, status) VALUES (:topic, :aggregateId, :status)")
                .param("topic", topic).param("aggregateId", aggregateId).param("status", status).update();
    }
}
