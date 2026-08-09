package io.github.devcircle.discovery;

import io.github.devcircle.search.application.SearchService;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import io.github.devcircle.search.infrastructure.SearchIndexInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the production discovery process owns search without a proxy hop. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:discovery-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "spring.elasticsearch.uris=http://localhost:19200",
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.enabled=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@Import(DiscoveryApplicationContextTest.TestBeans.class)
class DiscoveryApplicationContextTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void embedsSearchServletComponentsAndFlywayResourcesWithoutGatewayRoutes() {
        assertThat(context.getBean(SearchService.class)).isNotNull();
        assertThat(context.containsBean("searchApplication")).isFalse();
        assertThat(context.getBean(SearchIndexInitializer.class)).isNotNull();
        assertThat(context.containsBean("searchController")).isTrue();
        assertThat(context.containsBean("searchQueryService")).isTrue();
        assertThat(context.containsBean("searchIndexService")).isTrue();
        assertThat(context.containsBean("searchEventCoordinator")).isTrue();
        assertThat(context.containsBean("searchProjectionStore")).isTrue();
        assertThat(context.getBean(CommonErrorHandler.class)).isNotNull();
        assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers())
                .hasSize(2)
                .allMatch(container -> !container.isRunning());
        assertThat(Arrays.stream(context.getBeanDefinitionNames()))
                .noneMatch(name -> name.toLowerCase().contains("routedefinition"));
        assertThat(PostSearchDocument.class.getResource("PostSearchDocument.class")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("db/migration/V1__search_event_state.sql")).isNotNull();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_consumer_dedup")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_event_watermark")
                .query(Integer.class).single()).isZero();
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("dc-discovery");
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8203");
    }

    @Test
    void searchDependencyFailureKeepsThePublicFailureContractAndTraceBoundary() throws Exception {
        when(elasticsearchOperations.search(any(Query.class), eq(PostSearchDocument.class)))
                .thenThrow(new IllegalStateException("elasticsearch node 10.0.0.8 is offline"));

        mockMvc.perform(get("/api/search/posts")
                        .queryParam("keyword", "outbox")
                        .header("X-Trace-Id", "discovery-search-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Trace-Id", "discovery-search-outage"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value("discovery-search-outage"))
                .andExpect(content().string(not(containsString("10.0.0.8"))))
                .andExpect(content().string(not(containsString("elasticsearch node"))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        ElasticsearchOperations elasticsearchOperations() {
            ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
            IndexOperations index = mock(IndexOperations.class);
            when(operations.indexOps(PostSearchDocument.class)).thenReturn(index);
            when(index.exists()).thenReturn(true);
            return operations;
        }
    }
}
