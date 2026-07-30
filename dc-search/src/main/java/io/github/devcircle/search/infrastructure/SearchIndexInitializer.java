package io.github.devcircle.search.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the post index from the annotated document mapping before the first
 * event is consumed. Relying on Elasticsearch dynamic mapping would turn
 * filter fields such as status and theme into analyzed text fields.
 */
@Component
public class SearchIndexInitializer implements ApplicationRunner {
    private final ElasticsearchOperations elasticsearchOperations;

    public SearchIndexInitializer(ElasticsearchOperations elasticsearchOperations) {
        this.elasticsearchOperations = elasticsearchOperations;
    }

    @Override
    public void run(ApplicationArguments args) {
        var index = elasticsearchOperations.indexOps(PostSearchDocument.class);
        if (!index.exists()) {
            try {
                index.createWithMapping();
            } catch (RuntimeException creationFailure) {
                // Another instance may have won the create-index race.
                if (!index.exists()) {
                    throw creationFailure;
                }
            }
        }
    }
}
