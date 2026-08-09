package io.github.devcircle.contentdeployment;

import io.github.devcircle.content.application.PostService;
import io.github.devcircle.content.api.PostDetailComposer;
import io.github.devcircle.file.infrastructure.MediaRepository;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the production aggregate owns content and media without proxying. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-runtime;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.enabled=true",
        "devcircle.outbox.initial-delay-ms=3600000",
        "devcircle.seed.theme-content.enabled=false",
        "management.tracing.enabled=false",
        "devcircle.s3.endpoint=http://localhost:19000",
        "devcircle.s3.presign-endpoint=http://localhost:19000",
        "devcircle.s3.bucket=content-runtime-test",
        "devcircle.s3.access-key=test-access",
        "devcircle.s3.secret-key=test-secret",
        "devcircle.s3.public-base-url=http://localhost:19000/content-runtime-test"
})
@AutoConfigureMockMvc
class ContentRuntimeContextTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void embedsContentAndMediaServletComponentsWithOneMessagingRuntime() {
        assertThat(context.getBean(PostService.class)).isNotNull();
        assertThat(context.getBean(MediaRepository.class)).isNotNull();
        assertThat(context.getBeansOfType(PostDetailComposer.class)).hasSize(1);
        assertThat(context.containsBean("postController")).isTrue();
        assertThat(context.containsBean("fileController")).isTrue();
        assertThat(context.containsBean("contentApplication")).isFalse();
        assertThat(context.containsBean("fileApplication")).isFalse();
        assertThat(context.getBeansOfType(ReliableOutboxWorker.class)).hasSize(1);
        assertThat(Arrays.stream(context.getBeanDefinitionNames()))
                .noneMatch(name -> name.toLowerCase().contains("routedefinition"))
                .noneMatch(name -> name.toLowerCase().contains("postdetailfacade"));
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("dc-content-aggregate");
        assertThat(context.getEnvironment().getProperty("devcircle.outbox.service-name"))
                .isEqualTo("dc-content");
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                .isEqualTo("servlet");
        assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8202");
    }

    @Test
    void servesContentAndMediaRoutesLocally() throws Exception {
        mockMvc.perform(post("/api/files/intents"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
    }
}
