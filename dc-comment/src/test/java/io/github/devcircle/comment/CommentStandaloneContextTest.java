package io.github.devcircle.comment;

import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:comment-standalone;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.enabled=false",
        "management.tracing.enabled=false"
})
class CommentStandaloneContextTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void standaloneApplicationStillOwnsCommentRuntime() {
        assertThat(context.getBean(CommentService.class)).isNotNull();
        assertThat(context.getBean(CommentRepository.class)).isNotNull();
        assertThat(context.containsBean("commentController")).isTrue();
        assertThat(context.containsBean("postCommentController")).isTrue();
        assertThat(context.containsBean("commentProducerFactory")).isTrue();
        assertThat(context.containsBean("commentKafkaTemplate")).isTrue();
        assertThat(context.getBean("commentTransactionManager", PlatformTransactionManager.class)).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("dc-comment");
    }
}
