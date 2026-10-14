package io.github.devcircle.search.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.stereotype.Component;

import java.util.Map;

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
        // Spring Data stores @Id as Elasticsearch's _id and omits it from the
        // generated source mapping. Search projections deliberately retain a
        // numeric source `id` as the stable cursor tie-breaker, so declare it
        // before the first document arrives. Without this explicit mapping an
        // empty, otherwise healthy index rejects every query that sorts by id.
        Document properties = Document.create();
        properties.put("id", Map.of("type", "long"));
        properties.put("aggregateVersion", Map.of("type", "long"));
        Document mapping = Document.create();
        mapping.put("properties", properties);
        index.putMapping(mapping);
    }
}
